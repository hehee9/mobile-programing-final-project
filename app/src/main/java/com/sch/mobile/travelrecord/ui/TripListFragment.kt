package com.sch.mobile.travelrecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sch.mobile.travelrecord.MainActivity
import com.sch.mobile.travelrecord.R
import com.sch.mobile.travelrecord.data.Trip
import com.sch.mobile.travelrecord.data.TripRepository
import com.sch.mobile.travelrecord.data.TripSortOrder

class TripListFragment : Fragment() {
    private var repository: TripRepository? = null
    private var adapter: TripAdapter? = null
    private var sortOrder = TripSortOrder.NEWEST
    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var sortLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = TripRepository(requireContext())
        arguments?.let { args ->
            sortOrder = TripSortOrder.valueOf(args.getString(ARG_SORT, TripSortOrder.NEWEST.name)!!)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_trip_list, container, false)
        progressBar = view.findViewById(R.id.progress)
        emptyView = view.findViewById(R.id.empty_view)
        sortLabel = view.findViewById(R.id.sort_label)
        val recyclerView = view.findViewById<RecyclerView>(R.id.trip_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = TripAdapter(object : TripAdapter.Listener {
            override fun onTripClicked(trip: Trip) {
                (requireActivity() as MainActivity).showTripDetails(trip.no)
            }

            override fun onTripEditRequested(trip: Trip) {
                (requireActivity() as MainActivity).openEditor(trip.no)
            }

            override fun onTripDeleteRequested(trip: Trip) {
                confirmDelete(trip)
            }
        })
        recyclerView.adapter = adapter

        val addButton = view.findViewById<FloatingActionButton>(R.id.add_trip_fab)
        addButton.setOnClickListener { (requireActivity() as MainActivity).openEditor(0) }
        updateSortLabel()
        return view
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
    }

    override fun onDestroyView() {
        adapter?.shutdown()
        adapter = null
        progressBar = null
        emptyView = null
        sortLabel = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        repository?.close()
        super.onDestroy()
    }

    fun setSortOrder(sortOrder: TripSortOrder) {
        this.sortOrder = sortOrder
        updateSortLabel()
        loadTrips()
    }

    fun getSortOrder(): TripSortOrder = sortOrder

    fun refreshData() {
        loadTrips()
    }

    private fun loadTrips() {
        val repo = repository ?: return
        if (adapter == null) return
        // 목록 갱신은 Fragment onResume 한 곳에서 맡겨 Activity와 초기 로딩의 중복 DB 조회를 줄인다.
        setLoading(true)
        repo.getAllTrips(sortOrder, this::renderTrips, this::showError)
    }

    private fun renderTrips(trips: List<Trip>) {
        setLoading(false)
        val currentAdapter = adapter ?: return
        val currentEmptyView = emptyView ?: return
        currentAdapter.setTrips(trips)
        currentEmptyView.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(trip: Trip) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> deleteTrip(trip.no) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteTrip(tripNo: Long) {
        setLoading(true)
        repository?.deleteTrip(tripNo, {
            Toast.makeText(requireContext(), R.string.delete_done, Toast.LENGTH_SHORT).show()
            loadTrips()
        }, this::showError)
    }

    private fun updateSortLabel() {
        val labelView = sortLabel ?: return
        val label = if (sortOrder == TripSortOrder.NEWEST) R.string.sort_newest else R.string.sort_oldest
        labelView.text = getString(R.string.sort_label_format, getString(label))
    }

    private fun setLoading(loading: Boolean) {
        progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(throwable: Throwable) {
        setLoading(false)
        if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_SORT = "sort"

        @JvmStatic
        fun newInstance(sortOrder: TripSortOrder): TripListFragment = TripListFragment().apply {
            arguments = Bundle().apply { putString(ARG_SORT, sortOrder.name) }
        }
    }
}
