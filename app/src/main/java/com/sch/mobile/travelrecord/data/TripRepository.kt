package com.sch.mobile.travelrecord.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TripRepository(context: Context) {
    fun interface Callback<T> {
        fun onComplete(result: T)
    }

    fun interface ErrorCallback {
        fun onError(throwable: Throwable)
    }

    private val dbHelper = TripDbHelper(context.applicationContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getAllTrips(sortOrder: TripSortOrder, callback: Callback<List<Trip>>?, errorCallback: ErrorCallback?) {
        runAsync(Callable { dbHelper.getAllTrips(sortOrder) }, callback, errorCallback)
    }

    fun getTrip(no: Long, callback: Callback<Trip?>?, errorCallback: ErrorCallback?) {
        runAsync(Callable { dbHelper.getTrip(no) }, callback, errorCallback)
    }

    fun getTripPhotos(tripNo: Long, callback: Callback<List<TripPhoto>>?, errorCallback: ErrorCallback?) {
        runAsync(Callable { dbHelper.getTripPhotos(tripNo) }, callback, errorCallback)
    }

    fun getPhotoMarkersWithLocation(callback: Callback<List<PhotoMarker>>?, errorCallback: ErrorCallback?) {
        runAsync(Callable { dbHelper.getPhotoMarkersWithLocation() }, callback, errorCallback)
    }

    fun saveTrip(trip: Trip, callback: Callback<Long>?, errorCallback: ErrorCallback?) {
        runAsync(Callable {
            if (trip.no > 0) {
                dbHelper.updateTrip(trip)
                trip.no
            } else {
                dbHelper.insertTrip(trip)
            }
        }, callback, errorCallback)
    }

    fun saveTripWithPhotos(trip: Trip, photos: List<TripPhoto>?, callback: Callback<Long>?, errorCallback: ErrorCallback?) {
        runAsync(Callable { dbHelper.saveTripWithPhotos(trip, photos) }, callback, errorCallback)
    }

    fun deleteTrip(no: Long, callback: Callback<Boolean>?, errorCallback: ErrorCallback?) {
        runAsync(Callable { dbHelper.deleteTrip(no) }, callback, errorCallback)
    }

    fun deleteAllTrips(callback: Callback<Int>?, errorCallback: ErrorCallback?) {
        runAsync(Callable { dbHelper.deleteAllTrips() }, callback, errorCallback)
    }

    fun close() {
        executor.shutdown()
        dbHelper.close()
    }

    private fun <T> runAsync(task: Callable<T>, callback: Callback<T>?, errorCallback: ErrorCallback?) {
        executor.execute {
            try {
                val result = task.call()
                mainHandler.post { callback?.onComplete(result) }
            } catch (throwable: Throwable) {
                mainHandler.post { errorCallback?.onError(throwable) }
            }
        }
    }
}
