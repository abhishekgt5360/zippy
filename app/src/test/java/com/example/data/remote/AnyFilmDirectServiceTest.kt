package com.example.data.remote

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnyFilmDirectServiceTest {

    private lateinit var context: Context
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val okHttpClient = OkHttpClient.Builder().build()
    private lateinit var anyFilmService: AnyFilmDirectService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        anyFilmService = AnyFilmDirectService(okHttpClient, moshi)
        anyFilmService.init(context.cacheDir, context)
        NetworkClient.init(context.cacheDir, context)
    }

    // 1. Health check returns ok
    @Test
    fun test01_checkHealth() = runBlocking {
        val health = anyFilmService.checkHealth()
        assertEquals("ok", health.status)
    }

    // 2. Cryptographic HMAC-MD5 signing test
    @Test
    fun test02_hmacMd5SignatureCalculation() {
        val keyBytes = Base64.decode("76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O", Base64.DEFAULT)
        val data = "POST\napplication/json\napplication/json;charset=UTF-8\n2\n1700000000000\n99914b932bd37a50b983c5e7c90ae93b\n/wefeed-mobile-bff/user-api/visitor-login"
        val signature = AnyFilmDirectService.hmacMd5(keyBytes, data.toByteArray(Charsets.UTF_8))
        assertNotNull(signature)
        assertEquals(16, signature.size) // MD5 output is always 16 bytes (128 bits)
    }

    // 3. MD5 string and bytes functions
    @Test
    fun test03_md5Helper() {
        val testStr = "1700000000000"
        val hash = AnyFilmDirectService.md5(testStr)
        assertNotNull(hash)
        assertEquals(32, hash.length) // Hex string representation is 32 chars
    }

    // 4. NetworkClient.api returns AnyFilmDirectService exclusively
    @Test
    fun test04_networkClientExclusiveAnyFilmRouting() {
        val api = NetworkClient.api
        assertTrue("Expected AnyFilmDirectService", api is AnyFilmDirectService)
    }

    // 6. Live TV Service M3U parser test
    @Test
    fun test06_liveTvM3uParser() {
        val liveTvService = AnyFilmLiveTvService(okHttpClient)
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="set-hd" tvg-name="SET HD" group-title="SonyLIV Entertainment",SET HD
            https://dishmt.slivcdn.com/hls/live/2011671/SETHD/master.m3u8?hdnea=123
            #EXTINF:-1 tvg-id="ten1-hd" tvg-name="Sony Ten 1 HD" group-title="SonyLIV Sports",Sony Ten 1 HD
            https://dishmt.slivcdn.com/hls/live/2011747/TEN1HD/master.m3u8?hdnea=456
        """.trimIndent()

        // Reflection to call private parseM3u
        val method = AnyFilmLiveTvService::class.java.getDeclaredMethod("parseM3u", String::class.java, String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val channels = method.invoke(liveTvService, sampleM3u, "SonyLIV") as List<LiveTvChannel>

        assertEquals(2, channels.size)
        assertEquals("SET HD", channels[0].name)
        assertEquals("SonyLIV Entertainment", channels[0].category)
        assertTrue(channels[0].streamUrl.startsWith("https://dishmt.slivcdn.com"))

        assertEquals("Sony Ten 1 HD", channels[1].name)
        assertEquals("SonyLIV Sports", channels[1].category)
    }

    @Test
    fun test07_searchHeartOfTheBeast() = runBlocking {
        try {
            val res = anyFilmService.search("Heart of the Beast")
            println("=== ANYFILM SEARCH FOR Heart of the Beast ===")
            println("count=${res.items.size}")
            for (item in res.items) {
                println("Item: subjectId=${item.subjectId}, title='${item.safeName}', year=${item.year}")
                val stream = anyFilmService.getStream(item.subjectId, item.safeSlug, 1, 1)
                println("  Stream hasResource=${stream.hasResource}, sourcesCount=${stream.sources.size}")
                for (s in stream.sources) {
                    println("    Source: res=${s.resolution}, url=${s.url}")
                }
            }
        } catch (e: Exception) {
            println("Search error: $e")
            e.printStackTrace()
        }
    }

    @Test
    fun test08_inspectPlayInfoAndCdn() = runBlocking {
        val subjectId = "6749276456759056720" // Nocturne
        val stream = anyFilmService.getStream(subjectId, "nocturne", 1, 1)
        println("=== INSPECT NOCTURNE for $subjectId ===")
        for (s in stream.sources) {
            println("URL: ${s.url}")
            val req1 = okhttp3.Request.Builder()
                .url(s.url)
                .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
                .head()
                .build()
            val resp1 = okHttpClient.newCall(req1).execute()
            println("  Test 1 (AnyFilm UA, no referer): HTTP ${resp1.code} ${resp1.message}")
            resp1.close()

            val req2 = okhttp3.Request.Builder()
                .url(s.url)
                .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
                .header("Range", "bytes=0-1024")
                .build()
            val resp2 = okHttpClient.newCall(req2).execute()
            println("  Test 2 (AnyFilm UA, Range bytes=0-1024): HTTP ${resp2.code} ${resp2.message}, len=${resp2.body?.contentLength()}")
            resp2.close()

            val req3 = okhttp3.Request.Builder()
                .url(s.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://moviebox.ph/")
                .header("Origin", "https://moviebox.ph")
                .head()
                .build()
            val resp3 = okHttpClient.newCall(req3).execute()
            println("  Test 3 (Cinepulse default with Referer moviebox.ph): HTTP ${resp3.code} ${resp3.message}")
            assertTrue("Expected 200 OK for AnyFilm UA", resp1.code == 200 || resp1.code == 206)
            assertEquals("Expected 206 Partial Content for Range byte request", 206, resp2.code)
            resp3.close()
        }
    }

    @Test
    fun test09_routingHttpDataSourceTargetClassification() {
        val factory = RoutingHttpDataSource.Factory(context)
        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
        assertTrue(dataSource is RoutingHttpDataSource)

        // Verify active stream headers can be updated and cleared
        ActiveStreamHeaders.update("test_cookie=123", mapOf("X-Test" to "Value"))
        assertEquals("test_cookie=123", ActiveStreamHeaders.currentCookie)
        assertEquals("Value", ActiveStreamHeaders.currentHeaders?.get("X-Test"))

        ActiveStreamHeaders.clear()
        assertNull(ActiveStreamHeaders.currentCookie)
        assertNull(ActiveStreamHeaders.currentHeaders)
    }

    @Test
    fun test10_dubsAndQualityAndCaptionsResolution() = runBlocking {
        val searchRes = anyFilmService.search("Inception")
        assertTrue("Search should return items", searchRes.items.isNotEmpty())
        val movie = searchRes.items.first()
        val subjectId = movie.subjectId

        val detail = anyFilmService.getDetail(subjectId)
        assertNotNull(detail.data)
        val dubs = detail.data?.dubs.orEmpty()
        assertTrue("Detail should populate dubs list", dubs.isNotEmpty())
        assertTrue("Should include English or Original audio dub", dubs.any { it.isOriginal || it.lanName.contains("eng", ignoreCase = true) })

        val stream = anyFilmService.getStream(subjectId, subjectId, 0, 0)
        assertTrue("Stream sources should not be empty", stream.sources.isNotEmpty())
        if (stream.sources.size > 1) {
            val resolutions = stream.sources.map { it.resolution }
            val distinctResolutions = resolutions.distinct()
            assertTrue("Stream choices must have distinct resolutions: $resolutions", distinctResolutions.size > 1)
        }

        val captions = anyFilmService.getCaptions(subjectId, subjectId, 0, 0)
        assertTrue("Captions should be returned", captions.captions.orEmpty().isNotEmpty())
    }
}


