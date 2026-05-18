package com.sch.mobile.travelrecord.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.sch.mobile.travelrecord.R
import com.sch.mobile.travelrecord.data.TripPhoto
import com.sch.mobile.travelrecord.media.ThumbnailLoader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PhotoGridAdapter(
    private val removable: Boolean,
    private val listener: Listener?,
    private val photoClickListener: PhotoClickListener? = null,
) : RecyclerView.Adapter<PhotoGridAdapter.PhotoViewHolder>() {
    fun interface Listener {
        fun onRemoveRequested(photo: TripPhoto)
    }

    fun interface PhotoClickListener {
        fun onPhotoClicked(photo: TripPhoto)
    }

    private val photos = ArrayList<TripPhoto>()
    private val thumbnailLoader = ThumbnailLoader()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_grid, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = photos[position]
        val columnWidth = calculateColumnWidth(holder.itemView)
        val targetHeight = calculateTargetHeight(holder.itemView, photo, columnWidth)
        val params = holder.itemView.layoutParams
        params.height = targetHeight
        holder.itemView.layoutParams = params

        thumbnailLoader.load(holder.itemView.context, photo.uriString, holder.photoView, holder.progressBar)
        if (photo.hasLocation()) {
            holder.gpsBadge.setText(R.string.gps_available)
            holder.gpsBadge.setBackgroundResource(R.drawable.bg_badge_available)
        } else {
            holder.gpsBadge.setText(R.string.gps_missing)
            holder.gpsBadge.setBackgroundResource(R.drawable.bg_badge_missing)
        }
        holder.removeButton.visibility = if (removable) View.VISIBLE else View.GONE
        holder.removeButton.setOnClickListener { listener?.onRemoveRequested(photo) }
        holder.itemView.setOnClickListener { photoClickListener?.onPhotoClicked(photo) }
    }

    override fun getItemCount(): Int = photos.size

    fun setPhotos(newPhotos: List<TripPhoto>?) {
        val oldPhotos = ArrayList(photos)
        val nextPhotos = if (newPhotos == null) ArrayList() else ArrayList(newPhotos)
        val diffResult = DiffUtil.calculateDiff(PhotoDiffCallback(oldPhotos, nextPhotos))
        photos.clear()
        photos.addAll(nextPhotos)
        diffResult.dispatchUpdatesTo(this)
    }

    fun shutdown() {
        thumbnailLoader.shutdown()
    }

    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val photoView: ImageView = itemView.findViewById(R.id.grid_photo)
        val progressBar: ProgressBar = itemView.findViewById(R.id.grid_photo_progress)
        val gpsBadge: TextView = itemView.findViewById(R.id.grid_gps_badge)
        val removeButton: TextView = itemView.findViewById(R.id.remove_photo_button)
    }

    private class PhotoDiffCallback(private val oldPhotos: List<TripPhoto>, private val newPhotos: List<TripPhoto>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldPhotos.size
        override fun getNewListSize(): Int = newPhotos.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldPhoto = oldPhotos[oldItemPosition]
            val newPhoto = newPhotos[newItemPosition]
            if (oldPhoto.id > 0 && newPhoto.id > 0) return oldPhoto.id == newPhoto.id
            return oldPhoto.uriString == newPhoto.uriString
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldPhoto = oldPhotos[oldItemPosition]
            val newPhoto = newPhotos[newItemPosition]
            return oldPhoto.uriString == newPhoto.uriString &&
                oldPhoto.latitude == newPhoto.latitude &&
                oldPhoto.longitude == newPhoto.longitude &&
                oldPhoto.width == newPhoto.width &&
                oldPhoto.height == newPhoto.height &&
                oldPhoto.sortOrder == newPhoto.sortOrder
        }
    }

    companion object {
        @JvmStatic
        fun calculateColumnWidth(itemView: View): Int {
            val parent = itemView.parent as? View
            var parentWidth = if (parent == null) 0 else parent.width - parent.paddingLeft - parent.paddingRight
            if (parentWidth <= 0) parentWidth = itemView.resources.displayMetrics.widthPixels
            return max(dp(itemView, 120), parentWidth / 2 - dp(itemView, 12))
        }

        @JvmStatic
        fun calculateTargetHeight(itemView: View, photo: TripPhoto?, columnWidth: Int): Int {
            val aspectRatio = photo?.aspectRatio() ?: 1f
            val rawHeight = (columnWidth / max(0.45f, aspectRatio)).roundToInt()
            val minHeight = dp(itemView, 130)
            val maxHeight = dp(itemView, 420)
            return max(minHeight, min(maxHeight, rawHeight))
        }

        @JvmStatic
        fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).roundToInt()
    }
}
