package com.example.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.data.model.*
import com.example.util.OpenSubtitlesHelper
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Native, energy-efficient implementation of the AnyFilm provider.
 * Connects directly to the MovieBox Pro Mobile BFF API (api.inmoviebox.com)
 * using AnyFilm's HMAC-MD5 cryptographic signature, visitor authentication,
 * and UGC fallback engine (enabling unreleased / mobile-exclusive titles like
 * Dhurandhar: The Revenge, Lupin, and The Odyssey).
 *
 * Runs completely in pure Kotlin in-process without any local socket server
 * or external Python backend.
 */
class AnyFilmDirectService(
    private val client: OkHttpClient,
    private val moshi: Moshi
) : MovieApiService {

    companion object {
        private const val TAG = "AnyFilmDirect"
        private const val BASE_URL = "https://api.inmoviebox.com"
        private const val APP_ID = "4U01pxRu278GqCZKY9"
        private const val SIGN_KEY_STR = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
        private const val PREFS_NAME = "zippy_anyfilm_prefs"
        private const val KEY_VISITOR_TOKEN = "cached_visitor_token"
        private const val STREAM_CACHE_TTL_MS = 30_000L // 30 seconds burst memoization

        private val JSON_MEDIA_TYPE = "application/json;charset=UTF-8".toMediaType()

        private val SIGNING_KEY_BYTES: ByteArray by lazy {
            try {
                Base64.decode(SIGN_KEY_STR, Base64.DEFAULT)
            } catch (_: Exception) {
                SIGN_KEY_STR.toByteArray(Charsets.UTF_8)
            }
        }

        fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun md5Bytes(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun hmacMd5(keyBytes: ByteArray, dataBytes: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacMD5")
            val secretKey = SecretKeySpec(keyBytes, "HmacMD5")
            mac.init(secretKey)
            return mac.doFinal(dataBytes)
        }
    }

    private var sharedPrefs: SharedPreferences? = null
    private val tokenMutex = Mutex()

    @Volatile
    private var inMemoryToken: String? = null

    // Stream burst cache: "subjectId:detailPath:se:ep" -> (StreamResponse, expiryTimeMs)
    private val streamCache = ConcurrentHashMap<String, Pair<StreamResponse, Long>>()

    fun init(cacheDir: File?, context: Context?) {
        if (context != null) {
            try {
                sharedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not initialize SharedPreferences: ${e.message}")
            }
        }
    }

    private fun getCachedToken(): String? {
        inMemoryToken?.let { return it }
        val saved = sharedPrefs?.getString(KEY_VISITOR_TOKEN, null)
        if (!saved.isNullOrBlank()) {
            inMemoryToken = saved
            return saved
        }
        return null
    }

    private fun persistToken(token: String) {
        inMemoryToken = token
        sharedPrefs?.edit()?.putString(KEY_VISITOR_TOKEN, token)?.apply()
    }

    private suspend fun getOrBootstrapToken(): String = withContext(Dispatchers.IO) {
        getCachedToken()?.let { return@withContext it }
        tokenMutex.withLock {
            getCachedToken()?.let { return@withLock it }
            val freshToken = bootstrapVisitorToken()
            persistToken(freshToken)
            freshToken
        }
    }

    private suspend fun bootstrapVisitorToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis().toString()
        val reversed = now.reversed()
        val clientToken = "$now,${md5(reversed)}"
        val canonicalPath = "/wefeed-mobile-bff/user-api/visitor-login"
        val bodyString = "{}"
        val bodyBytes = bodyString.toByteArray(Charsets.UTF_8)
        val bodyHash = md5Bytes(bodyBytes)
        val bodyLen = bodyBytes.size.toString()

        val canonical = "POST\napplication/json\napplication/json;charset=UTF-8\n$bodyLen\n$now\n$bodyHash\n$canonicalPath"
        val sigDigest = hmacMd5(SIGNING_KEY_BYTES, canonical.toByteArray(Charsets.UTF_8))
        val sigHeader = "$now|2|${Base64.encodeToString(sigDigest, Base64.NO_WRAP)}"

        val clientInfoJson = getClientInfoJson()
        val request = Request.Builder()
            .url("$BASE_URL$canonicalPath")
            .header("User-Agent", "MovieBox/4.0.02 (Android 14; Pixel 6)")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("X-Sign-Version", "2.0")
            .header("appid", APP_ID)
            .header("region", "IN")
            .header("os", "android")
            .header("X-Timestamp", now)
            .header("Referer", "https://api.inmoviebox.com/")
            .header("X-Client-Token", clientToken)
            .header("x-tr-signature", sigHeader)
            .header("X-Play-Mode", "2")
            .header("X-Client-Info", clientInfoJson)
            .header("X-Client-Status", "1")
            .post(bodyString.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val resp = client.newCall(request).execute()
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw IOException("Visitor login failed with HTTP ${resp.code}: $body")
        }
        val json = JSONObject(body)
        val token = json.optJSONObject("data")?.optString("token")
        if (token.isNullOrBlank()) {
            throw IOException("No visitor token in response: $body")
        }
        token
    }

    private fun getClientInfoJson(): String {
        return JSONObject().apply {
            put("package_name", "com.community.oneroom")
            put("version_name", "4.0.02")
            put("version_code", 50020126)
            put("os", "android")
            put("os_version", "14")
            put("install_ch", "ps")
            put("device_id", "8c5da15be6ca34e724a27bc102cd8bcf")
            put("brand", "Google")
            put("model", "Pixel 6")
            put("region", "IN")
            put("timezone", "Asia/Kolkata")
            put("sp_code", "40401")
        }.toString()
    }

    internal suspend fun executeSignedRequest(
        method: String,
        endpoint: String,
        queryParams: Map<String, String> = emptyMap(),
        bodyString: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        var token = getOrBootstrapToken()
        var resp = doRequest(method, endpoint, queryParams, bodyString, token)

        // Handle token expiration / 401
        if (resp.first == 401 || resp.second.optInt("code") == 401 || resp.second.optString("message").contains("token", ignoreCase = true)) {
            android.util.Log.i(TAG, "Token expired, refreshing visitor token...")
            tokenMutex.withLock {
                inMemoryToken = null
                sharedPrefs?.edit()?.remove(KEY_VISITOR_TOKEN)?.apply()
                token = bootstrapVisitorToken()
                persistToken(token)
            }
            resp = doRequest(method, endpoint, queryParams, bodyString, token)
        }
        resp.second
    }

    private fun doRequest(
        method: String,
        endpoint: String,
        queryParams: Map<String, String>,
        bodyString: String,
        token: String
    ): Pair<Int, JSONObject> {
        val now = System.currentTimeMillis().toString()
        val reversed = now.reversed()
        val clientToken = "$now,${md5(reversed)}"

        val sortedQuery = if (queryParams.isNotEmpty()) {
            queryParams.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        } else ""

        val canonicalPath = endpoint + if (sortedQuery.isNotEmpty()) "?$sortedQuery" else ""
        val bodyBytes = if (bodyString.isNotEmpty()) bodyString.toByteArray(Charsets.UTF_8) else ByteArray(0)
        val bodyHash = if (bodyBytes.isNotEmpty()) {
            val limit = minOf(bodyBytes.size, 102400)
            md5Bytes(bodyBytes.copyOfRange(0, limit))
        } else ""
        val bodyLen = if (bodyBytes.isNotEmpty()) bodyBytes.size.toString() else ""

        val canonical = "$method\napplication/json\napplication/json;charset=UTF-8\n$bodyLen\n$now\n$bodyHash\n$canonicalPath"
        val sigDigest = hmacMd5(SIGNING_KEY_BYTES, canonical.toByteArray(Charsets.UTF_8))
        val sigHeader = "$now|2|${Base64.encodeToString(sigDigest, Base64.NO_WRAP)}"

        val url = "$BASE_URL$canonicalPath"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "MovieBox/4.0.02 (Android 14; Pixel 6)")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("X-Sign-Version", "2.0")
            .header("appid", APP_ID)
            .header("region", "IN")
            .header("os", "android")
            .header("X-Timestamp", now)
            .header("Referer", "https://api.inmoviebox.com/")
            .header("X-Client-Token", clientToken)
            .header("x-tr-signature", sigHeader)
            .header("X-Play-Mode", "2")
            .header("X-Client-Info", getClientInfoJson())
            .header("Authorization", "Bearer $token")
            .header("X-Client-Status", "0")

        if (method == "POST") {
            reqBuilder.post(bodyString.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            reqBuilder.get()
        }

        val resp = client.newCall(reqBuilder.build()).execute()
        val respBody = resp.body?.string().orEmpty()
        val json = try {
            JSONObject(respBody)
        } catch (_: Exception) {
            JSONObject().apply { put("raw", respBody) }
        }
        return Pair(resp.code, json)
    }

    override suspend fun checkHealth(): HealthResponse {
        return HealthResponse(status = "ok")
    }

    override suspend fun getHome(): HomeResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("categoryId", 2)
            put("page", 1)
            put("pageSize", 20)
        }
        val json = executeSignedRequest("POST", "/home/v2/get-list", bodyString = payload.toString())
        val data = json.optJSONObject("data")
        val items = parseMovieItems(data?.optJSONArray("items") ?: data?.optJSONArray("list"))
        HomeResponse(
            status = "ok",
            sections = listOf(
                HomeSection(section = "Featured", count = items.size, items = items)
            )
        )
    }

    override suspend fun getMovies(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("categoryId", 2)
            put("page", page)
            put("pageSize", 20)
        }
        val json = executeSignedRequest("POST", "/home/v2/get-list", bodyString = payload.toString())
        val data = json.optJSONObject("data")
        val items = parseMovieItems(data?.optJSONArray("items") ?: data?.optJSONArray("list"))
        CatalogResponse(items = items, page = page, hasMore = items.isNotEmpty())
    }

    override suspend fun getTvSeries(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("categoryId", 5)
            put("page", page)
            put("pageSize", 20)
        }
        val json = executeSignedRequest("POST", "/home/v2/get-list", bodyString = payload.toString())
        val data = json.optJSONObject("data")
        val items = parseMovieItems(data?.optJSONArray("items") ?: data?.optJSONArray("list"))
        CatalogResponse(items = items, page = page, hasMore = items.isNotEmpty())
    }

    override suspend fun getAnimation(page: Int, sort: String?, genre: String?): CatalogResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("categoryId", 8)
            put("page", page)
            put("pageSize", 20)
        }
        val json = executeSignedRequest("POST", "/home/v2/get-list", bodyString = payload.toString())
        val data = json.optJSONObject("data")
        val items = parseMovieItems(data?.optJSONArray("items") ?: data?.optJSONArray("list"))
        CatalogResponse(items = items, page = page, hasMore = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("keyword", query)
            put("page", page)
            put("pageSize", 20)
        }
        val json = executeSignedRequest("POST", "/wefeed-mobile-bff/subject-api/search", bodyString = payload.toString())
        val data = json.optJSONObject("data")
        val rawArr = data?.optJSONArray("items") ?: data?.optJSONArray("list")
        val items = parseMovieItems(rawArr)
        SearchResponse(items = items, query = query, page = page)
    }

    override suspend fun getSuggestions(query: String): SuggestResponse = withContext(Dispatchers.IO) {
        val s = search(query, 1)
        val suggestions = s.items.map {
            SuggestionItem(title = it.safeName, slug = it.safeSlug, rawSubjectId = it.rawSubjectId)
        }
        SuggestResponse(suggestions = suggestions)
    }

    override suspend fun getDetail(slug: String): DetailResponse = withContext(Dispatchers.IO) {
        val subjectId = extractSubjectId(slug)
        val json = executeSignedRequest(
            method = "GET",
            endpoint = "/wefeed-mobile-bff/subject-api/get",
            queryParams = mapOf("subjectId" to subjectId, "host" to "api.inmoviebox.com")
        )
        val data = json.optJSONObject("data") ?: JSONObject()
        val title = data.optString("title")
        val desc = data.optString("description")
        val rawDate = data.optString("releaseDate")
            .ifBlank { data.optString("release_date") }
            .ifBlank { data.optString("publishDate") }
            .ifBlank { data.optString("year") }
        val releaseDate = if (rawDate.isNotBlank()) {
            val trimmed = rawDate.trim()
            val digits = trimmed.filter { it.isDigit() }
            if (digits.length == 10 || digits.length == 13) {
                val epochMs = if (digits.length == 10) digits.toLong() * 1000L else digits.toLong()
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = epochMs
                val yr = cal.get(java.util.Calendar.YEAR)
                val mo = cal.get(java.util.Calendar.MONTH) + 1
                val da = cal.get(java.util.Calendar.DAY_OF_MONTH)
                "%04d-%02d-%02d".format(yr, mo, da)
            } else trimmed
        } else ""
        val duration = data.optString("duration")
        val genre = data.optString("genre")
        val poster = data.optJSONObject("cover")?.optString("url") ?: ""
        val rating = data.optDouble("imdbRatingValue", 0.0)
        val isTv = data.optInt("subjectType") == 2

        var seasons: List<SeasonItem> = emptyList()
        if (isTv) {
            seasons = fetchTvSeasons(subjectId)
        }

        val dubsList = mutableListOf<DubItem>()
        val rawDubs = data.optJSONArray("dubs")
        if (rawDubs != null) {
            for (i in 0 until rawDubs.length()) {
                val d = rawDubs.optJSONObject(i) ?: continue
                val dSubId = d.optString("subjectId")
                val lanName = d.optString("lanName").ifBlank { d.optString("lan") }
                val lan = d.optString("lan").ifBlank { d.optString("lanCode") }
                val isOrig = d.optBoolean("original") || lanName.contains("orig", ignoreCase = true)
                if (dSubId.isNotBlank()) {
                    dubsList.add(
                        DubItem(
                            subjectId = dSubId,
                            lanName = lanName,
                            lanCode = lan.ifBlank { null },
                            isOriginal = isOrig
                        )
                    )
                }
            }
        }

        val subject = SubjectDetail(
            rawSubjectId = subjectId,
            rawSubjectType = if (isTv) 2 else 1,
            title = title,
            description = desc,
            releaseDate = releaseDate,
            rawDuration = duration,
            genre = genre,
            rawRating = rating.toString(),
            hasResource = true,
            cover = CoverDetail(url = poster),
            dubs = dubsList
        )

        val detailData = DetailData(
            subject = subject,
            stars = emptyList(),
            resource = ResourceDetail(seasons = seasons),
            dubs = dubsList
        )

        DetailResponse(code = 0, message = "ok", data = detailData)
    }

    private suspend fun fetchTvSeasons(subjectId: String): List<SeasonItem> = withContext(Dispatchers.IO) {
        try {
            val json = executeSignedRequest(
                method = "GET",
                endpoint = "/wefeed-mobile-bff/subject-api/season-info/v2",
                queryParams = mapOf("subjectId" to subjectId, "host" to "api.inmoviebox.com")
            )
            val data = json.optJSONObject("data")
            val seasonsArr = data?.optJSONArray("seasons") ?: return@withContext emptyList()
            val result = mutableListOf<SeasonItem>()

            for (i in 0 until seasonsArr.length()) {
                val sObj = seasonsArr.optJSONObject(i) ?: continue
                val seNum = sObj.optInt("se", i + 1)
                val maxEp = sObj.optInt("maxEp", 1)
                val allEp = sObj.optString("allEp")

                result.add(
                    SeasonItem(
                        se = seNum,
                        maxEp = maxEp,
                        allEp = allEp.ifBlank { null }
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getStream(subjectId: String, detailPath: String, season: Int, episode: Int): StreamResponse = withContext(Dispatchers.IO) {
        val cacheKey = "$subjectId:$detailPath:$season:$episode"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]?.let { (cached, expiry) ->
            if (now < expiry) return@withContext cached
        }

        val resolved = resolveStreamInternal(subjectId, season, episode)
        streamCache[cacheKey] = Pair(resolved, now + STREAM_CACHE_TTL_MS)
        resolved
    }

    private suspend fun resolveStreamInternal(subjectId: String, se: Int, ep: Int): StreamResponse = withContext(Dispatchers.IO) {
        val queryParams = mutableMapOf(
            "subjectId" to subjectId,
            "host" to "api6.aoneroom.com",
            "quality" to "1080p"
        )
        if (se > 0) queryParams["se"] = se.toString()
        if (ep > 0) queryParams["ep"] = ep.toString()

        val json = executeSignedRequest("GET", "/wefeed-mobile-bff/subject-api/play-info/v2", queryParams)
        val data = json.optJSONObject("data")
        val streamsArr = data?.optJSONArray("streams") ?: data?.optJSONArray("streamList")
        val globalCookie = json.optString("signCookie").ifEmpty { data?.optString("signCookie").orEmpty() }

        val sources = mutableListOf<StreamSource>()
        if (streamsArr != null && streamsArr.length() > 0) {
            for (i in 0 until streamsArr.length()) {
                val sObj = streamsArr.optJSONObject(i) ?: continue
                var u = sObj.optString("url")
                val sCookie = sObj.optString("signCookie").ifEmpty { globalCookie }

                // Anti-Piracy urlprefix bypass: decodes genuine m3u8 playlist path
                if (sCookie.contains("urlprefix=")) {
                    try {
                        val prefixStart = sCookie.indexOf("urlprefix=") + "urlprefix=".length
                        var prefixEnd = sCookie.indexOf(":", prefixStart)
                        if (prefixEnd == -1) prefixEnd = sCookie.indexOf(";", prefixStart)
                        if (prefixEnd == -1) prefixEnd = sCookie.length
                        var base64Str = sCookie.substring(prefixStart, prefixEnd)
                        val padding = (4 - base64Str.length % 4) % 4
                        base64Str += "=".repeat(padding)
                        val decodedPathBytes = Base64.decode(base64Str, Base64.DEFAULT)
                        val decodedPath = String(decodedPathBytes, Charsets.UTF_8)
                        u = if (decodedPath.endsWith("/")) "${decodedPath}index.m3u8" else "${decodedPath}/index.m3u8"
                    } catch (_: Exception) {}
                }

                val dur = sObj.optInt("duration", 0)
                val isDummyUrl = u.contains("macdn.aoneroom.com") || u.contains("please_update") || u.contains("update_app") || u.contains("b164fbfb43") || (dur in 1..60)
                if (!isDummyUrl && u.isNotBlank()) {
                    val q = extractResolution(sObj, u, fallbackRank = i)
                    val size = sObj.optLong("size", 0L)
                    val duration = sObj.optLong("duration", 0L)
                    val format = if (u.contains(".m3u8", ignoreCase = true)) "HLS" else "MP4"
                    sources.add(
                        StreamSource(
                            resolution = q,
                            format = format,
                            url = u,
                            directUrl = u,
                            rawSize = size,
                            rawDuration = duration,
                            cookie = sCookie.takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

        // If official streams were empty or dummy, execute AnyFilm UGC bypass
        if (sources.isEmpty()) {
            android.util.Log.i(TAG, "Official stream dummy/placeholder detected for $subjectId; executing AnyFilm UGC bypass...")
            val ugcSources = resolveUgcStreams(subjectId, se, ep)
            if (ugcSources.isNotEmpty()) {
                sources.addAll(ugcSources)
            }
        }

        val normalized = normalizeStreamSources(sources)

        StreamResponse(
            subjectId = subjectId,
            se = se,
            ep = ep,
            hasResource = normalized.isNotEmpty(),
            sources = normalized,
            hls = emptyList(),
            dash = emptyList()
        )
    }

    private fun extractResolution(rObj: JSONObject, u: String, fallbackRank: Int = 0): String {
        val candidates = listOf(
            rObj.optString("resolutions"),
            rObj.optString("resolution"),
            rObj.optString("quality"),
            rObj.optString("definition"),
            rObj.optString("format"),
            rObj.optString("title")
        )
        for (cand in candidates) {
            val num = cand.filter { it.isDigit() }.toIntOrNull()
            if (num != null && num in 240..4320) {
                return "${num}p"
            }
        }
        val uLower = u.lowercase()
        val match = Regex("""(\d{3,4})p""").find(uLower)
        if (match != null) {
            return match.value.lowercase()
        }
        if (uLower.contains("1080") || uLower.contains("nyaa") || uLower.contains("fhd")) return "1080p"
        if (uLower.contains("720") || uLower.contains("hd")) return "720p"
        if (uLower.contains("480") || uLower.contains("sd")) return "480p"
        if (uLower.contains("360")) return "360p"

        return when (fallbackRank) {
            0 -> "1080p"
            1 -> "720p"
            2 -> "480p"
            else -> "360p"
        }
    }

    private fun normalizeStreamSources(rawSources: List<StreamSource>): List<StreamSource> {
        if (rawSources.isEmpty()) return emptyList()

        val uniqueSources = mutableListOf<StreamSource>()
        val seenUrls = mutableSetOf<String>()
        for (src in rawSources) {
            if (seenUrls.add(src.url)) {
                uniqueSources.add(src)
            }
        }

        val sorted = uniqueSources.sortedWith { a, b ->
            val sA = (a.rawSize as? Number)?.toLong() ?: a.size?.toLongOrNull() ?: 0L
            val sB = (b.rawSize as? Number)?.toLong() ?: b.size?.toLongOrNull() ?: 0L
            if (sA != sB) {
                sB.compareTo(sA)
            } else {
                val rA = a.resolution.filter { it.isDigit() }.toIntOrNull() ?: 0
                val rB = b.resolution.filter { it.isDigit() }.toIntOrNull() ?: 0
                rB.compareTo(rA)
            }
        }

        val resSet = sorted.map { it.resolution }.toSet()
        if (resSet.size < sorted.size && sorted.size in 2..5) {
            val standardQualities = listOf("1080p", "720p", "480p", "360p")
            return sorted.mapIndexed { idx, s ->
                val q = standardQualities.getOrElse(idx) { "${(480 - idx * 120).coerceAtLeast(240)}p" }
                s.copy(resolution = q)
            }
        }

        if (sorted.size == 1) {
            val base = sorted[0]
            val baseSize = (base.rawSize as? Number)?.toLong() ?: base.size?.toLongOrNull() ?: 0L
            return listOf(
                base.copy(
                    resolution = "1080p",
                    rawSize = if (baseSize > 0) baseSize else null
                ),
                base.copy(
                    resolution = "720p",
                    rawSize = if (baseSize > 0) (baseSize * 0.55).toLong() else null
                ),
                base.copy(
                    resolution = "480p",
                    rawSize = if (baseSize > 0) (baseSize * 0.30).toLong() else null
                )
            )
        }

        return sorted
    }

    private suspend fun resolveUgcStreams(subjectId: String, se: Int, ep: Int): List<StreamSource> = withContext(Dispatchers.IO) {
        val targetEp = if (ep > 0) ep else 1

        val firstJson = executeSignedRequest(
            method = "GET",
            endpoint = "/wefeed-mobile-bff/ugc/video/first-by-subject",
            queryParams = mapOf("subjectId" to subjectId, "host" to "api.inmoviebox.com")
        )
        val data = firstJson.optJSONObject("data") ?: return@withContext emptyList()
        val ugcVidId = data.optString("ugcVideoId")
        val belongToCollection = data.optJSONObject("belongToCollection")
        val collectionId = belongToCollection?.optString("collectionId")

        val resultSources = mutableListOf<StreamSource>()

        // 1. Try Collection download-list if part of a collection
        if (!collectionId.isNullOrBlank()) {
            val dlJson = executeSignedRequest(
                method = "GET",
                endpoint = "/wefeed-mobile-bff/ugc/collection/download-list",
                queryParams = mapOf("collectionId" to collectionId, "page" to "1", "perPage" to "50")
            )
            val dlData = dlJson.optJSONObject("data")
            val items = dlData?.optJSONArray("items")
            if (items != null) {
                var targetItem: JSONObject? = null
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val itemEp = it.optInt("episode", it.optInt("ep", it.optInt("episodeNumber", 0)))
                    if (itemEp == targetEp) {
                        targetItem = it
                        break
                    }
                }
                if (targetItem == null && items.length() > 0 && targetEp == 1) {
                    targetItem = items.optJSONObject(0)
                }
                if (targetItem != null) {
                    val resArr = targetItem.optJSONArray("resources")
                    if (resArr != null) {
                        for (j in 0 until resArr.length()) {
                            val r = resArr.optJSONObject(j) ?: continue
                            val u = r.optString("url")
                            if (u.isNotBlank() && !u.contains("macdn.aoneroom.com")) {
                                val resLabel = extractResolution(r, u, fallbackRank = j)
                                val size = r.optLong("size", 0L)
                                val dur = r.optLong("duration", 0L)
                                val ugcCookie = r.optString("signCookie")
                                    .ifEmpty { targetItem.optString("signCookie") }
                                    .ifEmpty { dlData?.optString("signCookie").orEmpty() }
                                resultSources.add(
                                    StreamSource(
                                        resolution = resLabel,
                                        format = "MP4",
                                        url = u,
                                        directUrl = u,
                                        rawSize = size,
                                        rawDuration = dur,
                                        cookie = ugcCookie.takeIf { it.isNotBlank() }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Single video download fallback if collection didn't yield
        if (resultSources.isEmpty() && ugcVidId.isNotBlank()) {
            val singleJson = executeSignedRequest(
                method = "GET",
                endpoint = "/wefeed-mobile-bff/ugc/video/download",
                queryParams = mapOf("ugcVideoId" to ugcVidId, "host" to "api.inmoviebox.com")
            )
            val singleData = singleJson.optJSONObject("data")
            val items = singleData?.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val resArr = it.optJSONArray("resources") ?: continue
                    for (j in 0 until resArr.length()) {
                        val r = resArr.optJSONObject(j) ?: continue
                        val u = r.optString("url")
                        if (u.isNotBlank() && !u.contains("macdn.aoneroom.com")) {
                            val resLabel = extractResolution(r, u, fallbackRank = j)
                            val size = r.optLong("size", 0L)
                            val dur = r.optLong("duration", 0L)
                            val ugcCookie = r.optString("signCookie")
                                .ifEmpty { it.optString("signCookie") }
                                .ifEmpty { singleData?.optString("signCookie").orEmpty() }
                            resultSources.add(
                                StreamSource(
                                    resolution = resLabel,
                                    format = "MP4",
                                    url = u,
                                    directUrl = u,
                                    rawSize = size,
                                    rawDuration = dur,
                                    cookie = ugcCookie.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            }
        }
        resultSources
    }

    override suspend fun getCaptions(subjectId: String, detailPath: String, season: Int, episode: Int): CaptionsResponse = withContext(Dispatchers.IO) {
        val captionsList = mutableListOf<CaptionItem>()
        val seenUrls = mutableSetOf<String>()

        fun addCaption(id: String, lan: String, lanName: String, url: String, size: String = "0", delay: Long = 0L) {
            if (url.isNotBlank() && seenUrls.add(url)) {
                captionsList.add(
                    CaptionItem(
                        id = id,
                        lan = lan,
                        lanName = lanName,
                        url = url,
                        size = size,
                        delay = delay
                    )
                )
            }
        }

        try {
            // 1. Fetch detail for current subject to inspect resourceDetectors and dubs
            val currentDetailJson = executeSignedRequest(
                method = "GET",
                endpoint = "/wefeed-mobile-bff/subject-api/get",
                queryParams = mapOf("subjectId" to subjectId, "host" to "api.inmoviebox.com")
            )
            val currentData = currentDetailJson.optJSONObject("data")
            val currentDetectors = currentData?.optJSONArray("resourceDetectors")
            val dubsArr = currentData?.optJSONArray("dubs")

            var mainSubjectId = subjectId
            var engSubjectId: String? = null
            if (dubsArr != null) {
                for (i in 0 until dubsArr.length()) {
                    val d = dubsArr.optJSONObject(i) ?: continue
                    val dSubId = d.optString("subjectId")
                    val isOrig = d.optBoolean("original") || d.optString("lanName").contains("orig", ignoreCase = true)
                    if (isOrig && dSubId.isNotBlank()) {
                        mainSubjectId = dSubId
                    }
                    val lan = (d.optString("lanName") + " " + d.optString("lan")).lowercase()
                    if (lan.contains("eng") && dSubId.isNotBlank()) {
                        engSubjectId = dSubId
                    }
                }
            }

            val targetPairs = mutableListOf<Pair<String, String>>()

            // (A) Main / Original subject detectors
            if (mainSubjectId != subjectId) {
                try {
                    val mainDetailJson = executeSignedRequest(
                        method = "GET",
                        endpoint = "/wefeed-mobile-bff/subject-api/get",
                        queryParams = mapOf("subjectId" to mainSubjectId, "host" to "api.inmoviebox.com")
                    )
                    val mainDetectors = mainDetailJson.optJSONObject("data")?.optJSONArray("resourceDetectors")
                    if (mainDetectors != null) {
                        for (i in 0 until mainDetectors.length()) {
                            val d = mainDetectors.optJSONObject(i) ?: continue
                            val rId = d.optString("resourceId")
                            if (rId.isNotBlank()) targetPairs.add(Pair(mainSubjectId, rId))
                        }
                    }
                } catch (_: Exception) {}
            }

            // (B) Current subject detectors
            if (currentDetectors != null) {
                for (i in 0 until currentDetectors.length()) {
                    val d = currentDetectors.optJSONObject(i) ?: continue
                    val rId = d.optString("resourceId")
                    if (rId.isNotBlank()) targetPairs.add(Pair(subjectId, rId))
                }
            }

            // (C) English dub detectors if available and different
            if (engSubjectId != null && engSubjectId != mainSubjectId && engSubjectId != subjectId) {
                try {
                    val engDetailJson = executeSignedRequest(
                        method = "GET",
                        endpoint = "/wefeed-mobile-bff/subject-api/get",
                        queryParams = mapOf("subjectId" to engSubjectId, "host" to "api.inmoviebox.com")
                    )
                    val engDetectors = engDetailJson.optJSONObject("data")?.optJSONArray("resourceDetectors")
                    if (engDetectors != null) {
                        for (i in 0 until engDetectors.length()) {
                            val d = engDetectors.optJSONObject(i) ?: continue
                            val rId = d.optString("resourceId")
                            if (rId.isNotBlank()) targetPairs.add(Pair(engSubjectId, rId))
                        }
                    }
                } catch (_: Exception) {}
            }

            targetPairs.add(Pair(mainSubjectId, ""))
            targetPairs.add(Pair(subjectId, ""))

            for ((targetSubId, targetResId) in targetPairs.distinct()) {
                try {
                    val qParams = mutableMapOf(
                        "subjectId" to targetSubId,
                        "host" to "api.inmoviebox.com"
                    )
                    if (targetResId.isNotBlank()) qParams["resourceId"] = targetResId
                    if (season > 0) qParams["se"] = season.toString()
                    if (episode > 0) qParams["ep"] = episode.toString()

                    val extJson = executeSignedRequest("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions", qParams)
                    val extData = extJson.optJSONObject("data")
                    val extList = extData?.optJSONArray("extCaptions")
                        ?: extData?.optJSONArray("list")
                        ?: extData?.optJSONArray("captions")
                        ?: extJson.optJSONArray("data")
                    if (extList != null) {
                        for (i in 0 until extList.length()) {
                            val c = extList.optJSONObject(i) ?: continue
                            val u = c.optString("url")
                            if (u.isNotBlank()) {
                                addCaption(
                                    id = c.optString("id", i.toString()),
                                    lan = c.optString("lan", "en"),
                                    lanName = c.optString("lanName", "English"),
                                    url = u,
                                    size = c.optString("size", "0"),
                                    delay = c.optLong("delay", 0L)
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Play-info subTitleList fallback
            try {
                val playParams = mutableMapOf(
                    "subjectId" to subjectId,
                    "host" to "api.inmoviebox.com"
                )
                if (season > 0) playParams["se"] = season.toString()
                if (episode > 0) playParams["ep"] = episode.toString()
                val playJson = executeSignedRequest("GET", "/wefeed-mobile-bff/subject-api/play-info/v2", playParams)
                val pData = playJson.optJSONObject("data")
                val pSubs = pData?.optJSONArray("subTitleList") ?: pData?.optJSONArray("subtitles")
                if (pSubs != null) {
                    for (i in 0 until pSubs.length()) {
                        val s = pSubs.optJSONObject(i) ?: continue
                        val u = s.optString("url")
                        if (u.isNotBlank()) {
                            addCaption(
                                id = s.optString("id", i.toString()),
                                lan = s.optString("lan", "en"),
                                lanName = s.optString("lanName", "English"),
                                url = u,
                                size = s.optString("size", "0"),
                                delay = s.optLong("delay", 0L)
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            // 3. OpenSubtitles fallback if captions are still empty
            if (captionsList.isEmpty()) {
                val movieTitle = currentData?.optString("title").orEmpty()
                if (movieTitle.isNotBlank()) {
                    try {
                        val isMovie = currentData?.optInt("subjectType") == 1
                        val osSub = OpenSubtitlesHelper.fetchSubtitle(
                            client = client,
                            title = movieTitle,
                            isMovie = isMovie,
                            season = if (season > 0) season else null,
                            episode = if (episode > 0) episode else null,
                            language = "en"
                        )
                        if (osSub != null && osSub.url.isNotBlank()) {
                            addCaption(
                                id = "os_en",
                                lan = osSub.language,
                                lanName = osSub.languageName,
                                url = osSub.url
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

        } catch (_: Exception) {}

        val sorted = captionsList.sortedWith { a, b ->
            val aName = (a.lanName.orEmpty() + " " + a.lan.orEmpty()).lowercase()
            val bName = (b.lanName.orEmpty() + " " + b.lan.orEmpty()).lowercase()
            fun score(s: String): Int = when {
                s.contains("eng") -> 3
                s.contains("hin") || s.contains("हिन्दी") -> 2
                s.contains("orig") -> 1
                else -> 0
            }
            val sComp = score(bName).compareTo(score(aName))
            if (sComp != 0) sComp else aName.compareTo(bName)
        }

        CaptionsResponse(
            subjectId = subjectId,
            se = season,
            ep = episode,
            count = sorted.size,
            captions = sorted
        )
    }

    private fun extractSubjectId(slug: String): String {
        val trimmed = slug.trim()
        if (trimmed.all { it.isDigit() }) return trimmed
        val parts = trimmed.split("-")
        val lastPart = parts.lastOrNull()
        if (!lastPart.isNullOrBlank() && lastPart.length >= 8 && lastPart.all { it.isLetterOrDigit() }) {
            return lastPart
        }
        return trimmed
    }

    private fun parseMovieItems(arr: JSONArray?): List<MovieItem> {
        if (arr == null) return emptyList()
        val list = mutableListOf<MovieItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val sid = obj.optString("subjectId").ifEmpty { obj.optString("id") }
            if (sid.isBlank()) continue
            val title = obj.optString("title").ifEmpty { obj.optString("name") }
            val slug = obj.optString("detailPath").ifEmpty { sid }
            val rawYear = obj.optString("year").ifBlank { null }
            val rawDate = obj.optString("releaseDate")
                .ifBlank { obj.optString("release_date") }
                .ifBlank { obj.optString("publishDate") }
                .ifBlank { obj.optString("pubDate") }

            val year = when {
                !rawYear.isNullOrBlank() && rawYear.length == 4 && rawYear.all { it.isDigit() } -> rawYear
                rawDate.isNotBlank() -> {
                    val trimmed = rawDate.trim()
                    val digits = trimmed.filter { it.isDigit() }
                    if (digits.length == 10 || digits.length == 13) {
                        val epochMs = if (digits.length == 10) digits.toLong() * 1000L else digits.toLong()
                        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                        cal.timeInMillis = epochMs
                        cal.get(java.util.Calendar.YEAR).toString()
                    } else if (trimmed.length >= 4 && trimmed.substring(0, 4).all { it.isDigit() }) {
                        trimmed.substring(0, 4)
                    } else null
                }
                else -> null
            }
            val isTv = obj.optInt("subjectType", 1) == 2
            val poster = obj.optJSONObject("cover")?.optString("url") ?: obj.optString("cover")
            val rating = obj.optDouble("imdbRatingValue", 0.0)
            val genre = obj.optString("genre")
            val country = obj.optString("countryName").ifBlank { obj.optString("country") }
            val language = obj.optString("language")

            list.add(
                MovieItem(
                    name = title,
                    posterUrl = poster,
                    slug = slug,
                    rawSubjectId = sid,
                    rawRating = rating.toString(),
                    rawYear = year,
                    rawType = if (isTv) "tv" else "movie",
                    genre = genre,
                    country = country,
                    language = language
                )
            )
        }
        return list
    }
}
