package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object DownloadHelper {
    private const val HTTP_USER_AGENT = "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)"

    fun downloadVideo(
        context: Context,
        url: String,
        title: String,
        resolution: String,
        season: Int? = null,
        episode: Int? = null,
        cookie: String? = null,
        headers: Map<String, String>? = null
    ) {
        if (url.isBlank()) {
            Toast.makeText(context, "No download stream link available.", Toast.LENGTH_SHORT).show()
            return
        }

        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)) {
            Toast.makeText(context, "Segmented streams (HLS/DASH) cannot be downloaded offline.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: throw IllegalStateException("DownloadManager is not available on this device.")

            val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").trim('_')
            val ext = if (url.contains(".mkv", ignoreCase = true)) "mkv" else "mp4"
            val fileName = if (season != null && episode != null && season > 0 && episode > 0) {
                "${safeTitle}_S${season}E${episode}_${resolution}.${ext}"
            } else {
                "${safeTitle}_${resolution}.${ext}"
            }

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloading $title ($resolution) via Zippy")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Zippy/$fileName")
                .addRequestHeader("User-Agent", HTTP_USER_AGENT)
                .addRequestHeader("Accept", "*/*")
                .apply {
                    if (!cookie.isNullOrBlank()) {
                        addRequestHeader("Cookie", cookie)
                    }
                    headers?.forEach { (k, v) ->
                        addRequestHeader(k, v)
                    }
                }
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            downloadManager.enqueue(request)
            Toast.makeText(context, "Downloading $fileName to Downloads/Zippy", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Fallback: If subfolder creation is restricted on specific vendor ROM, retry directly in Downloads root
            try {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    ?: throw e
                val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").trim('_')
                val ext = if (url.contains(".mkv", ignoreCase = true)) "mkv" else "mp4"
                val fileName = "${safeTitle}_${resolution}.$ext"
                val fallbackRequest = DownloadManager.Request(Uri.parse(url))
                    .setTitle(fileName)
                    .setDescription("Downloading $title ($resolution) via Zippy")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    .addRequestHeader("User-Agent", HTTP_USER_AGENT)
                    .addRequestHeader("Accept", "*/*")
                    .apply {
                        if (!cookie.isNullOrBlank()) {
                            addRequestHeader("Cookie", cookie)
                        }
                        headers?.forEach { (k, v) ->
                            addRequestHeader(k, v)
                        }
                    }
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                downloadManager.enqueue(fallbackRequest)
                Toast.makeText(context, "Downloading $fileName to Downloads", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(context, "Download failed: ${e2.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
