package com.sch.mobile.travelrecord.ui

import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.sch.mobile.travelrecord.R
import com.sch.mobile.travelrecord.data.Trip
import com.sch.mobile.travelrecord.media.ThumbnailLoader

class TripAdapter(private val listener: Listener) : RecyclerView.Adapter<TripAdapter.TripViewHolder>() {
    interface Listener {
        fun onTripClicked(trip: Trip)
        fun onTripEditRequested(trip: Trip)
        fun onTripDeleteRequested(trip: Trip)
    }

    private val trips = ArrayList<Trip>()
    private val thumbnailLoader = ThumbnailLoader()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false)
        return TripViewHolder(view)
    }

    override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
        val trip = trips[position]
        holder.placeText.text = trip.place
        holder.dateText.text = trip.visitDate
        holder.memoText.text = if (trip.memo.isNullOrBlank()) "메모 없음" else trip.memo!!.trim()

        if (trip.gpsPhotoCount > 0) {
            holder.gpsBadge.text = if (trip.photoCount > 1) {
                holder.itemView.context.getString(R.string.photo_location_summary_format, trip.gpsPhotoCount, trip.photoCount)
            } else {
                holder.itemView.context.getString(R.string.gps_available)
            }
            holder.gpsBadge.setBackgroundResource(R.drawable.bg_badge_available)
        } else {
            holder.gpsBadge.text = if (trip.photoCount > 0) {
                holder.itemView.context.getString(R.string.photo_location_summary_format, 0, trip.photoCount)
            } else {
                holder.itemView.context.getString(R.string.gps_missing)
            }
            holder.gpsBadge.setBackgroundResource(R.drawable.bg_badge_missing)
        }

        thumbnailLoader.load(holder.itemView.context, trip.photoUri, holder.thumbnail, holder.imageProgress)
        holder.itemView.setOnClickListener { listener.onTripClicked(trip) }
        holder.itemView.isLongClickable = true
        holder.itemView.setOnCreateContextMenuListener { menu: ContextMenu, _: View, _: ContextMenu.ContextMenuInfo? ->
            menu.setHeaderTitle(trip.place)
            menu.add(R.string.action_edit).setOnMenuItemClickListener {
                listener.onTripEditRequested(trip)
                true
            }
            menu.add(R.string.action_delete).setOnMenuItemClickListener {
                listener.onTripDeleteRequested(trip)
                true
            }
        }
    }

    override fun getItemCount(): Int = trips.size

    fun setTrips(newTrips: List<Trip>?) {
        val oldTrips = ArrayList(trips)
        val nextTrips = if (newTrips == null) ArrayList() else ArrayList(newTrips)
        val diffResult = DiffUtil.calculateDiff(TripDiffCallback(oldTrips, nextTrips))
        trips.clear()
        trips.addAll(nextTrips)
        diffResult.dispatchUpdatesTo(this)
    }

    fun shutdown() {
        thumbnailLoader.shutdown()
    }

    class TripViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.trip_thumbnail)
        val imageProgress: ProgressBar = itemView.findViewById(R.id.image_progress)
        val placeText: TextView = itemView.findViewById(R.id.place_text)
        val dateText: TextView = itemView.findViewById(R.id.date_text)
        val memoText: TextView = itemView.findViewById(R.id.memo_text)
        val gpsBadge: TextView = itemView.findViewById(R.id.gps_badge)
    }

    private class TripDiffCallback(private val oldTrips: List<Trip>, private val newTrips: List<Trip>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldTrips.size
        override fun getNewListSize(): Int = newTrips.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = oldTrips[oldItemPosition].no == newTrips[newItemPosition].no

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldTrip = oldTrips[oldItemPosition]
            val newTrip = newTrips[newItemPosition]
            return oldTrip.no == newTrip.no &&
                oldTrip.place == newTrip.place &&
                oldTrip.visitDate == newTrip.visitDate &&
                oldTrip.memo == newTrip.memo &&
                oldTrip.photoUri == newTrip.photoUri &&
                oldTrip.latitude == newTrip.latitude &&
                oldTrip.longitude == newTrip.longitude &&
                oldTrip.photoCount == newTrip.photoCount &&
                oldTrip.gpsPhotoCount == newTrip.gpsPhotoCount &&
                oldTrip.updatedAt == newTrip.updatedAt
        }
    }
}
