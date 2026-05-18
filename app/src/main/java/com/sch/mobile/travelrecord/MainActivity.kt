package com.sch.mobile.travelrecord

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sch.mobile.travelrecord.data.TripRepository
import com.sch.mobile.travelrecord.data.TripSortOrder
import com.sch.mobile.travelrecord.ui.DetailFragment
import com.sch.mobile.travelrecord.ui.MapFragment
import com.sch.mobile.travelrecord.ui.TripListFragment
import com.sch.mobile.travelrecord.util.SystemBarHelper

class MainActivity : AppCompatActivity() {
    private var currentSortOrder = TripSortOrder.NEWEST
    private var repository: TripRepository? = null
    private var bottomNavigationView: BottomNavigationView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        SystemBarHelper.apply(this, findViewById(R.id.toolbar), findViewById(R.id.bottom_navigation))
        repository = TripRepository(this)
        if (savedInstanceState != null) {
            currentSortOrder = TripSortOrder.valueOf(savedInstanceState.getString(STATE_SORT, TripSortOrder.NEWEST.name)!!)
        }
        setupBottomNavigation()
        supportFragmentManager.addOnBackStackChangedListener(this::syncBottomNavigationSelection)
        if (savedInstanceState == null) showTripListRoot()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SORT, currentSortOrder.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        repository?.close()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sortItem = menu.findItem(R.id.action_sort)
        if (sortItem != null) {
            val nextSortLabel = if (currentSortOrder == TripSortOrder.NEWEST) R.string.sort_oldest else R.string.sort_newest
            sortItem.title = getString(R.string.sort_label_format, getString(nextSortLabel))
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                toggleSortOrder()
                true
            }
            R.id.action_delete_all -> {
                confirmDeleteAll()
                true
            }
            R.id.action_about -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun showTripDetails(tripId: Long) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, DetailFragment.newInstance(tripId))
            .addToBackStack("trip_detail")
            .commit()
    }

    fun showMapForTrip(tripId: Long) {
        bottomNavigationView?.menu?.findItem(R.id.nav_map)?.isChecked = true
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, MapFragment.newInstance(tripId))
            .addToBackStack("trip_map")
            .commit()
    }

    fun openEditor(tripId: Long) {
        val intent = Intent(this, EditTripActivity::class.java)
        intent.putExtra(EditTripActivity.EXTRA_TRIP_ID, tripId)
        startActivity(intent)
    }

    private fun setupBottomNavigation() {
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_records -> {
                    showTripListRoot()
                    true
                }
                R.id.nav_map -> {
                    showMapRoot()
                    true
                }
                else -> false
            }
        }
    }

    private fun showTripListRoot() {
        popToRoot()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, TripListFragment.newInstance(currentSortOrder))
            .commit()
    }

    private fun showMapRoot() {
        popToRoot()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, MapFragment.newInstance())
            .commit()
    }

    private fun popToRoot() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun toggleSortOrder() {
        currentSortOrder = if (currentSortOrder == TripSortOrder.NEWEST) TripSortOrder.OLDEST else TripSortOrder.NEWEST
        invalidateOptionsMenu()
        when (val current = supportFragmentManager.findFragmentById(R.id.fragment_container)) {
            is TripListFragment -> current.setSortOrder(currentSortOrder)
            else -> bottomNavigationView?.setSelectedItemId(R.id.nav_records)
        }
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_all_title)
            .setMessage(R.string.confirm_delete_all_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> deleteAllTrips() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteAllTrips() {
        repository?.deleteAllTrips(
            { _ ->
                Toast.makeText(this, R.string.delete_all_done, Toast.LENGTH_SHORT).show()
                if (bottomNavigationView != null) {
                    bottomNavigationView?.setSelectedItemId(R.id.nav_records)
                } else {
                    showTripListRoot()
                }
            },
            { Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show() },
        )
    }

    private fun syncBottomNavigationSelection() {
        val nav = bottomNavigationView ?: return
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current is MapFragment) {
            nav.menu.findItem(R.id.nav_map).isChecked = true
        } else {
            nav.menu.findItem(R.id.nav_records).isChecked = true
        }
    }

    companion object {
        private const val STATE_SORT = "state_sort"
    }
}
