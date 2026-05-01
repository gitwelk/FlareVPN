package flare.client.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import flare.client.app.data.db.AppDatabase
import flare.client.app.data.model.DisplayItem
import flare.client.app.data.model.ProfileEntity
import flare.client.app.data.model.SubscriptionEntity
import flare.client.app.data.model.PingState
import flare.client.app.data.parser.ClipboardParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import flare.client.app.data.repository.ProfileRepository
import flare.client.app.data.SettingsManager
import flare.client.app.service.FlareVpnService
import flare.client.app.R
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDatabase.getInstance(application)
    }
    private val repository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProfileRepository(db.profileDao(), db.subscriptionDao())
    }

    companion object {
        private const val VIRTUAL_SUB_ID = -1L
    }

    private val _connectionTimerText = MutableStateFlow("")
    val connectionTimerText: StateFlow<String> = _connectionTimerText.asStateFlow()

    private var timerJob: kotlinx.coroutines.Job? = null
    private var selectionJob: kotlinx.coroutines.Job? = null
    private val expandedSubs = MutableStateFlow<Set<Long>>(emptySet())
    private val _refreshingSubs = MutableStateFlow<Set<Long>>(emptySet())
    val refreshingSubs: StateFlow<Set<Long>> = _refreshingSubs.asStateFlow()

    private var autoUpdateJob: kotlinx.coroutines.Job? = null
    private var healthCheckJob: kotlinx.coroutines.Job? = null
    private var recoveryJob: kotlinx.coroutines.Job? = null

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())

    private val _selectedProfileId = MutableStateFlow<Long?>(null)
    val selectedProfileId: StateFlow<Long?> = _selectedProfileId.asStateFlow()

    private val _editingProfile = MutableStateFlow<ProfileEntity?>(null)
    val editingProfile: StateFlow<ProfileEntity?> = _editingProfile.asStateFlow()

    private val _editingSubscription = MutableStateFlow<SubscriptionEntity?>(null)
    val editingSubscription: StateFlow<SubscriptionEntity?> = _editingSubscription.asStateFlow()

    private val _importEvent = MutableSharedFlow<ImportEvent>()
    val importEvent: SharedFlow<ImportEvent> = _importEvent.asSharedFlow()

    private val _displayItems = MutableStateFlow<List<DisplayItem>>(emptyList())
    val displayItems: StateFlow<List<DisplayItem>> = _displayItems.asStateFlow()

    private val _isStartupLoading = MutableStateFlow(true)
    val isStartupLoading: StateFlow<Boolean> = _isStartupLoading.asStateFlow()

    val isAnySubscriptionExpanded: StateFlow<Boolean> = expandedSubs
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val initMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var isInitialized = false
    private var isReceiverRegistered = false
    private var displayItemsJob: kotlinx.coroutines.Job? = null

    sealed class ImportEvent {
        object Loading : ImportEvent()
        data class Success(val message: String) : ImportEvent()
        data class Error(val message: String) : ImportEvent()
        data class NeedPermission(val intent: Intent) : ImportEvent()
    }

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == FlareVpnService.BROADCAST_STATE) {
                val connected = intent.getBooleanExtra(FlareVpnService.EXTRA_CONNECTED, false)
                val hasError = intent.getBooleanExtra(FlareVpnService.EXTRA_ERROR, false)
                _connectionState.value = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                if (connected) {
                    startTimer()
                    startHealthCheckJob()
                } else {
                    stopTimer()
                    if (hasError) {
                        val settings = SettingsManager(context)
                        if (settings.isAdaptiveTunnelEnabled) {
                            startRecovery()
                        } else {
                            flare.client.app.ui.notification.AppNotificationManager.showNotification(flare.client.app.ui.notification.NotificationType.ERROR, context.getString(R.string.vpn_error_tunnel_creation), 4)
                        }
                    }
                }
            }
        }
    }

    fun initializeAsync() {
        if (isInitialized) return
        viewModelScope.launch {
            ensureInitialized()
        }
    }

    private suspend fun ensureInitialized() {
        if (isInitialized) return
        initMutex.withLock {
            if (isInitialized) return@withLock

            val app = getApplication<Application>()
            if (!isReceiverRegistered) {
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    Context.RECEIVER_NOT_EXPORTED
                } else {
                    0
                }
                app.registerReceiver(vpnReceiver, IntentFilter(FlareVpnService.BROADCAST_STATE), flags)
                isReceiverRegistered = true
            }

            displayItemsJob?.cancel()
            displayItemsJob = combine(
                repository.getAllSubscriptions(),
                repository.getAllProfiles(),
                expandedSubs,
                _selectedProfileId,
                _pingStates,
                _refreshingSubs
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val subs = args[0] as List<SubscriptionEntity>
                val allProfiles = args[1] as List<ProfileEntity>
                val expanded = args[2] as Set<Long>
                val selId = args[3] as Long?
                val pings = args[4] as Map<Long, PingState>
                val refreshing = args[5] as Set<Long>

                val profilesBySub = allProfiles.groupBy { it.subscriptionId }
                val standalone = allProfiles.filter { it.subscriptionId == null }
                buildDisplayList(subs, standalone, profilesBySub, expanded, selId, pings, refreshing)
            }
                .onEach { items ->
                    _displayItems.value = items
                    _isStartupLoading.value = false
                }
                .launchIn(viewModelScope)

            if (flare.client.app.singbox.SingBoxManager.isRunning) {
                _connectionState.value = ConnectionState.CONNECTED
                startTimer()
                startHealthCheckJob()
            }

            viewModelScope.launch {
                _selectedProfileId.value = repository.getSelectedProfile()?.id
            }
            startAutoUpdateJob()
            startBestProfileJob()
            startUpdateCheckJob()
            isInitialized = true
        }
    }

    init {
        initializeAsync()
    }

    override fun onCleared() {
        super.onCleared()
        val app = getApplication<Application>()
        if (isReceiverRegistered) {
            app.unregisterReceiver(vpnReceiver)
            isReceiverRegistered = false
        }
    }

    fun toggleSubscriptionExpanded(subId: Long) = expandedSubs.update { if (subId in it) it - subId else it + subId }
    fun selectProfile(profileId: Long) {
        selectionJob?.cancel()
        recoveryJob?.cancel()
        selectionJob = viewModelScope.launch {
            ensureInitialized()
            repository.selectProfile(profileId)
            _selectedProfileId.value = profileId
            
            if (_connectionState.value != ConnectionState.DISCONNECTED) {
                delay(250) 
                stopVpn()
                delay(100)
                startVpn()
            }
        }
    }
    fun deleteSubscription(subId: Long) {
        val subName = if (subId == VIRTUAL_SUB_ID) {
            getApplication<Application>().getString(R.string.sub_single_profiles)
        } else {
            displayItems.value.filterIsInstance<DisplayItem.SubscriptionItem>().find { it.entity.id == subId }?.entity?.name ?: getApplication<Application>().getString(R.string.label_unknown)
        }
        viewModelScope.launch {
            ensureInitialized()
            if (subId == VIRTUAL_SUB_ID) {
                repository.deleteStandaloneProfiles()
            } else {
                repository.deleteSubscriptionById(subId)
            }
            expandedSubs.update { it - subId }
            if (repository.getSelectedProfile() == null) _selectedProfileId.value = null
            flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.SUCCESS,
                getApplication<Application>().getString(R.string.sub_deleted_success, subName),
                3
            )
        }
    }
    fun speedTestSubscription(subId: Long) {
        viewModelScope.launch {
            ensureInitialized()
            val profiles = if (subId == VIRTUAL_SUB_ID) {
                repository.getAllProfiles().first().filter { it.subscriptionId == null }
            } else {
                repository.getAllProfiles().first().filter { it.subscriptionId == subId }
            }
            if (profiles.isEmpty()) return@launch
            speedTestProfile(profiles)
        }
    }

    fun speedTestProfile(profiles: List<ProfileEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentPings = _pingStates.value.toMutableMap()
            profiles.forEach { profile ->
                currentPings[profile.id] = PingState.Loading
            }
            _pingStates.value = currentPings

            val app = getApplication<Application>()
            val settings = SettingsManager(app)
            val isProxy = settings.pingType.startsWith("via")

            if (!isProxy) {
                val method = if (settings.pingType == "TCP") "TCP" else "ICMP"
                profiles.forEach { profile ->
                    launch {
                        val (latency, error) = flare.client.app.util.PingHelper.pingDirect(profile, method)
                        _pingStates.update { it.toMutableMap().apply {
                            this[profile.id] = PingState.Result(latency, latency < 0, error)
                        } }
                    }
                }
            } else {
                val httpMethod = if (settings.pingType == "via proxy GET") "GET" else "HEAD"
                flare.client.app.util.PingHelper.pingProxyBatch(
                    context = app,
                    profiles = profiles,
                    testUrl = settings.pingTestUrl,
                    httpMethod = httpMethod
                ) { id, latency, error ->
                    _pingStates.update { it.toMutableMap().apply {
                        this[id] = PingState.Result(latency, latency < 0, error)
                    } }
                }
            }
        }
    }
    fun showSubscriptionOptions(subId: Long) {}
    fun setEditingProfile(p: ProfileEntity?) { _editingProfile.value = p; _editingSubscription.value = null }
    fun setEditingSubscription(s: SubscriptionEntity?) { _editingSubscription.value = s; _editingProfile.value = null }
    fun updateProfileConfig(id: Long, json: String) { viewModelScope.launch(Dispatchers.IO) { ensureInitialized(); repository.updateProfileConfig(id, json) } }
    fun updateProfile(id: Long, name: String, json: String) { viewModelScope.launch(Dispatchers.IO) { ensureInitialized(); repository.updateProfile(id, name, json) } }
    fun updateProfileFull(profile: ProfileEntity) { viewModelScope.launch(Dispatchers.IO) { ensureInitialized(); repository.updateProfileFull(profile) } }
    fun deleteProfile(id: Long) { viewModelScope.launch(Dispatchers.IO) { ensureInitialized(); repository.deleteProfile(id) } }
    fun updateSubscription(id: Long, name: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ensureInitialized()
            repository.updateSubscription(id, name, url)
        }
    }
    fun connectOrDisconnect() = if (_connectionState.value != ConnectionState.DISCONNECTED) stopVpn(true) else startVpn()
    fun startVpnFromUi() = startVpn()
    fun importFromClipboard(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ensureInitialized()
            _importEvent.emit(ImportEvent.Loading)
            try {
                val app = getApplication<Application>()
                val settings = SettingsManager(app)
                val hwid = if (settings.isHwidEnabled) getHwid() else null
                val model = android.os.Build.MODEL
                val osVersion = android.os.Build.VERSION.RELEASE
                
                kotlinx.coroutines.withTimeout(10000L) {
                    when (val result = ClipboardParser.parse(app, text, hwid, model, osVersion)) {
                        is ClipboardParser.ParseResult.SingleProfile -> {
                            repository.insertProfile(result.profile)
                            _importEvent.emit(ImportEvent.Success(getApplication<Application>().getString(R.string.success_profile_added, result.profile.name)))
                        }
                        is ClipboardParser.ParseResult.Subscription -> {
                            repository.insertSubscriptionWithProfiles(result.subscription, result.profiles)
                            _importEvent.emit(ImportEvent.Success(getApplication<Application>().getString(R.string.success_subscription_added, result.subscription.name)))
                        }
                        is ClipboardParser.ParseResult.Error -> {
                            _importEvent.emit(ImportEvent.Error(result.message))
                        }
                        else -> {
                            _importEvent.emit(ImportEvent.Error(getApplication<Application>().getString(R.string.error_import_failed)))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _importEvent.emit(ImportEvent.Error(getApplication<Application>().getString(R.string.error_import_timeout)))
            } catch (e: Exception) {
                _importEvent.emit(ImportEvent.Error(getApplication<Application>().getString(R.string.error_import_failed)))
            }
        }
    }

    private fun startVpn() {
        viewModelScope.launch {
            ensureInitialized()
            val profile = repository.getSelectedProfile() ?: return@launch
            val app = getApplication<Application>()
            val settings = SettingsManager(app)
            val configWithSettings = patchMtu(profile.configJson, settings.mtu, settings.tunStack)

            val vpnIntent = VpnService.prepare(app)
            if (vpnIntent != null) { _importEvent.emit(ImportEvent.NeedPermission(vpnIntent)); return@launch }
            _connectionState.value = ConnectionState.CONNECTING
            val intent = Intent(app, FlareVpnService::class.java).apply {
                action = FlareVpnService.ACTION_START
                putExtra(FlareVpnService.EXTRA_CONFIG, configWithSettings)
                putExtra(FlareVpnService.EXTRA_PROFILE_NAME, profile.name)
            }
            if (settings.isStatusNotificationEnabled) {
                androidx.core.content.ContextCompat.startForegroundService(app, intent)
            } else {
                app.startService(intent)
            }
        }
    }

    private fun stopVpn(cancelRecovery: Boolean = false) {
        if (cancelRecovery) recoveryJob?.cancel()
        val app = getApplication<Application>()
        app.startService(Intent(app, FlareVpnService::class.java).apply { action = FlareVpnService.ACTION_STOP })
    }
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val baseTime = flare.client.app.singbox.SingBoxManager.startTime
            val start = if (baseTime > 0) baseTime else SystemClock.elapsedRealtime()
            while (true) {
                _connectionTimerText.value = formatDuration(SystemClock.elapsedRealtime() - start)
                delay(1000)
            }
        }
    }
    private fun stopTimer() { timerJob?.cancel(); timerJob = null; _connectionTimerText.value = "" }
    private fun formatDuration(ms: Long): String = String.format("%02d:%02d:%02d", ms/(3600000), (ms/60000)%60, (ms/1000)%60)

    private fun buildDisplayList(subs: List<SubscriptionEntity>, standalone: List<ProfileEntity>, profilesBySub: Map<Long?, List<ProfileEntity>>, expanded: Set<Long>, selId: Long?, pings: Map<Long, PingState>, refreshing: Set<Long>): List<DisplayItem> {
        val items = mutableListOf<DisplayItem>()
        subs.forEach { sub ->
            val subProfiles = profilesBySub[sub.id] ?: emptyList()
            val isExpanded = sub.id in expanded
            val isRefreshing = sub.id in refreshing
            items += DisplayItem.SubscriptionItem(sub, subProfiles, isExpanded, isRefreshing, if (isExpanded) DisplayItem.CornerType.TOP else DisplayItem.CornerType.ALL)
            if (isExpanded) subProfiles.forEachIndexed { i, p -> items += DisplayItem.ProfileItem(p, p.id == selId, pings[p.id] ?: PingState.None, if (i == subProfiles.size - 1) DisplayItem.CornerType.BOTTOM else DisplayItem.CornerType.NONE) }
        }
        if (standalone.isNotEmpty()) {
            val virtualSub = SubscriptionEntity(
                id = VIRTUAL_SUB_ID,
                name = getApplication<Application>().getString(R.string.sub_single_profiles),
                url = ""
            )
            val isExpanded = VIRTUAL_SUB_ID in expanded
            val isRefreshing = VIRTUAL_SUB_ID in refreshing
            items += DisplayItem.SubscriptionItem(virtualSub, standalone, isExpanded, isRefreshing, if (isExpanded) DisplayItem.CornerType.TOP else DisplayItem.CornerType.ALL)
            if (isExpanded) {
                standalone.forEachIndexed { i, p ->
                    items += DisplayItem.ProfileItem(p, p.id == selId, pings[p.id] ?: PingState.None, if (i == standalone.size - 1) DisplayItem.CornerType.BOTTOM else DisplayItem.CornerType.NONE)
                }
            }
        }
        return items
    }

    fun startAutoUpdateJob() {
        autoUpdateJob?.cancel()

        autoUpdateJob = viewModelScope.launch {
            ensureInitialized()
            val settings = SettingsManager(getApplication())
            if (!settings.isSubAutoUpdateEnabled) return@launch
            while (isActive) {
                val intervalRaw = settings.subAutoUpdateInterval.toLongOrNull() ?: 3600L
                val interval = if (intervalRaw < 30L) 30L else intervalRaw
                val lastUpdate = settings.lastSubUpdateTime
                val now = System.currentTimeMillis()
                val nextUpdate = lastUpdate + interval * 1000L
                val delayTime = nextUpdate - now
                if (delayTime > 0) {
                    delay(delayTime)
                }
                if (isActive) {
                    try {
                        refreshAllSubscriptions()
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Auto-update failed: ${e.message}")
                        
                        delay(60000L)
                    }
                }
            }
        }
    }

    fun startHealthCheckJob() {
        val app = getApplication<Application>()
        val settings = SettingsManager(app)
        healthCheckJob?.cancel()
        if (!settings.isAdaptiveTunnelEnabled) return

        healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            while (isActive) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    val url = settings.pingTestUrl
                    try {
                        val proxyTag = java.net.URLEncoder.encode(flare.client.app.singbox.SingBoxManager.primaryProxyTag, "UTF-8")
                        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
                        val checkUrl = "http://127.0.0.1:9092/proxies/$proxyTag/delay?url=$encodedUrl&timeout=5000"
                        
                        val request = okhttp3.Request.Builder().url(checkUrl).build()
                        var isWorking = false
                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                val delay = org.json.JSONObject(body).optInt("delay", -1)
                                if (delay > 0) {
                                    isWorking = true
                                }
                            }
                        }
                        
                        if (!isWorking) {
                            Log.w("MainViewModel", "Active Health Check failed: Proxy returned timeout or error")
                            startRecovery()
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Active Health Check failed: Could not reach Clash API", e)
                        startRecovery()
                    }
                }
                delay(20000L) 
            }
        }
    }

    fun stopHealthCheckJob() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    private fun startRecovery() {
        val app = getApplication<Application>()
        val settings = SettingsManager(app)
        if (!settings.isAdaptiveTunnelEnabled) return
        
        if (recoveryJob?.isActive == true) return
        
        recoveryJob = viewModelScope.launch {
            Log.i("MainViewModel", "Starting adaptive tunnel recovery...")
            val selectedId = _selectedProfileId.value ?: return@launch
            
            
            stopVpn()
            delay(1000)
            startVpn()
            
            
            val connectDeadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < connectDeadline) {
                if (_connectionState.value == ConnectionState.CONNECTED) break
                delay(500)
            }
            
            
            if (_connectionState.value == ConnectionState.CONNECTED) {
                delay(2000) 
                val okHttpClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val isWorking = withContext(Dispatchers.IO) {
                    try {
                        val proxyTag = java.net.URLEncoder.encode(flare.client.app.singbox.SingBoxManager.primaryProxyTag, "UTF-8")
                        val encodedUrl = java.net.URLEncoder.encode(settings.pingTestUrl, "UTF-8")
                        val checkUrl = "http://127.0.0.1:9092/proxies/$proxyTag/delay?url=$encodedUrl&timeout=5000"
                        
                        val request = okhttp3.Request.Builder().url(checkUrl).build()
                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                val delay = org.json.JSONObject(body).optInt("delay", -1)
                                delay > 0
                            } else {
                                false
                            }
                        }
                    } catch (e: Exception) {
                        false
                    }
                }
                if (isWorking) {
                    Log.i("MainViewModel", "Recovery successful with current profile.")
                    return@launch
                }
            }
            
            
            Log.i("MainViewModel", "Current profile failed during recovery. Finding best profile...")
            val allProfiles = repository.getAllProfiles().first()
            val currentProfile = allProfiles.find { it.id == selectedId } ?: return@launch
            val subId = currentProfile.subscriptionId ?: return@launch
            val profiles = allProfiles.filter { it.subscriptionId == subId }
            if (profiles.size <= 1) return@launch
            
            speedTestProfile(profiles)
            
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val pings = _pingStates.value
                val allDone = profiles.all { p ->
                    val state = pings[p.id]
                    state is PingState.Result
                }
                if (allDone) break
                delay(500)
            }
            
            val pings = _pingStates.value
            val best = profiles
                .mapNotNull { p ->
                    val state = pings[p.id]
                    if (state is PingState.Result && !state.isError && state.latency >= 0) {
                        p to state.latency
                    } else null
                }
                .minByOrNull { it.second }?.first
                
            if (best != null && best.id != selectedId) {
                Log.i("MainViewModel", "Switching to best profile: ${best.name}")
                selectProfile(best.id)
                
                
                val title = app.getString(R.string.notif_adaptive_tunnel_changed_title)
                val body = app.getString(R.string.notif_adaptive_tunnel_changed_body, best.name)
                flare.client.app.ui.notification.AppNotificationManager.showSystemNotification(app, title, body, isHighPriority = true)
            } else {
                Log.w("MainViewModel", "No working profile found during recovery.")
            }
        }
    }

    private var bestProfileJob: kotlinx.coroutines.Job? = null

    fun startBestProfileJob() {
        bestProfileJob?.cancel()

        bestProfileJob = viewModelScope.launch {
            ensureInitialized()
            val settings = SettingsManager(getApplication())
            if (!settings.isBestProfileEnabled) return@launch
            while (isActive) {
                val rawInterval = settings.bestProfileInterval.toLongOrNull() ?: 1800L
                val interval = if (rawInterval < 10L) 10L else rawInterval
                delay(interval * 1000L)
                val shouldRun = if (settings.isBestProfileOnlyIfConnected) {
                    _connectionState.value == ConnectionState.CONNECTED
                } else true
                if (shouldRun) {
                    selectBestProfile()
                }
            }
        }
    }

    private suspend fun selectBestProfile() {
        val settings = SettingsManager(getApplication())
        val selectedId = _selectedProfileId.value ?: return
        val allProfiles = repository.getAllProfiles().first()
        val selectedProfile = allProfiles.find { it.id == selectedId } ?: return
        val subId = selectedProfile.subscriptionId ?: return

        val profiles = allProfiles.filter { it.subscriptionId == subId }
        if (profiles.size <= 1) return

        speedTestProfile(profiles)

        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val pings = _pingStates.value
            val allDone = profiles.all { p ->
                val state = pings[p.id]
                state is PingState.Result
            }
            if (allDone) break
            delay(500)
        }

        val pings = _pingStates.value
        val bestPair = profiles
            .mapNotNull { p ->
                val state = pings[p.id]
                if (state is PingState.Result && !state.isError && state.latency >= 0) {
                    p to state.latency
                } else null
            }
            .minByOrNull { it.second }

        val best = bestPair?.first
        val latency = bestPair?.second

        if (best != null) {
            if (best.id != _selectedProfileId.value) {
                selectProfile(best.id)
            }
            if (settings.isBestProfileNotificationEnabled) {
                val app = getApplication<Application>()
                val title = app.getString(R.string.notif_best_profile_title)
                val body = app.getString(R.string.notif_best_profile_body, best.name, latency)
                flare.client.app.ui.notification.AppNotificationManager.showSystemNotification(app, title, body)
            }
        }
    }

    suspend fun refreshAllSubscriptions() = withContext(Dispatchers.IO) {
        val subs = repository.getAllSubscriptions().first()
        if (subs.isEmpty()) return@withContext
        var successCount = 0
        val selectedBefore = repository.getSelectedProfile()
        val app = getApplication<Application>()
        val settings = SettingsManager(app)
        val hwid = if (settings.isHwidEnabled) getHwid() else null
        val model = android.os.Build.MODEL
        val osVersion = android.os.Build.VERSION.RELEASE
        
        coroutineScope {
            val deferreds = subs.map { sub ->
                async {
                    try {
                        _refreshingSubs.update { it + sub.id }
                        val result = withTimeoutOrNull(10000L) {
                            ClipboardParser.parse(app, sub.url, hwid, model, osVersion)
                        }
                        if (result is ClipboardParser.ParseResult.Subscription) {
                            repository.replaceSubscriptionProfiles(sub.id, result.profiles)
                            repository.updateSubscription(result.subscription.copy(id = sub.id))
                            true
                        } else false
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to refresh ${sub.name}", e)
                        false
                    } finally {
                        _refreshingSubs.update { it - sub.id }
                    }
                }
            }
            val results = deferreds.awaitAll()
            successCount = results.count { it }
        }
        if (selectedBefore != null) {
            val allAfter = repository.getAllProfiles().first()
            val restored = allAfter.find {
                it.uri == selectedBefore.uri &&
                it.name == selectedBefore.name &&
                it.subscriptionId == selectedBefore.subscriptionId
            }
            if (restored != null) {
                repository.selectProfile(restored.id)
                _selectedProfileId.value = restored.id
            } else {
                _selectedProfileId.value = null
            }
        }
        if (successCount > 0) {
            settings.lastSubUpdateTime = System.currentTimeMillis()
            flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.SUCCESS,
                app.getString(R.string.sub_update_success, successCount),
                4
            )
        } else {
            flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.ERROR,
                app.getString(R.string.sub_update_error),
                4
            )
            
            delay(60000L)
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _refreshingSubs.update { it + sub.id }
            val app = getApplication<Application>()
            val settings = SettingsManager(app)
            val hwid = if (settings.isHwidEnabled) getHwid() else null
            val model = android.os.Build.MODEL
            val osVersion = android.os.Build.VERSION.RELEASE
            try {
                val selectedBefore = repository.getSelectedProfile()
                val result = withTimeoutOrNull(10000L) {
                    ClipboardParser.parse(app, sub.url, hwid, model, osVersion)
                }
                if (result is ClipboardParser.ParseResult.Subscription) {
                    repository.replaceSubscriptionProfiles(sub.id, result.profiles)
                    repository.updateSubscription(result.subscription.copy(id = sub.id))
                    if (selectedBefore != null) {
                        if (selectedBefore.subscriptionId == sub.id) {
                            val allAfter = repository.getAllProfiles().first()
                            val restored = allAfter.find {
                                it.uri == selectedBefore.uri &&
                                it.name == selectedBefore.name &&
                                it.subscriptionId == sub.id
                            }
                            if (restored != null) {
                                repository.selectProfile(restored.id)
                                _selectedProfileId.value = restored.id
                            } else {
                                _selectedProfileId.value = null
                            }
                        } else {
                            _selectedProfileId.value = selectedBefore.id
                        }
                    }
                    flare.client.app.ui.notification.AppNotificationManager.showNotification(
                        flare.client.app.ui.notification.NotificationType.SUCCESS,
                        app.getString(R.string.sub_update_success_single, sub.name),
                        3
                    )
                } else {
                    flare.client.app.ui.notification.AppNotificationManager.showNotification(
                        flare.client.app.ui.notification.NotificationType.ERROR,
                        app.getString(R.string.sub_update_error_single),
                        3
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to refresh ${sub.name}", e)
                flare.client.app.ui.notification.AppNotificationManager.showNotification(
                    flare.client.app.ui.notification.NotificationType.ERROR,
                    app.getString(R.string.sub_update_error_single),
                    3
                )
            } finally {
                _refreshingSubs.update { it - sub.id }
            }
        }
    }

    fun addPrivateServer(uri: String, name: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val subName = app.getString(R.string.sub_my_servers)
            val allSubs = repository.getAllSubscriptions().first()
            var sub = allSubs.find { it.name == subName }
            if (sub == null) {
                val newSub = SubscriptionEntity(
                    name = subName,
                    url = "",
                    total = Long.MAX_VALUE
                )
                val id = repository.insertSubscription(newSub)
                sub = newSub.copy(id = id)
            }
            
            val profile = ClipboardParser.buildProfileFromUri(app, uri, sub.id).copy(name = name)
            repository.insertProfile(profile)
            
            val allProfiles = repository.getAllProfiles().first()
            val savedProfile = allProfiles.find { it.uri == uri && it.subscriptionId == sub.id }
            if (savedProfile != null) {
                selectProfile(savedProfile.id)
            }
        }
    }

    private fun patchMtu(json: String, newMtu: String, tunStack: String): String {
        return try {
            val obj = JSONObject(json)
            val inbounds = obj.optJSONArray("inbounds")
            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.optJSONObject(i)
                    if (inbound?.optString("type") == "tun") {
                        inbound.put("mtu", newMtu.toIntOrNull() ?: 1500)
                        inbound.put("stack", tunStack)
                    }
                }
            }
            obj.toString().replace("\\/", "/")
        } catch (e: Exception) {
            json
        }
    }

    private var updateCheckJob: kotlinx.coroutines.Job? = null

    fun startUpdateCheckJob() {
        val app = getApplication<Application>()
        updateCheckJob?.cancel()

        updateCheckJob = viewModelScope.launch(Dispatchers.IO) {
            ensureInitialized()
            val settings = SettingsManager(app)
            if (!settings.isUpdateCheckEnabled) return@launch
            while (isActive) {
                val intervalMs = when (settings.updateCheckFrequency) {
                    "daily" -> 24 * 3600 * 1000L
                    "weekly" -> 7 * 24 * 3600 * 1000L
                    "monthly" -> 30 * 24 * 3600 * 1000L
                    else -> 24 * 3600 * 1000L
                }
                val lastCheck = settings.lastUpdateCheckTime
                val now = System.currentTimeMillis()

                val delayTime = if (lastCheck == 0L) {
                    
                    (1L + (Math.random() * 59).toLong()) * 1000L
                } else {
                    (lastCheck + intervalMs) - now
                }

                if (delayTime > 0) {
                    delay(delayTime)
                }

                if (isActive) {
                    flare.client.app.util.VersionManager.checkUpdates(app)
                    settings.lastUpdateCheckTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun getHwid(): String {
        return android.provider.Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown_hwid"
    }
}
