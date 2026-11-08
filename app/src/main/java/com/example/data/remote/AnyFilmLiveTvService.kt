package com.example.data.remote

import com.example.data.model.LiveTvChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Service that fetches AnyFilm's working Live TV resources:
 * - SonyLIV auto-updated direct CDN playlist
 * - JioTV live channels playlist
 * - FanCode sports playlist
 */
class AnyFilmLiveTvService(private val client: OkHttpClient) {

    companion object {
        private const val SONY_M3U_URL = "https://raw.githubusercontent.com/sportlive18/Sonyliv-Playlist-Autoupdate/main/sony.m3u"
        private const val JIO_M3U_URL = "https://raw.githubusercontent.com/sportlive18/sportlink-update/main/zio.m3u"
        private const val FANCODE_M3U_URL = "https://raw.githubusercontent.com/sayanpal514-hue/FANCODE-AUTO-UPDATED-PLAYLIST/main/fancode.m3u"
    }

    private var cachedChannels = ConcurrentHashMap<String, List<LiveTvChannel>>()
    private var lastFetchTime: Long = 0L
    private val CACHE_TTL_MS = 300_000L // 5 minutes

    suspend fun getChannels(forceRefresh: Boolean = false): List<LiveTvChannel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastFetchTime) < CACHE_TTL_MS && cachedChannels.containsKey("all")) {
            return@withContext cachedChannels["all"].orEmpty()
        }

        val allChannels = mutableListOf<LiveTvChannel>()

        // 1. Fetch SonyLIV channels
        try {
            val req = Request.Builder().url(SONY_M3U_URL).header("User-Agent", "Mozilla/5.0").build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val content = resp.body?.string().orEmpty()
                allChannels.addAll(parseM3u(content, defaultCategory = "SonyLIV"))
            }
        } catch (_: Exception) {}

        // 2. Fetch FanCode sports
        try {
            val req = Request.Builder().url(FANCODE_M3U_URL).header("User-Agent", "Mozilla/5.0").build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val content = resp.body?.string().orEmpty()
                allChannels.addAll(parseM3u(content, defaultCategory = "FanCode Sports"))
            }
        } catch (_: Exception) {}

        // 3. Fetch JioTV channels if needed
        if (allChannels.size < 15) {
            try {
                val req = Request.Builder().url(JIO_M3U_URL).header("User-Agent", "Mozilla/5.0").build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val content = resp.body?.string().orEmpty()
                    allChannels.addAll(parseM3u(content, defaultCategory = "JioTV Live"))
                }
            } catch (_: Exception) {}
        }

        val distinct = allChannels.distinctBy { it.name }
        cachedChannels["all"] = distinct
        lastFetchTime = now
        distinct
    }

    private fun parseM3u(m3uContent: String, defaultCategory: String): List<LiveTvChannel> {
        val channels = mutableListOf<LiveTvChannel>()
        val lines = m3uContent.lines()
        var currentName: String? = null
        var currentCategory: String = defaultCategory
        var currentLogo: String? = null
        var currentId: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                // Extract group-title
                val groupMatch = Regex("""group-title="([^"]+)"""").find(trimmed)
                currentCategory = groupMatch?.groupValues?.get(1)?.ifBlank { defaultCategory } ?: defaultCategory

                // Extract tvg-logo
                val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(trimmed)
                currentLogo = logoMatch?.groupValues?.get(1)

                // Extract tvg-id
                val idMatch = Regex("""tvg-id="([^"]+)"""").find(trimmed)
                currentId = idMatch?.groupValues?.get(1)

                // Extract Channel Name (after last comma)
                val commaIdx = trimmed.lastIndexOf(',')
                currentName = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                    trimmed.substring(commaIdx + 1).trim()
                } else {
                    currentId ?: "Live Channel"
                }
            } else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val name = currentName ?: "Live Channel"
                val id = currentId ?: name.replace(" ", "_").lowercase()
                channels.add(
                    LiveTvChannel(
                        id = id,
                        name = name,
                        category = currentCategory,
                        logo = currentLogo,
                        streamUrl = trimmed
                    )
                )
                // Reset
                currentName = null
                currentCategory = defaultCategory
                currentLogo = null
                currentId = null
            }
        }
        return channels
    }
}

