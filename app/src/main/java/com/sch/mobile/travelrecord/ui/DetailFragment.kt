package com.sch.mobile.travelrecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.sch.mobile.travelrecord.MainActivity
import com.sch.mobile.travelrecord.PhotoViewerActivity
import com.sch.mobile.travelrecord.R
import com.sch.mobile.travelrecord.data.Trip
import com.sch.mobile.travelrecord.data.TripPhoto
import com.sch.mobile.travelrecord.data.TripRepository

class DetailFragment : Fragment(), DetailContentAdapter.Listener {
    private var tripId: Long = 0
    private var repository: TripRepository? = null
    private var currentTrip: Trip? = null
    private var detailAdapter: DetailContentAdapter? = null
    private val currentPhotos = ArrayList<TripPhoto>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tripId = arguments?.getLong(ARG_TRIP_ID, 0) ?: 0
        repository = TripRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_detail, container, false)
        setupDetailList(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        loadTrip()
    }

    override fun onDestroyView() {
        detailAdapter?.shutdown()
        detailAdapter = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        repository?.close()
        super.onDestroy()
    }

    private fun setupDetailList(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.detail_recycler_view)
        recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        recyclerView.setHasFixedSize(false)
        detailAdapter = DetailContentAdapter(this)
        recyclerView.adapter = detailAdapter
    }

    private fun loadTrip() {
        val repo = repository ?: return
        if (tripId <= 0) return
        repo.getTrip(tripId, { trip ->
            if (!isAdded) return@getTrip
            if (trip == null) {
                requireActivity().supportFragmentManager.popBackStack()
                return@getTrip
            }
            currentTrip = trip
            repo.getTripPhotos(tripId, { photos ->
                if (!isAdded || view == null) return@getTripPhotos
                currentPhotos.clear()
                currentPhotos.addAll(photos)
                renderContent()
            }, this::showError)
        }, this::showError)
    }

    private fun renderContent() {
        detailAdapter?.setData(currentTrip, currentPhotos)
    }

    override fun onMapRequested() {
        currentTrip?.let { (requireActivity() as MainActivity).showMapForTrip(it.no) }
    }

    override fun onEditRequested() {
        currentTrip?.let { (requireActivity() as MainActivity).openEditor(it.no) }
    }

    override fun onDeleteRequested() {
        if (currentTrip != null) confirmDelete()
    }

    override fun onPhotoClicked(photo: TripPhoto, index: Int) {
        PhotoViewerActivity.open(requireContext(), currentPhotos, index)
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> deleteCurrentTrip() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteCurrentTrip() {
        val trip = currentTrip ?: return
        repository?.deleteTrip(trip.no, {
            Toast.makeText(requireContext(), R.string.delete_done, Toast.LENGTH_SHORT).show()
            requireActivity().supportFragmentManager.popBackStack()
        }, this::showError)
    }

    private fun showError(throwable: Throwable) {
        if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_TRIP_ID = "trip_id"

        @JvmStatic
        fun newInstance(tripId: Long): DetailFragment = DetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TRIP_ID, tripId) }
        }
    }
}
