package com.sch.mobile.travelrecord.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton
import com.sch.mobile.travelrecord.R
import com.sch.mobile.travelrecord.data.Trip
import com.sch.mobile.travelrecord.data.TripPhoto
import com.sch.mobile.travelrecord.media.ThumbnailLoader

class DetailContentAdapter(private val listener: Listener?) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    interface Listener {
        fun onMapRequested()
        fun onEditRequested()
        fun onDeleteRequested()
        fun onPhotoClicked(photo: TripPhoto, index: Int)
    }

    private val thumbnailLoader = ThumbnailLoader()
    private val photos = ArrayList<TripPhoto>()
    private var trip: Trip? = null
    private var gpsCount = 0

    fun setData(trip: Trip?, newPhotos: List<TripPhoto>?) {
        val nextPhotos = if (newPhotos == null) ArrayList() else ArrayList(newPhotos)
        if (hasSameContent(trip, nextPhotos)) {
            // 상세 화면 재개 시 같은 데이터면 masonry 카드 전체 재측정과 이미지 재요청을 피한다.
            return
        }
        this.trip = trip
        photos.clear()
        photos.addAll(nextPhotos)
        gpsCount = photos.count { it.hasLocation() }
        notifyDataSetChanged()
    }

    fun shutdown() {
        thumbnailLoader.shutdown()
    }

    private fun hasSameContent(nextTrip: Trip?, nextPhotos: List<TripPhoto>): Boolean {
        if (!sameTrip(trip, nextTrip) || photos.size != nextPhotos.size) return false
        for (i in photos.indices) if (!samePhoto(photos[i], nextPhotos[i])) return false
        return true
    }

    private fun sameTrip(oldTrip: Trip?, newTrip: Trip?): Boolean {
        if (oldTrip === newTrip) return true
        if (oldTrip == null || newTrip == null) return false
        return oldTrip.no == newTrip.no &&
            oldTrip.place == newTrip.place &&
            oldTrip.visitDate == newTrip.visitDate &&
            oldTrip.memo == newTrip.memo &&
            oldTrip.photoUri == newTrip.photoUri &&
            oldTrip.photoCount == newTrip.photoCount &&
            oldTrip.gpsPhotoCount == newTrip.gpsPhotoCount &&
            oldTrip.updatedAt == newTrip.updatedAt
    }

    private fun samePhoto(oldPhoto: TripPhoto, newPhoto: TripPhoto): Boolean =
        oldPhoto.id == newPhoto.id &&
            oldPhoto.uriString == newPhoto.uriString &&
            oldPhoto.latitude == newPhoto.latitude &&
            oldPhoto.longitude == newPhoto.longitude &&
            oldPhoto.originalName == newPhoto.originalName &&
            oldPhoto.width == newPhoto.width &&
            oldPhoto.height == newPhoto.height &&
            oldPhoto.primary == newPhoto.primary &&
            oldPhoto.sortOrder == newPhoto.sortOrder &&
            oldPhoto.createdAt == newPhoto.createdAt

    override fun getItemViewType(position: Int): Int {
        if (position == 0) return VIEW_TYPE_HEADER
        val actionsPosition = itemCount - 1
        if (position == actionsPosition) return VIEW_TYPE_ACTIONS
        return if (photos.isEmpty()) VIEW_TYPE_EMPTY else VIEW_TYPE_PHOTO
    }

    override fun getItemCount(): Int = 1 + if (photos.isEmpty()) 1 else photos.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_detail_header, parent, false))
            VIEW_TYPE_ACTIONS -> ActionsViewHolder(inflater.inflate(R.layout.item_detail_actions, parent, false))
            VIEW_TYPE_EMPTY -> EmptyViewHolder(inflater.inflate(R.layout.item_detail_empty, parent, false))
            else -> PhotoViewHolder(inflater.inflate(R.layout.item_photo_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(trip, gpsCount, photos.size)
            is ActionsViewHolder -> holder.bind(gpsCount > 0, listener)
            is PhotoViewHolder -> {
                val photo = photos[position - 1]
                holder.bind(photo, position - 1, thumbnailLoader, listener)
            }
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val params = holder.itemView.layoutParams
        if (params is StaggeredGridLayoutManager.LayoutParams) {
            params.isFullSpan = holder.itemViewType != VIEW_TYPE_PHOTO
        }
    }

    private class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val placeText: TextView = itemView.findViewById(R.id.detail_place)
        private val dateText: TextView = itemView.findViewById(R.id.detail_date)
        private val locationText: TextView = itemView.findViewById(R.id.detail_location)
        private val memoText: TextView = itemView.findViewById(R.id.detail_memo)

        fun bind(trip: Trip?, gpsCount: Int, photoCount: Int) {
            if (trip == null) {
                placeText.text = ""
                dateText.text = ""
                locationText.text = ""
                memoText.text = ""
                return
            }
            placeText.text = trip.place
            dateText.text = trip.visitDate
            locationText.text = itemView.context.getString(R.string.photo_location_summary_format, gpsCount, photoCount)
            memoText.text = if (trip.memo.isNullOrBlank()) itemView.context.getString(R.string.detail_memo_empty) else trip.memo!!.trim()
        }
    }

    private class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val photoView: ImageView = itemView.findViewById(R.id.grid_photo)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.grid_photo_progress)
        private val gpsBadge: TextView = itemView.findViewById(R.id.grid_gps_badge)
        private val removeButton: TextView = itemView.findViewById(R.id.remove_photo_button)

        fun bind(photo: TripPhoto, index: Int, thumbnailLoader: ThumbnailLoader, listener: Listener?) {
            val columnWidth = PhotoGridAdapter.calculateColumnWidth(itemView)
            val params = itemView.layoutParams
            params.height = PhotoGridAdapter.calculateTargetHeight(itemView, photo, columnWidth)
            itemView.layoutParams = params

            thumbnailLoader.load(itemView.context, photo.uriString, photoView, progressBar)
            gpsBadge.setText(if (photo.hasLocation()) R.string.gps_available else R.string.gps_missing)
            gpsBadge.setBackgroundResource(if (photo.hasLocation()) R.drawable.bg_badge_available else R.drawable.bg_badge_missing)
            removeButton.visibility = View.GONE
            itemView.setOnClickListener { listener?.onPhotoClicked(photo, index) }
        }
    }

    private class EmptyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private class ActionsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val openMapButton: MaterialButton = itemView.findViewById(R.id.open_map_button)
        private val editButton: MaterialButton = itemView.findViewById(R.id.edit_button)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.delete_button)

        fun bind(mapEnabled: Boolean, listener: Listener?) {
            openMapButton.isEnabled = mapEnabled
            openMapButton.setOnClickListener { listener?.onMapRequested() }
            editButton.setOnClickListener { listener?.onEditRequested() }
            deleteButton.setOnClickListener { listener?.onDeleteRequested() }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_PHOTO = 2
        private const val VIEW_TYPE_EMPTY = 3
        private const val VIEW_TYPE_ACTIONS = 4
    }
}
