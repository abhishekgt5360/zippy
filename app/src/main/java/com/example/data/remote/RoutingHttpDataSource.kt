package com.example.data.remote

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Global holder for active stream playback headers (e.g. anti-piracy session cookies).
 */
object ActiveStreamHeaders {
    @Volatile
    var currentCookie: String? = null

    @Volatile
    var currentHeaders: Map<String, String>? = null

    fun update(cookie: String?, headers: Map<String, String>? = null) {
        currentCookie = cookie
        currentHeaders = headers
    }

    fun clear() {
        currentCookie = null
        currentHeaders = null
    }
}

/**
 * Intelligent, provider-aware HTTP DataSource that dynamically routes ExoPlayer requests:
 * 1. Default MovieBox Web/Proxy requests retain the required Referer, Origin, and Dhaka timezone headers.
 * 2. AnyFilm CDN streams (bcdn.hakunaymatata.com, aoneroom.com, inmoviebox.com, etc.)
 *    are routed using MovieBoxPro Android User-Agent WITHOUT moviebox.ph Referer/Origin headers
 *    (which cause HTTP 429 Too Many Requests / 403 Forbidden).
 * 3. Dynamic session cookies (e.g. signCookie) and custom headers are attached automatically.
 */
@OptIn(UnstableApi::class)
class RoutingHttpDataSource(
    private val defaultDataSource: HttpDataSource,
    private val anyFilmDataSource: HttpDataSource,
    private val isAnyFilmTarget: (Uri) -> Boolean,
    private val getActiveCookie: () -> String?,
    private val getActiveHeaders: () -> Map<String, String>?
) : HttpDataSource {

    private var activeDataSource: HttpDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        defaultDataSource.addTransferListener(transferListener)
        anyFilmDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val useAnyFilm = isAnyFilmTarget(dataSpec.uri)
        val target = if (useAnyFilm) anyFilmDataSource else defaultDataSource
        val cookie = getActiveCookie()
        if (!cookie.isNullOrBlank()) {
            target.setRequestProperty("Cookie", cookie)
        } else {
            target.clearRequestProperty("Cookie")
        }
        val customHeaders = getActiveHeaders()
        if (!customHeaders.isNullOrEmpty()) {
            for ((k, v) in customHeaders) {
                target.setRequestProperty(k, v)
            }
        }
        activeDataSource = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeDataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? {
        return activeDataSource?.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeDataSource?.responseHeaders ?: emptyMap()
    }

    override fun getResponseCode(): Int {
        return activeDataSource?.responseCode ?: -1
    }

    override fun close() {
        try {
            activeDataSource?.close()
        } finally {
            activeDataSource = null
        }
    }

    override fun setRequestProperty(name: String, value: String) {
        defaultDataSource.setRequestProperty(name, value)
        anyFilmDataSource.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        defaultDataSource.clearRequestProperty(name)
        anyFilmDataSource.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        defaultDataSource.clearAllRequestProperties()
        anyFilmDataSource.clearAllRequestProperties()
    }

    class Factory(
        private val context: Context,
        private val defaultUserAgent: String = "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)",
        private val defaultHeaders: Map<String, String> = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "identity"
        ),
        private val anyFilmUserAgent: String = "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)",
        private val anyFilmHeaders: Map<String, String> = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "identity"
        )
    ) : HttpDataSource.Factory {

        private val defaultHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(defaultUserAgent)
            .setDefaultRequestProperties(defaultHeaders)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(25000)
            .setReadTimeoutMs(35000)

        private val anyFilmHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(anyFilmUserAgent)
            .setDefaultRequestProperties(anyFilmHeaders)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(25000)
            .setReadTimeoutMs(35000)

        private val requestProperties = HttpDataSource.RequestProperties()

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            defaultHttpFactory.setDefaultRequestProperties(defaultRequestProperties)
            requestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): HttpDataSource {
            val defaultSource = defaultHttpFactory.createDataSource()
            val anyFilmSource = anyFilmHttpFactory.createDataSource()
            return RoutingHttpDataSource(
                defaultDataSource = defaultSource,
                anyFilmDataSource = anyFilmSource,
                isAnyFilmTarget = { true },
                getActiveCookie = { ActiveStreamHeaders.currentCookie },
                getActiveHeaders = { ActiveStreamHeaders.currentHeaders }
            )
        }
    }
}

