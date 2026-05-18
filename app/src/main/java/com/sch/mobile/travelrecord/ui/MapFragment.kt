package com.sch.mobile.travelrecord.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.sch.mobile.travelrecord.BuildConfig
import com.sch.mobile.travelrecord.PhotoViewerActivity
import com.sch.mobile.travelrecord.R
import com.sch.mobile.travelrecord.data.PhotoMarker
import com.sch.mobile.travelrecord.data.TripPhoto
import com.sch.mobile.travelrecord.data.TripRepository
import com.sch.mobile.travelrecord.media.ThumbnailLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MapFragment : Fragment(), OnMapReadyCallback {
    private var focusTripId: Long = 0
    private var repository: TripRepository? = null
    private var googleMap: GoogleMap? = null
    private var progressBar: ProgressBar? = null
    private var noticeView: TextView? = null
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val markerBitmapCache = HashMap<String, Bitmap>()
    private val loadingThumbnails = HashSet<String>()
    private val waitingThumbnailMarkers = HashMap<String, MutableList<Marker>>()
    private var viewDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        focusTripId = arguments?.getLong(ARG_FOCUS_TRIP_ID, 0) ?: 0
        repository = TripRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_map, container, false)
        progressBar = view.findViewById(R.id.map_progress)
        noticeView = view.findViewById(R.id.map_notice)
        viewDestroyed = false
        setupMapIfAvailable()
        return view
    }

    override fun onDestroyView() {
        viewDestroyed = true
        googleMap = null
        markerBitmapCache.clear()
        loadingThumbnails.clear()
        waitingThumbnailMarkers.clear()
        super.onDestroyView()
    }

    override fun onDestroy() {
        thumbnailExecutor.shutdownNow()
        markerBitmapCache.clear()
        loadingThumbnails.clear()
        waitingThumbnailMarkers.clear()
        repository?.close()
        super.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isMapToolbarEnabled = true
        map.setInfoWindowAdapter(PhotoInfoWindowAdapter())
        map.setOnMarkerClickListener { marker ->
            if (marker.tag is PhotoMarker) {
                marker.showInfoWindow()
                true
            } else {
                false
            }
        }
        map.setOnInfoWindowClickListener { marker ->
            val tag = marker.tag
            if (tag is PhotoMarker) openTripPhotoViewer(tag)
        }
        loadMarkers()
    }

    private fun setupMapIfAvailable() {
        if (TextUtils.isEmpty(BuildConfig.MAPS_API_KEY)) {
            setLoading(false)
            showNotice(R.string.map_key_missing)
            return
        }
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(requireContext())
        if (availability != ConnectionResult.SUCCESS) {
            setLoading(false)
            showNotice(R.string.map_play_services_missing)
            return
        }
        val mapFragment = SupportMapFragment.newInstance()
        childFragmentManager.beginTransaction()
            .replace(R.id.map_container, mapFragment)
            .commitNow()
        mapFragment.getMapAsync(this)
    }

    private fun loadMarkers() {
        val repo = repository ?: return
        if (googleMap == null) return
        setLoading(true)
        repo.getPhotoMarkersWithLocation(this::renderMarkers, this::showError)
    }

    private fun renderMarkers(markers: List<PhotoMarker>) {
        val map = googleMap ?: return
        if (!isAdded) return
        setLoading(false)
        map.clear()
        if (markers.isEmpty()) {
            showNotice(R.string.map_empty)
            return
        }
        noticeView?.visibility = View.GONE
        var focus: PhotoMarker? = null
        for (markerData in markers) {
            val position = LatLng(markerData.latitude, markerData.longitude)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title(markerData.tripPlace)
                    .snippet(markerData.originalName),
            )
            marker?.tag = markerData
            if (focus == null || markerData.tripNo == focusTripId) focus = markerData
        }
        focus?.let { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 13f)) }
    }

    private fun showNotice(stringRes: Int) {
        noticeView?.let {
            it.visibility = View.VISIBLE
            it.setText(stringRes)
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(throwable: Throwable) {
        setLoading(false)
        if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
    }

    private fun loadMarkerThumbnail(marker: Marker, photoMarker: PhotoMarker) {
        val photoUri = photoMarker.photoUri ?: return
        if (markerBitmapCache.containsKey(photoUri)) return
        val waitingMarkers = waitingThumbnailMarkers.getOrPut(photoUri) { ArrayList() }
        if (!waitingMarkers.contains(marker)) waitingMarkers.add(marker)
        if (loadingThumbnails.contains(photoUri)) return
        loadingThumbnails.add(photoUri)
        val appContext: Context = requireContext().applicationContext
        val thumbnailSize = dp(96)
        thumbnailExecutor.execute {
            // 지도 말풍선도 목록용 썸네일 캐시를 공유해 같은 원본을 반복 디코딩하지 않는다.
            val bitmap = ThumbnailLoader.loadThumbnailBitmap(appContext, photoUri, thumbnailSize, thumbnailSize)
            mainHandler.post {
                loadingThumbnails.remove(photoUri)
                val markersToRefresh = waitingThumbnailMarkers.remove(photoUri)
                if (bitmap != null) markerBitmapCache[photoUri] = bitmap
                if (isAdded && !viewDestroyed && googleMap != null && markersToRefresh != null) {
                    for (waitingMarker in markersToRefresh) {
                        if (waitingMarker.isInfoWindowShown) waitingMarker.showInfoWindow()
                    }
                }
            }
        }
    }

    private fun openTripPhotoViewer(marker: PhotoMarker) {
        val repo = repository
        if (repo == null) {
            PhotoViewerActivity.open(requireContext(), marker.photoUri, marker.originalName, true)
            return
        }
        repo.getTripPhotos(marker.tripNo, { photos ->
            if (!isAdded || viewDestroyed || photos.isEmpty()) return@getTripPhotos
            var startIndex = 0
            for (i in photos.indices) {
                val photo: TripPhoto = photos[i]
                if (photo.id == marker.photoId || marker.photoUri == photo.uriString) {
                    startIndex = i
                    break
                }
            }
            PhotoViewerActivity.open(requireContext(), ArrayList(photos), startIndex)
        }, {
            if (isAdded && !viewDestroyed) PhotoViewerActivity.open(requireContext(), marker.photoUri, marker.originalName, true)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private inner class PhotoInfoWindowAdapter : GoogleMap.InfoWindowAdapter {
        override fun getInfoWindow(marker: Marker): View? = null

        override fun getInfoContents(marker: Marker): View? {
            val photoMarker = marker.tag as? PhotoMarker ?: return null
            val view = LayoutInflater.from(requireContext()).inflate(R.layout.info_window_photo_marker, null, false)
            val photoView = view.findViewById<ImageView>(R.id.map_info_photo)
            val titleView = view.findViewById<TextView>(R.id.map_info_title)
            val dateView = view.findViewById<TextView>(R.id.map_info_date)
            val nameView = view.findViewById<TextView>(R.id.map_info_name)
            titleView.text = photoMarker.tripPlace
            dateView.text = photoMarker.visitDate
            val name = if (photoMarker.originalName.isNullOrBlank()) {
                getString(R.string.photo_viewer_count_format, photoMarker.sortOrder + 1, photoMarker.sortOrder + 1)
            } else {
                photoMarker.originalName
            }
            nameView.text = name
            val bitmap = markerBitmapCache[photoMarker.photoUri]
            if (bitmap != null) {
                photoView.setImageBitmap(bitmap)
            } else {
                photoView.setImageResource(R.drawable.ic_photo_placeholder)
                loadMarkerThumbnail(marker, photoMarker)
            }
            return view
        }
    }

    companion object {
        private const val ARG_FOCUS_TRIP_ID = "focus_trip_id"

        @JvmStatic
        fun newInstance(): MapFragment = newInstance(0)

        @JvmStatic
        fun newInstance(focusTripId: Long): MapFragment = MapFragment().apply {
            arguments = Bundle().apply { putLong(ARG_FOCUS_TRIP_ID, focusTripId) }
        }
    }
}
