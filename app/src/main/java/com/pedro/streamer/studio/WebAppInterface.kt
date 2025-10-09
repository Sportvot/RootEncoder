package com.pedro.streamer.studio

import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pedro.streamer.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WebAppInterface(private val context: StudioActivity) {
    @JavascriptInterface
    fun postMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val data = json.optJSONObject("data")

            if(type == "START_STREAM" && data != null) {

                // If data contains a URL (connectionString), use DeepLinkParams to parse matchId, else fallback to direct extraction
                val url = data.optString("connectionString", null.toString())
                val uri = url.toUri()
                val params = DeepLinkParams.fromUri(uri)
                val matchId = params.matchId
                val refreshId = params.refreshId
                val refreshToken = params.refreshToken

                val intent = Intent(context, com.pedro.streamer.MainActivity::class.java)
                if (matchId != null) {
                    intent.putExtra(StudioConstants.MATCH_ID_KEY, matchId)
                    intent.putExtra(StudioConstants.REFRESH_ID_KEY, refreshId)
                    intent.putExtra(StudioConstants.REFRESH_TOKEN_KEY, refreshToken)
                    CoroutineScope(Dispatchers.IO).launch {
                        context.applicationContext.dataStore.edit { prefs ->
                            params.resolution?.let { prefs[stringPreferencesKey("video_resolution_key")] = it }
                            params.fps?.let { prefs[stringPreferencesKey("video_fps_key")] = it }
                            params.ip?.let { prefs[stringPreferencesKey("srt_server_ip_key")] = it }
                            params.port?.let { prefs[stringPreferencesKey("srt_server_port_key")] = it }
                            params.srtStreamId?.let { prefs[stringPreferencesKey("server_stream_id_key")] = it }
                            params.bitrate?.let { prefs[intPreferencesKey("live_video_bitrate_key")] = it }
                        }
                    }
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Invalid message: $message", e)
        }
    }
} 