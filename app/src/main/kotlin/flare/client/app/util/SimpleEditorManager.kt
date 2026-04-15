package flare.client.app.util

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import flare.client.app.R
import flare.client.app.data.model.ProfileEntity
import flare.client.app.data.parser.ClipboardParser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class SimpleEditorManager(
    private val view: View,
    private val onSave: (ProfileEntity) -> Unit,
    private val onClose: () -> Unit
) {
    private val etTag: EditText = view.findViewById(R.id.et_simple_tag)
    private val etServer: EditText = view.findViewById(R.id.et_simple_server)
    private val etPort: EditText = view.findViewById(R.id.et_simple_port)
    private val etUuid: EditText = view.findViewById(R.id.et_simple_uuid)
    private val tvUuidLabel: TextView = view.findViewById(R.id.tv_simple_uuid_label)
    private val tvFlow: TextView = view.findViewById(R.id.tv_simple_flow)
    private val layoutFlow: View = view.findViewById(R.id.layout_simple_flow)
    private val viewFlowDivider: View = view.findViewById(R.id.view_flow_divider)
    private val tvMethod: TextView = view.findViewById(R.id.tv_simple_method)
    private val layoutMethod: View = view.findViewById(R.id.layout_simple_method)
    private val viewMethodDivider: View = view.findViewById(R.id.view_method_divider)

    private val swTls: SwitchCompat = view.findViewById(R.id.sw_simple_tls)
    private val layoutTlsInner: View = view.findViewById(R.id.layout_tls_inner)
    private val layoutTlsContainer: View = view.findViewById(R.id.layout_tls_container)
    private val etSni: EditText = view.findViewById(R.id.et_simple_sni)
    private val etAlpn: EditText = view.findViewById(R.id.et_simple_alpn)
    private val tvFingerprint: TextView = view.findViewById(R.id.tv_simple_fingerprint)
    private val layoutFingerprint: View = view.findViewById(R.id.layout_simple_fingerprint)

    private val layoutRealityContainer: View = view.findViewById(R.id.layout_reality_container)
    private val etPbk: EditText = view.findViewById(R.id.et_simple_pbk)
    private val etSid: EditText = view.findViewById(R.id.et_simple_sid)
    
    private val btnBack: View = view.findViewById(R.id.btn_simple_back)
    private val btnSave: View = view.findViewById(R.id.btn_simple_save)

    private var currentProfile: ProfileEntity? = null
    private var currentScheme = "vless"

    init {
        btnBack.setOnClickListener { onClose() }
        btnSave.setOnClickListener { saveAndClose() }

        swTls.setOnCheckedChangeListener { _, isChecked ->
            layoutTlsInner.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateRealityVisibility()
        }

        layoutFlow.setOnClickListener {
            val options = listOf(
                "xtls-rprx-vision",
                "xtls-rprx-vision-udp443",
                ""
            )
            val menuItems = options.mapIndexed { index, opt ->
                GlassUtils.MenuItem(index, if (opt.isEmpty()) "None" else opt) {
                    tvFlow.text = opt
                }
            }
            GlassUtils.showGlassMenu(layoutFlow, menuItems)
        }

        layoutFingerprint.setOnClickListener {
            val options = listOf("chrome", "firefox", "safari", "edge", "ios", "android", "random", "randomized")
            val menuItems = options.mapIndexed { index, opt ->
                GlassUtils.MenuItem(index, opt) {
                    tvFingerprint.text = opt
                }
            }
            GlassUtils.showGlassMenu(layoutFingerprint, menuItems)
        }

        layoutMethod.setOnClickListener {
            val options = listOf("aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")
            val menuItems = options.mapIndexed { index, opt ->
                GlassUtils.MenuItem(index, opt) {
                    tvMethod.text = opt
                }
            }
            GlassUtils.showGlassMenu(layoutMethod, menuItems)
        }
    }

    private fun updateRealityVisibility() {
        val isTls = swTls.isChecked
        val isRealitySupported = currentScheme == "vless" || currentScheme == "trojan"

        if (isTls && isRealitySupported && etPbk.text.isNotEmpty()) {
            layoutRealityContainer.visibility = View.VISIBLE
        } else if (isTls && isRealitySupported) {
            layoutRealityContainer.visibility = View.VISIBLE
        } else {
            layoutRealityContainer.visibility = View.GONE
        }
    }

    fun bind(profile: ProfileEntity) {
        currentProfile = profile
        try {
            val uri = URI(profile.uri)
            currentScheme = uri.scheme ?: "vless"
            val queryParams = parseQuery(uri.rawQuery)

            etTag.setText(profile.name)
            etServer.setText(uri.host ?: "")
            etPort.setText(if (uri.port > 0) uri.port.toString() else "")

            when (currentScheme) {
                "vless", "trojan" -> {
                    tvUuidLabel.text = if (currentScheme == "vless") view.context.getString(R.string.label_uuid) else view.context.getString(R.string.label_password)
                    etUuid.setText(uri.userInfo ?: "")
                    
                    layoutFlow.visibility = if (currentScheme == "vless") View.VISIBLE else View.GONE
                    viewFlowDivider.visibility = if (currentScheme == "vless") View.VISIBLE else View.GONE
                    tvFlow.text = queryParams["flow"] ?: ""

                    layoutMethod.visibility = View.GONE
                    viewMethodDivider.visibility = View.GONE
                    
                    layoutTlsContainer.visibility = View.VISIBLE
                    val sec = queryParams["security"] ?: "none"
                    swTls.isChecked = sec == "tls" || sec == "reality"
                    etSni.setText(queryParams["sni"] ?: uri.host ?: "")
                    etAlpn.setText(queryParams["alpn"] ?: "")
                    tvFingerprint.text = queryParams["fp"] ?: "chrome"

                    if (sec == "reality" || queryParams.containsKey("pbk")) {
                        etPbk.setText(queryParams["pbk"] ?: "")
                        etSid.setText(queryParams["sid"] ?: "")
                    } else {
                        etPbk.setText("")
                        etSid.setText("")
                    }
                }
                "vmess" -> {
                    tvUuidLabel.text = view.context.getString(R.string.label_uuid)
                    val b64 = profile.uri.removePrefix("vmess://").trim()
                    try {
                        val json = org.json.JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)))
                        etTag.setText(profile.name)
                        etServer.setText(json.optString("add"))
                        etPort.setText(json.optString("port"))
                        etUuid.setText(json.optString("id"))
                        etSni.setText(json.optString("sni"))
                        etAlpn.setText(json.optString("alpn"))
                        swTls.isChecked = json.optString("tls") == "tls"
                    } catch (_: Exception) {}
                    
                    layoutFlow.visibility = View.GONE
                    viewFlowDivider.visibility = View.GONE
                    layoutMethod.visibility = View.GONE
                    viewMethodDivider.visibility = View.GONE
                    layoutTlsContainer.visibility = View.VISIBLE
                    layoutRealityContainer.visibility = View.GONE
                }
                "ss", "shadowsocks" -> {
                    tvUuidLabel.text = view.context.getString(R.string.label_password)
                    layoutFlow.visibility = View.GONE
                    viewFlowDivider.visibility = View.GONE
                    layoutMethod.visibility = View.VISIBLE
                    viewMethodDivider.visibility = View.VISIBLE
                    layoutTlsContainer.visibility = View.GONE
                    layoutRealityContainer.visibility = View.GONE

                    val userInfo = try { String(android.util.Base64.decode(uri.userInfo ?: "", android.util.Base64.DEFAULT)) } catch (_: Exception) { uri.userInfo ?: ":" }
                    tvMethod.text = userInfo.substringBefore(":")
                    etUuid.setText(userInfo.substringAfter(":"))
                }
                "hysteria2", "hy2" -> {
                    tvUuidLabel.text = view.context.getString(R.string.label_password)
                    etUuid.setText(uri.userInfo ?: "")
                    layoutFlow.visibility = View.GONE
                    viewFlowDivider.visibility = View.GONE
                    layoutMethod.visibility = View.GONE
                    viewMethodDivider.visibility = View.GONE
                    
                    layoutTlsContainer.visibility = View.VISIBLE
                    swTls.isChecked = true
                    swTls.isEnabled = false 
                    
                    etSni.setText(queryParams["sni"] ?: uri.host ?: "")
                    etAlpn.setText("") 
                    layoutFingerprint.visibility = View.GONE 
                    layoutRealityContainer.visibility = View.GONE
                }
                else -> {
                    
                    tvUuidLabel.text = view.context.getString(R.string.label_credentials)
                    etUuid.setText(uri.userInfo ?: "")
                    layoutFlow.visibility = View.GONE
                    viewFlowDivider.visibility = View.GONE
                    layoutMethod.visibility = View.GONE
                    viewMethodDivider.visibility = View.GONE
                }
            }

            layoutTlsInner.visibility = if (swTls.isChecked) View.VISIBLE else View.GONE
            updateRealityVisibility()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveAndClose() {
        val original = currentProfile ?: return
        try {
            val name = etTag.text.toString().trim()
            val host = etServer.text.toString().trim()
            val portText = etPort.text.toString().trim()
            val cred = etUuid.text.toString().trim()
            
            val newUri = when (currentScheme) {
                "vless", "trojan" -> {
                    val portStr = if (portText.isNotEmpty()) ":$portText" else ""
                    val query = mutableListOf<String>()
                    val parsed = URI(original.uri)
                    val originalParams = parseQuery(parsed.rawQuery)
                    query.add("type=" + (originalParams["type"] ?: "tcp"))
                    
                    if (currentScheme == "vless" && tvFlow.text.isNotEmpty()) {
                        query.add("flow=${encode(tvFlow.text.toString())}")
                    }

                    if (swTls.isChecked) {
                        val pbk = etPbk.text.toString().trim()
                        if (pbk.isNotEmpty()) {
                            query.add("security=reality")
                            query.add("pbk=${encode(pbk)}")
                            val sid = etSid.text.toString().trim()
                            if (sid.isNotEmpty()) query.add("sid=${encode(sid)}")
                        } else {
                            query.add("security=tls")
                        }
                        
                        val sni = etSni.text.toString().trim()
                        if (sni.isNotEmpty()) query.add("sni=${encode(sni)}")
                        
                        val alpn = etAlpn.text.toString().trim()
                        if (alpn.isNotEmpty()) query.add("alpn=${encode(alpn)}")
                        
                        val fp = tvFingerprint.text.toString().trim()
                        if (fp.isNotEmpty()) query.add("fp=${encode(fp)}")
                    } else {
                        query.add("security=none")
                    }

                    "$currentScheme://$cred@$host$portStr?${query.joinToString("&")}#${encode(name)}"
                }
                "vmess" -> {
                    val b64 = original.uri.removePrefix("vmess://").trim()
                    val json = try { org.json.JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))) } catch (_: Exception) { org.json.JSONObject() }
                    json.put("ps", name)
                    json.put("add", host)
                    json.put("port", portText.toIntOrNull() ?: 443)
                    json.put("id", cred)
                    if (swTls.isChecked) {
                        json.put("tls", "tls")
                        json.put("sni", etSni.text.toString().trim())
                        json.put("alpn", etAlpn.text.toString().trim())
                    } else {
                        json.put("tls", "")
                    }
                    val newB64 = android.util.Base64.encodeToString(json.toString().toByteArray(), android.util.Base64.NO_WRAP)
                    "vmess://$newB64"
                }
                "ss", "shadowsocks" -> {
                    val portStr = if (portText.isNotEmpty()) ":$portText" else ""
                    val method = tvMethod.text.toString()
                    val auth = android.util.Base64.encodeToString("$method:$cred".toByteArray(), android.util.Base64.NO_WRAP)
                    "ss://$auth@$host$portStr#${encode(name)}"
                }
                "hysteria2", "hy2" -> {
                    val portStr = if (portText.isNotEmpty()) ":$portText" else ""
                    val sni = etSni.text.toString().trim()
                    val params = if (sni.isNotEmpty()) "?sni=${encode(sni)}" else ""
                    "$currentScheme://$cred@$host$portStr$params#${encode(name)}"
                }
                else -> original.uri
            }

            val updatedProfile = ClipboardParser.buildProfileFromUri(view.context, newUri, original.subscriptionId)
            onSave(updatedProfile.copy(id = original.id, name = name))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseQuery(query: String?): Map<String, String> = query?.split("&")?.associate {
        val parts = it.split("=", limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    } ?: emptyMap()
}
