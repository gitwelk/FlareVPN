package flare.client.app.util

import android.util.Base64
import org.json.JSONObject
import flare.client.app.data.model.ProfileEntity
import java.net.URLEncoder

object ProfileExportHelper {
    fun exportLink(profile: ProfileEntity): String? {
        if (!profile.uri.startsWith("internal://json")) {
            return profile.uri
        }
        
        try {
            val config = JSONObject(profile.configJson)
            val outbounds = config.optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val type = outbound.optString("type")
                if (type == "vless") {
                    return generateVlessLink(outbound, profile.name)
                } else if (type == "vmess") {
                    return generateVmessLink(outbound, profile.name)
                } else if (type == "trojan") {
                    return generateTrojanLink(outbound, profile.name)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun generateVlessLink(ob: JSONObject, name: String): String {
        val server = ob.optString("server")
        val port = ob.optInt("server_port")
        val uuid = ob.optString("uuid")
        val sb = StringBuilder("vless://$uuid@$server:$port?")

        val params = mutableMapOf<String, String>()
        val flow = ob.optString("flow")
        if (flow.isNotEmpty()) params["flow"] = flow

        val tls = ob.optJSONObject("tls")
        if (tls != null && tls.optBoolean("enabled", false)) {
            val reality = tls.optJSONObject("reality")
            if (reality != null && reality.optBoolean("enabled", true)) {
                params["security"] = "reality"
                val sni = reality.optString("server_name")
                params["sni"] = sni.ifEmpty { tls.optString("server_name") }
                params["pbk"] = reality.optString("public_key")
                val sid = reality.optString("short_id")
                if (sid.isNotEmpty()) params["sid"] = sid
            } else {
                params["security"] = "tls"
                params["sni"] = tls.optString("server_name")
            }
            val utls = tls.optJSONObject("utls")
            if (utls != null) {
                params["fp"] = utls.optString("fingerprint", "chrome")
            } else {
                params["fp"] = "chrome"
            }
        } else {
            params["security"] = "none"
        }

        val transport = ob.optJSONObject("transport")
        if (transport != null) {
            val type = transport.optString("type")
            params["type"] = type
            if (type == "ws") {
                params["path"] = transport.optString("path", "/")
                transport.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotEmpty() }?.let {
                    params["host"] = it
                }
            } else if (type == "grpc") {
                params["serviceName"] = transport.optString("service_name")
            }
        } else {
            params["type"] = "tcp"
        }

        val paramString = params.entries.joinToString("&") { 
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" 
        }
        sb.append(paramString)
        if (name.isNotEmpty()) {
            sb.append("#${URLEncoder.encode(name, "UTF-8")}")
        }
        return sb.toString()
    }
    
    private fun generateVmessLink(ob: JSONObject, name: String): String {
        val json = JSONObject()
        json.put("v", "2")
        json.put("ps", name)
        json.put("add", ob.optString("server"))
        json.put("port", ob.optInt("server_port"))
        json.put("id", ob.optString("uuid"))
        json.put("aid", 0)
        
        val tls = ob.optJSONObject("tls")
        if (tls != null && tls.optBoolean("enabled", false)) {
            json.put("tls", "tls")
            json.put("sni", tls.optString("server_name"))
        } else {
            json.put("tls", "")
        }
        
        val transport = ob.optJSONObject("transport")
        if (transport != null) {
            json.put("net", transport.optString("type"))
            if (transport.optString("type") == "ws") {
                json.put("path", transport.optString("path", "/"))
                json.put("host", transport.optJSONObject("headers")?.optString("Host", "") ?: "")
            }
        } else {
            json.put("net", "tcp")
        }
        
        json.put("type", "none")
        val base64 = Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        return "vmess://$base64"
    }

    private fun generateTrojanLink(ob: JSONObject, name: String): String {
        val server = ob.optString("server")
        val port = ob.optInt("server_port")
        val pw = ob.optString("password")
        val sb = StringBuilder("trojan://${URLEncoder.encode(pw, "UTF-8")}@$server:$port?")

        val params = mutableMapOf<String, String>()

        val tls = ob.optJSONObject("tls")
        if (tls != null && tls.optBoolean("enabled", false)) {
            params["security"] = "tls"
            params["sni"] = tls.optString("server_name")
        } else {
            params["security"] = "none"
        }

        val transport = ob.optJSONObject("transport")
        if (transport != null) {
            val type = transport.optString("type")
            params["type"] = type
            if (type == "ws") {
                params["path"] = transport.optString("path", "/")
                transport.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotEmpty() }?.let {
                    params["host"] = it
                }
            } else if (type == "grpc") {
                params["serviceName"] = transport.optString("service_name")
            }
        } else {
            params["type"] = "tcp"
        }

        val paramString = params.entries.joinToString("&") { 
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" 
        }
        sb.append(paramString)
        if (name.isNotEmpty()) {
            sb.append("#${URLEncoder.encode(name, "UTF-8")}")
        }
        return sb.toString()
    }
}
