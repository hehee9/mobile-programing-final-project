package com.sch.mobile.travelrecord.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import com.sch.mobile.travelrecord.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class ThumbnailLoader {
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(context: Context, uriString: String?, imageView: ImageView, progressBar: ProgressBar) {
        if (uriString.isNullOrBlank()) {
            imageView.tag = null
            imageView.setImageResource(R.drawable.ic_photo_placeholder)
            progressBar.visibility = View.GONE
            return
        }

        val tag = uriString.trim() + "#thumb"
        val previousTag = imageView.tag
        imageView.tag = tag
        val appContext = context.applicationContext
        val cachedBitmap = getFromCache(tag)
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap)
            progressBar.visibility = View.GONE
            return
        }
        // 같은 URI가 재바인딩될 때 placeholder를 다시 깔지 않아 스크롤 깜빡임을 줄인다.
        if (tag != previousTag || imageView.drawable == null) {
            imageView.setImageResource(R.drawable.ic_photo_placeholder)
        }
        progressBar.visibility = View.VISIBLE

        executor.execute {
            val displayUri: Uri = ImageStorage.getOrCreateThumbnailUri(appContext, uriString.trim())
            val bitmap = decodeThumbnail(appContext, displayUri)
            if (bitmap != null) putInCache(tag, bitmap)
            mainHandler.post {
                val currentTag = imageView.tag
                if (tag != currentTag) return@post
                progressBar.visibility = View.GONE
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(R.drawable.ic_photo_placeholder)
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun decodeThumbnail(context: Context, uri: Uri): Bitmap? = ImageStorage.decodeSampledBitmap(context, uri, 320, 320, true)

    companion object {
        private val MEMORY_CACHE = object : LruCache<String, Bitmap>(calculateCacheSizeKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
        }

        @JvmStatic
        fun loadThumbnailBitmap(context: Context, uriString: String?, reqWidth: Int, reqHeight: Int): Bitmap? {
            if (uriString.isNullOrBlank()) return null
            val key = uriString.trim() + "#thumb"
            val cachedBitmap = getFromCache(key)
            if (cachedBitmap != null) return cachedBitmap
            val displayUri = ImageStorage.getOrCreateThumbnailUri(context.applicationContext, uriString.trim())
            val bitmap = ImageStorage.decodeSampledBitmap(context.applicationContext, displayUri, reqWidth, reqHeight, true)
            if (bitmap != null) putInCache(key, bitmap)
            return bitmap
        }

        private fun getFromCache(key: String): Bitmap? = synchronized(MEMORY_CACHE) { MEMORY_CACHE.get(key) }

        private fun putInCache(key: String, bitmap: Bitmap) {
            synchronized(MEMORY_CACHE) {
                if (MEMORY_CACHE.get(key) == null) MEMORY_CACHE.put(key, bitmap)
            }
        }

        private fun calculateCacheSizeKb(): Int {
            val maxMemoryKb = Runtime.getRuntime().maxMemory() / 1024
            val targetKb = min(16 * 1024L, max(4 * 1024L, maxMemoryKb / 16))
            return targetKb.toInt()
        }
    }
}
