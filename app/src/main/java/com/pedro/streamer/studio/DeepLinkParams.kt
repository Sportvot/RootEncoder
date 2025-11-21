package com.pedro.streamer.studio

import android.net.Uri

/**
 * Data class representing parsed deep link parameters for streaming.
 */
data class DeepLinkParams(
    val matchId: String? = null,
    val resolution: String? = null,
    val fps: String? = null,
    val srtStreamId: String? = null,
    val bitrate: Int? = null,
    val ip: String? = null,
    val port: String? = null,
    val refreshId: String? = null,
    val refreshToken: String? = null
) {
    companion object {
        fun fromUri(uri: Uri?): DeepLinkParams {
            if (uri == null) return DeepLinkParams()
            val query = uri.query
            val params = mutableMapOf<String, MutableList<String>>()

            query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                val key = Uri.decode(parts[0])
                val value = if (parts.size > 1) Uri.decode(parts[1]) else ""
                params.getOrPut(key) { mutableListOf() }.add(value)
            }

            val matchId = params["streamId"]?.firstOrNull()
            val refreshId = params["refreshId"]?.firstOrNull()
            val refreshToken = params["refreshToken"]?.firstOrNull()
            val resolution = params["enc[vid][res]"]?.firstOrNull()
            val fps = params["enc[vid][fps]"]?.firstOrNull()
            val srtStreamId = params["conn[][srtstreamid]"]?.firstOrNull()
            val bitrate = params["enc[vid][bitrate]"]?.firstOrNull()?.toIntOrNull()
            val connUrl = params["conn[][url]"]?.firstOrNull()
            var ip: String? = null
            var port: String? = null
            connUrl?.let { url ->
                val regex = Regex("""srt://([\w.]+):(\d+)""")
                val match = regex.find(url)
                if (match != null && match.groupValues.size >= 3) {
                    ip = match.groupValues[1]
                    port = match.groupValues[2]
                }
            }

            return DeepLinkParams(
                matchId = matchId,
                resolution = resolution,
                fps = fps,
                srtStreamId = srtStreamId,
                bitrate = bitrate,
                ip = ip,
                port = port,
                refreshId = refreshId,
                refreshToken = refreshToken
            )
        }
    }
} 