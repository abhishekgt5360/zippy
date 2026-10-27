package com.example

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.remote.NetworkClient
import com.example.util.AdultFilter
import com.example.util.NollywoodFilter

class CinePulseApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Initialize network client with disk cache for fast movie loading
        NetworkClient.init(cacheDir)
        // Load the bundled Nigerian-title blocklist before any feed is rendered.
        NollywoodFilter.init(this)
        // Load the verified adult-title blocklist used while 18+ is disabled.
        AdultFilter.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // RGB_565 provides fast decoding and halves memory usage for smooth 60fps scrolling
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // Generous 25% heap memory cache for instant poster rendering
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("cinepulse_images"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB persistent disk cache for instant offline and repeat loads
                    .build()
            }
            .respectCacheHeaders(false) // Always cache image assets regardless of CDN headers
            .crossfade(120) // Fast subtle crossfade
            .build()
    }
}
