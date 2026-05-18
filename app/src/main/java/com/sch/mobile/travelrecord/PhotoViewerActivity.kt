package com.sch.mobile.travelrecord

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.sch.mobile.travelrecord.data.TripPhoto
import com.sch.mobile.travelrecord.media.ImageStorage
import com.sch.mobile.travelrecord.ui.ZoomImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class PhotoViewerActivity : AppCompatActivity() {
    private val executor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingDeque(4),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uriStrings = ArrayList<String>()
    private val originalNames = ArrayList<String?>()
    private var gpsFlags = BooleanArray(0)
    private lateinit var countText: TextView
    private lateinit var metaText: TextView
    @Volatile private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(R.layout.activity_photo_viewer)

        val toolbar = findViewById<MaterialToolbar>(R.id.photo_viewer_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.photo_viewer_title)

        countText = findViewById(R.id.viewer_count)
        metaText = findViewById(R.id.viewer_meta)
        val viewPager = findViewById<ViewPager2>(R.id.viewer_pager)
        readIntentData()
        if (uriStrings.isEmpty()) {
            finish()
            return
        }

        val adapter = PhotoViewerAdapter()
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateMeta(position)
            }
        })
        val requestedIndex = if (savedInstanceState == null) {
            intent.getIntExtra(EXTRA_INDEX, 0)
        } else {
            savedInstanceState.getInt(STATE_INDEX, intent.getIntExtra(EXTRA_INDEX, 0))
        }
        val startIndex = max(0, min(requestedIndex, uriStrings.size - 1))
        currentIndex = startIndex
        viewPager.setCurrentItem(startIndex, false)
        updateMeta(startIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_INDEX, currentIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun readIntentData() {
        var uris = intent.getStringArrayListExtra(EXTRA_URIS)
        if (uris.isNullOrEmpty()) {
            val singleUri = intent.getStringExtra(EXTRA_URI)
            if (!singleUri.isNullOrBlank()) {
                uris = arrayListOf(singleUri)
                val names = arrayListOf(intent.getStringExtra(EXTRA_NAME))
                intent.putStringArrayListExtra(EXTRA_NAMES, ArrayList(names))
                intent.putExtra(EXTRA_GPS_FLAGS, booleanArrayOf(intent.getBooleanExtra(EXTRA_HAS_GPS, false)))
            }
        }
        if (uris != null) uriStrings.addAll(uris)
        val names = intent.getStringArrayListExtra(EXTRA_NAMES)
        if (names != null) originalNames.addAll(names)
        while (originalNames.size < uriStrings.size) originalNames.add("")
        val flags = intent.getBooleanArrayExtra(EXTRA_GPS_FLAGS)
        gpsFlags = flags ?: BooleanArray(uriStrings.size)
        if (gpsFlags.size < uriStrings.size) {
            val expanded = BooleanArray(uriStrings.size)
            System.arraycopy(gpsFlags, 0, expanded, 0, gpsFlags.size)
            gpsFlags = expanded
        }
    }

    private fun updateMeta(position: Int) {
        if (position < 0 || position >= uriStrings.size) return
        countText.text = getString(R.string.photo_viewer_count_format, position + 1, uriStrings.size)
        val originalName = originalNames[position]
        val safeName = if (originalName.isNullOrBlank()) getString(R.string.photo_viewer_title) else originalName
        metaText.text = safeName + " · " + getString(if (gpsFlags[position]) R.string.gps_available else R.string.gps_missing)
    }

    private fun decodePreview(uriString: String): Bitmap? {
        val key = uriString.trim() + "#preview"
        synchronized(PREVIEW_CACHE) {
            val cached = PREVIEW_CACHE.get(key)
            if (cached != null) return cached
        }
        // 원본 파일은 보존하되 화면에는 1600px 미리보기를 써서 슬라이드 중 메모리 급증을 막는다.
        val previewUri: Uri = ImageStorage.getOrCreatePreviewUri(this, uriString)
        val reqWidth = max(1, resources.displayMetrics.widthPixels)
        val reqHeight = max(1, resources.displayMetrics.heightPixels)
        val bitmap = ImageStorage.decodeSampledBitmap(this, previewUri, reqWidth, reqHeight, false)
        if (bitmap != null) {
            synchronized(PREVIEW_CACHE) {
                if (PREVIEW_CACHE.get(key) == null) PREVIEW_CACHE.put(key, bitmap)
            }
        }
        return bitmap
    }

    private inner class PhotoViewerAdapter : RecyclerView.Adapter<PhotoViewerAdapter.PhotoPageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_viewer_page, parent, false)
            return PhotoPageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PhotoPageViewHolder, position: Int) {
            val uriString = uriStrings[position]
            holder.bind(uriString)
        }

        override fun getItemCount(): Int = uriStrings.size

        private inner class PhotoPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ZoomImageView = itemView.findViewById(R.id.viewer_page_photo)
            private val progressBar: ProgressBar = itemView.findViewById(R.id.viewer_page_progress)

            fun bind(uriString: String) {
                val normalizedUriString = uriString.trim()
                val tag = normalizedUriString + "#preview"
                val previousTag = imageView.tag
                imageView.tag = tag
                val cached = synchronized(PREVIEW_CACHE) { PREVIEW_CACHE.get(tag) }
                if (cached != null) {
                    progressBar.visibility = View.GONE
                    imageView.setImageBitmap(cached)
                    imageView.post { imageView.resetZoom() }
                    return
                }
                if (tag != previousTag || imageView.drawable == null) {
                    imageView.setImageResource(R.drawable.ic_photo_placeholder)
                }
                imageView.resetZoom()
                progressBar.visibility = View.VISIBLE
                executor.execute {
                    val bitmap = decodePreview(normalizedUriString)
                    mainHandler.post {
                        if (isDestroyed || tag != imageView.tag) return@post
                        progressBar.visibility = View.GONE
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                        } else {
                            imageView.setImageResource(R.drawable.ic_photo_placeholder)
                        }
                        imageView.post { imageView.resetZoom() }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "com.sch.mobile.travelrecord.EXTRA_PHOTO_URI"
        private const val EXTRA_NAME = "com.sch.mobile.travelrecord.EXTRA_PHOTO_NAME"
        private const val EXTRA_HAS_GPS = "com.sch.mobile.travelrecord.EXTRA_PHOTO_HAS_GPS"
        private const val EXTRA_URIS = "com.sch.mobile.travelrecord.EXTRA_PHOTO_URIS"
        private const val EXTRA_NAMES = "com.sch.mobile.travelrecord.EXTRA_PHOTO_NAMES"
        private const val EXTRA_GPS_FLAGS = "com.sch.mobile.travelrecord.EXTRA_PHOTO_GPS_FLAGS"
        private const val EXTRA_INDEX = "com.sch.mobile.travelrecord.EXTRA_PHOTO_INDEX"
        private const val STATE_INDEX = "state_index"

        private val PREVIEW_CACHE = object : LruCache<String, Bitmap>(calculatePreviewCacheSizeKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
        }

        @JvmStatic
        fun open(context: Context, uriString: String?, originalName: String?, hasGps: Boolean) {
            if (uriString.isNullOrBlank()) return
            val uris = arrayListOf(uriString)
            val names = arrayListOf(originalName)
            open(context, uris, names, booleanArrayOf(hasGps), 0)
        }

        @JvmStatic
        fun open(context: Context, photos: List<TripPhoto>?, startIndex: Int) {
            if (photos.isNullOrEmpty()) return
            val uris = ArrayList<String>()
            val names = ArrayList<String?>()
            val gpsFlags = BooleanArray(photos.size)
            for (i in photos.indices) {
                val photo = photos[i]
                photo.uriString?.let { uris.add(it) } ?: uris.add("")
                names.add(photo.originalName)
                gpsFlags[i] = photo.hasLocation()
            }
            open(context, uris, names, gpsFlags, startIndex)
        }

        @JvmStatic
        fun open(context: Context, uris: List<String>?, names: List<String?>?, gpsFlags: BooleanArray?, startIndex: Int) {
            if (uris.isNullOrEmpty()) return
            val intent = Intent(context, PhotoViewerActivity::class.java)
            intent.putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
            intent.putStringArrayListExtra(EXTRA_NAMES, if (names == null) ArrayList() else ArrayList(names))
            intent.putExtra(EXTRA_GPS_FLAGS, gpsFlags)
            intent.putExtra(EXTRA_INDEX, max(0, min(startIndex, uris.size - 1)))
            context.startActivity(intent)
        }

        private fun calculatePreviewCacheSizeKb(): Int {
            val maxMemoryKb = Runtime.getRuntime().maxMemory() / 1024
            val targetKb = min(48 * 1024L, max(12 * 1024L, maxMemoryKb / 8))
            return targetKb.toInt()
        }
    }
}
