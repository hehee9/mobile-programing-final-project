package com.sch.mobile.travelrecord.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sch.mobile.travelrecord.media.ImageStorage
import java.util.HashSet

class TripDbHelper(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    private val appContext: Context = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTravelRecordsTable(db)
        createTripPhotosTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTripPhotosTable(db)
            migrateLegacyPhotos(db)
        }
    }

    @Synchronized
    fun insertTrip(trip: Trip): Long {
        val now = System.currentTimeMillis()
        trip.createdAt = now
        trip.updatedAt = now
        val db = writableDatabase
        val id = db.insertOrThrow(TABLE_TRAVEL_RECORDS, null, toTripValues(trip, true))
        trip.no = id
        return id
    }

    @Synchronized
    fun updateTrip(trip: Trip): Int {
        trip.updatedAt = System.currentTimeMillis()
        val db = writableDatabase
        return db.update(TABLE_TRAVEL_RECORDS, toTripValues(trip, false), "$COL_NO = ?", arrayOf(trip.no.toString()))
    }

    @Synchronized
    fun saveTripWithPhotos(trip: Trip, photos: List<TripPhoto>?): Long {
        val db = writableDatabase
        val obsoletePhotoUris = ArrayList<String>()
        var success = false
        db.beginTransaction()
        try {
            val tripNo: Long
            if (trip.no > 0) {
                obsoletePhotoUris.addAll(collectPhotoUrisForTrip(db, trip.no))
                updateTripInDb(db, trip)
                tripNo = trip.no
            } else {
                val now = System.currentTimeMillis()
                trip.createdAt = now
                trip.updatedAt = now
                tripNo = db.insertOrThrow(TABLE_TRAVEL_RECORDS, null, toTripValues(trip, true))
                trip.no = tripNo
            }

            db.delete(TABLE_TRIP_PHOTOS, "$PHOTO_COL_TRIP_NO = ?", arrayOf(tripNo.toString()))
            photos?.let {
                val now = System.currentTimeMillis()
                for (i in it.indices) {
                    val photo = it[i]
                    photo.tripNo = tripNo
                    photo.sortOrder = i
                    photo.primary = i == 0
                    if (photo.createdAt <= 0) photo.createdAt = now
                    db.insertOrThrow(TABLE_TRIP_PHOTOS, null, toPhotoValues(photo))
                }
            }
            if (obsoletePhotoUris.isNotEmpty()) {
                val nextPhotoUris = HashSet<String>()
                photos?.forEach { photo -> photo.uriString?.let(nextPhotoUris::add) }
                obsoletePhotoUris.removeAll(nextPhotoUris)
            }
            syncTripPrimaryFromPhotos(db, tripNo)
            db.setTransactionSuccessful()
            success = true
            return tripNo
        } finally {
            db.endTransaction()
            if (success) deleteStoredImages(obsoletePhotoUris)
        }
    }

    @Synchronized
    fun deleteTrip(no: Long): Boolean {
        val db = writableDatabase
        val photoUris = collectPhotoUrisForTrip(db, no)
        val deleted = db.delete(TABLE_TRAVEL_RECORDS, "$COL_NO = ?", arrayOf(no.toString())) > 0
        if (deleted) deleteStoredImages(photoUris)
        return deleted
    }

    @Synchronized
    fun deleteAllTrips(): Int {
        val db = writableDatabase
        val photoUris = collectAllPhotoUris(db)
        val count: Int
        db.beginTransaction()
        try {
            db.delete(TABLE_TRIP_PHOTOS, null, null)
            count = db.delete(TABLE_TRAVEL_RECORDS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (count > 0) deleteStoredImages(photoUris)
        return count
    }

    @Synchronized
    fun getTrip(no: Long): Trip? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT r.*, COUNT(p.$PHOTO_COL_ID) AS photo_count, " +
                "SUM(CASE WHEN p.$PHOTO_COL_LATITUDE IS NOT NULL AND p.$PHOTO_COL_LONGITUDE IS NOT NULL THEN 1 ELSE 0 END) AS gps_photo_count " +
                "FROM $TABLE_TRAVEL_RECORDS r " +
                "LEFT JOIN $TABLE_TRIP_PHOTOS p ON p.$PHOTO_COL_TRIP_NO = r.$COL_NO " +
                "WHERE r.$COL_NO = ? GROUP BY r.$COL_NO",
            arrayOf(no.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) fromTripCursor(cursor) else null
        }
    }

    @Synchronized
    fun getAllTrips(sortOrder: TripSortOrder): List<Trip> {
        val orderBy = if (sortOrder == TripSortOrder.OLDEST) {
            "r.$COL_VISIT_DATE ASC, r.$COL_NO ASC"
        } else {
            "r.$COL_VISIT_DATE DESC, r.$COL_NO DESC"
        }
        val trips = ArrayList<Trip>()
        readableDatabase.rawQuery(
            "SELECT r.*, COUNT(p.$PHOTO_COL_ID) AS photo_count, " +
                "SUM(CASE WHEN p.$PHOTO_COL_LATITUDE IS NOT NULL AND p.$PHOTO_COL_LONGITUDE IS NOT NULL THEN 1 ELSE 0 END) AS gps_photo_count " +
                "FROM $TABLE_TRAVEL_RECORDS r " +
                "LEFT JOIN $TABLE_TRIP_PHOTOS p ON p.$PHOTO_COL_TRIP_NO = r.$COL_NO " +
                "GROUP BY r.$COL_NO ORDER BY $orderBy",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) trips.add(fromTripCursor(cursor))
        }
        return trips
    }

    @Synchronized
    fun getTripPhotos(tripNo: Long): List<TripPhoto> {
        val photos = ArrayList<TripPhoto>()
        readableDatabase.query(
            TABLE_TRIP_PHOTOS,
            null,
            "$PHOTO_COL_TRIP_NO = ?",
            arrayOf(tripNo.toString()),
            null,
            null,
            "$PHOTO_COL_SORT_ORDER ASC, $PHOTO_COL_ID ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) photos.add(fromPhotoCursor(cursor))
        }
        return photos
    }

    @Synchronized
    fun getPhotoMarkersWithLocation(): List<PhotoMarker> {
        val markers = ArrayList<PhotoMarker>()
        readableDatabase.rawQuery(
            "SELECT p.$PHOTO_COL_ID, p.$PHOTO_COL_TRIP_NO, p.$PHOTO_COL_URI, " +
                "p.$PHOTO_COL_ORIGINAL_NAME, p.$PHOTO_COL_SORT_ORDER, " +
                "p.$PHOTO_COL_LATITUDE, p.$PHOTO_COL_LONGITUDE, " +
                "r.$COL_PLACE, r.$COL_VISIT_DATE FROM $TABLE_TRIP_PHOTOS p " +
                "JOIN $TABLE_TRAVEL_RECORDS r ON r.$COL_NO = p.$PHOTO_COL_TRIP_NO " +
                "WHERE p.$PHOTO_COL_LATITUDE IS NOT NULL AND p.$PHOTO_COL_LONGITUDE IS NOT NULL " +
                "ORDER BY r.$COL_VISIT_DATE DESC, p.$PHOTO_COL_SORT_ORDER ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                markers.add(
                    PhotoMarker(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getString(7),
                        cursor.getString(8),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getInt(4),
                        cursor.getDouble(5),
                        cursor.getDouble(6),
                    ),
                )
            }
        }
        return markers
    }

    private fun createTravelRecordsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_TRAVEL_RECORDS (" +
                "$COL_NO INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COL_PLACE TEXT NOT NULL, " +
                "$COL_VISIT_DATE TEXT NOT NULL, " +
                "$COL_MEMO TEXT, " +
                "$COL_PHOTO_URI TEXT, " +
                "$COL_LATITUDE REAL, " +
                "$COL_LONGITUDE REAL, " +
                "$COL_CREATED_AT INTEGER NOT NULL, " +
                "$COL_UPDATED_AT INTEGER NOT NULL" +
                ")",
        )
    }

    private fun createTripPhotosTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_TRIP_PHOTOS (" +
                "$PHOTO_COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$PHOTO_COL_TRIP_NO INTEGER NOT NULL, " +
                "$PHOTO_COL_URI TEXT NOT NULL, " +
                "$PHOTO_COL_LATITUDE REAL, " +
                "$PHOTO_COL_LONGITUDE REAL, " +
                "$PHOTO_COL_ORIGINAL_NAME TEXT, " +
                "$PHOTO_COL_WIDTH INTEGER NOT NULL DEFAULT 0, " +
                "$PHOTO_COL_HEIGHT INTEGER NOT NULL DEFAULT 0, " +
                "$PHOTO_COL_IS_PRIMARY INTEGER NOT NULL DEFAULT 0, " +
                "$PHOTO_COL_SORT_ORDER INTEGER NOT NULL DEFAULT 0, " +
                "$PHOTO_COL_CREATED_AT INTEGER NOT NULL, " +
                "FOREIGN KEY($PHOTO_COL_TRIP_NO) REFERENCES $TABLE_TRAVEL_RECORDS($COL_NO) ON DELETE CASCADE" +
                ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_photos_trip_no ON $TABLE_TRIP_PHOTOS($PHOTO_COL_TRIP_NO)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_photos_location ON $TABLE_TRIP_PHOTOS($PHOTO_COL_LATITUDE, $PHOTO_COL_LONGITUDE)")
    }

    private fun migrateLegacyPhotos(db: SQLiteDatabase) {
        db.query(TABLE_TRAVEL_RECORDS, null, "$COL_PHOTO_URI IS NOT NULL AND $COL_PHOTO_URI != ''", null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val trip = fromTripCursor(cursor)
                val photo = TripPhoto(
                    0,
                    trip.no,
                    trip.photoUri,
                    trip.latitude,
                    trip.longitude,
                    "legacy_photo",
                    0,
                    0,
                    true,
                    0,
                    if (trip.createdAt > 0) trip.createdAt else System.currentTimeMillis(),
                )
                db.insert(TABLE_TRIP_PHOTOS, null, toPhotoValues(photo))
            }
        }
    }

    private fun collectPhotoUrisForTrip(db: SQLiteDatabase, tripNo: Long): MutableList<String> {
        val uris = ArrayList<String>()
        db.query(TABLE_TRIP_PHOTOS, arrayOf(PHOTO_COL_URI), "$PHOTO_COL_TRIP_NO = ?", arrayOf(tripNo.toString()), null, null, null).use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(uris::add)
        }
        if (uris.isEmpty()) {
            db.query(
                TABLE_TRAVEL_RECORDS,
                arrayOf(COL_PHOTO_URI),
                "$COL_NO = ? AND $COL_PHOTO_URI IS NOT NULL AND $COL_PHOTO_URI != ''",
                arrayOf(tripNo.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(uris::add)
            }
        }
        return uris
    }

    private fun collectAllPhotoUris(db: SQLiteDatabase): MutableList<String> {
        val uris = ArrayList<String>()
        db.query(TABLE_TRIP_PHOTOS, arrayOf(PHOTO_COL_URI), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(uris::add)
        }
        db.query(TABLE_TRAVEL_RECORDS, arrayOf(COL_PHOTO_URI), "$COL_PHOTO_URI IS NOT NULL AND $COL_PHOTO_URI != ''", null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(uris::add)
        }
        return uris
    }

    private fun deleteStoredImages(photoUris: List<String>?) {
        if (photoUris.isNullOrEmpty()) return
        val uniqueUris = HashSet(photoUris)
        for (uri in uniqueUris) ImageStorage.deleteStoredImage(appContext, uri)
    }

    private fun updateTripInDb(db: SQLiteDatabase, trip: Trip): Int {
        trip.updatedAt = System.currentTimeMillis()
        return db.update(TABLE_TRAVEL_RECORDS, toTripValues(trip, false), "$COL_NO = ?", arrayOf(trip.no.toString()))
    }

    private fun syncTripPrimaryFromPhotos(db: SQLiteDatabase, tripNo: Long) {
        db.query(
            TABLE_TRIP_PHOTOS,
            null,
            "$PHOTO_COL_TRIP_NO = ?",
            arrayOf(tripNo.toString()),
            null,
            null,
            "$PHOTO_COL_IS_PRIMARY DESC, $PHOTO_COL_SORT_ORDER ASC, $PHOTO_COL_ID ASC",
            "1",
        ).use { cursor ->
            val values = ContentValues()
            if (cursor.moveToFirst()) {
                val primary = fromPhotoCursor(cursor)
                values.put(COL_PHOTO_URI, primary.uriString)
                putNullableDouble(values, COL_LATITUDE, primary.latitude)
                putNullableDouble(values, COL_LONGITUDE, primary.longitude)
            } else {
                values.putNull(COL_PHOTO_URI)
                values.putNull(COL_LATITUDE)
                values.putNull(COL_LONGITUDE)
            }
            values.put(COL_UPDATED_AT, System.currentTimeMillis())
            db.update(TABLE_TRAVEL_RECORDS, values, "$COL_NO = ?", arrayOf(tripNo.toString()))
        }
    }

    private fun toTripValues(trip: Trip, includeCreatedAt: Boolean): ContentValues {
        val values = ContentValues()
        values.put(COL_PLACE, safeText(trip.place))
        values.put(COL_VISIT_DATE, safeText(trip.visitDate))
        values.put(COL_MEMO, trip.memo)
        values.put(COL_PHOTO_URI, trip.photoUri)
        putNullableDouble(values, COL_LATITUDE, trip.latitude)
        putNullableDouble(values, COL_LONGITUDE, trip.longitude)
        if (includeCreatedAt) values.put(COL_CREATED_AT, trip.createdAt)
        values.put(COL_UPDATED_AT, trip.updatedAt)
        return values
    }

    private fun toPhotoValues(photo: TripPhoto): ContentValues {
        val values = ContentValues()
        values.put(PHOTO_COL_TRIP_NO, photo.tripNo)
        values.put(PHOTO_COL_URI, photo.uriString)
        putNullableDouble(values, PHOTO_COL_LATITUDE, photo.latitude)
        putNullableDouble(values, PHOTO_COL_LONGITUDE, photo.longitude)
        values.put(PHOTO_COL_ORIGINAL_NAME, photo.originalName)
        values.put(PHOTO_COL_WIDTH, photo.width)
        values.put(PHOTO_COL_HEIGHT, photo.height)
        values.put(PHOTO_COL_IS_PRIMARY, if (photo.primary) 1 else 0)
        values.put(PHOTO_COL_SORT_ORDER, photo.sortOrder)
        values.put(PHOTO_COL_CREATED_AT, if (photo.createdAt <= 0) System.currentTimeMillis() else photo.createdAt)
        return values
    }

    private fun putNullableDouble(values: ContentValues, key: String, value: Double?) {
        if (value == null) values.putNull(key) else values.put(key, value)
    }

    private fun safeText(value: String?): String = value?.trim().orEmpty()

    private fun fromTripCursor(cursor: Cursor): Trip {
        val noIndex = cursor.getColumnIndexOrThrow(COL_NO)
        val placeIndex = cursor.getColumnIndexOrThrow(COL_PLACE)
        val visitDateIndex = cursor.getColumnIndexOrThrow(COL_VISIT_DATE)
        val memoIndex = cursor.getColumnIndexOrThrow(COL_MEMO)
        val photoUriIndex = cursor.getColumnIndexOrThrow(COL_PHOTO_URI)
        val latitudeIndex = cursor.getColumnIndexOrThrow(COL_LATITUDE)
        val longitudeIndex = cursor.getColumnIndexOrThrow(COL_LONGITUDE)
        val createdAtIndex = cursor.getColumnIndexOrThrow(COL_CREATED_AT)
        val updatedAtIndex = cursor.getColumnIndexOrThrow(COL_UPDATED_AT)
        val photoCountIndex = cursor.getColumnIndex("photo_count")
        val gpsPhotoCountIndex = cursor.getColumnIndex("gps_photo_count")
        val latitude = if (cursor.isNull(latitudeIndex)) null else cursor.getDouble(latitudeIndex)
        val longitude = if (cursor.isNull(longitudeIndex)) null else cursor.getDouble(longitudeIndex)
        val photoCount = if (photoCountIndex >= 0 && !cursor.isNull(photoCountIndex)) {
            cursor.getInt(photoCountIndex)
        } else if (cursor.getString(photoUriIndex) == null) {
            0
        } else {
            1
        }
        val gpsPhotoCount = if (gpsPhotoCountIndex >= 0 && !cursor.isNull(gpsPhotoCountIndex)) {
            cursor.getInt(gpsPhotoCountIndex)
        } else if (latitude != null && longitude != null) {
            1
        } else {
            0
        }
        return Trip(
            cursor.getLong(noIndex),
            cursor.getString(placeIndex),
            cursor.getString(visitDateIndex),
            cursor.getString(memoIndex),
            cursor.getString(photoUriIndex),
            latitude,
            longitude,
            photoCount,
            gpsPhotoCount,
            cursor.getLong(createdAtIndex),
            cursor.getLong(updatedAtIndex),
        )
    }

    private fun fromPhotoCursor(cursor: Cursor): TripPhoto {
        val latitudeIndex = cursor.getColumnIndexOrThrow(PHOTO_COL_LATITUDE)
        val longitudeIndex = cursor.getColumnIndexOrThrow(PHOTO_COL_LONGITUDE)
        val latitude = if (cursor.isNull(latitudeIndex)) null else cursor.getDouble(latitudeIndex)
        val longitude = if (cursor.isNull(longitudeIndex)) null else cursor.getDouble(longitudeIndex)
        return TripPhoto(
            cursor.getLong(cursor.getColumnIndexOrThrow(PHOTO_COL_ID)),
            cursor.getLong(cursor.getColumnIndexOrThrow(PHOTO_COL_TRIP_NO)),
            cursor.getString(cursor.getColumnIndexOrThrow(PHOTO_COL_URI)),
            latitude,
            longitude,
            cursor.getString(cursor.getColumnIndexOrThrow(PHOTO_COL_ORIGINAL_NAME)),
            cursor.getInt(cursor.getColumnIndexOrThrow(PHOTO_COL_WIDTH)),
            cursor.getInt(cursor.getColumnIndexOrThrow(PHOTO_COL_HEIGHT)),
            cursor.getInt(cursor.getColumnIndexOrThrow(PHOTO_COL_IS_PRIMARY)) == 1,
            cursor.getInt(cursor.getColumnIndexOrThrow(PHOTO_COL_SORT_ORDER)),
            cursor.getLong(cursor.getColumnIndexOrThrow(PHOTO_COL_CREATED_AT)),
        )
    }

    companion object {
        private const val DB_NAME = "travel_records.db"
        private const val DB_VERSION = 2

        const val TABLE_TRAVEL_RECORDS = "travel_records"
        const val TABLE_TRIP_PHOTOS = "trip_photos"

        const val COL_NO = "no"
        const val COL_PLACE = "place"
        const val COL_VISIT_DATE = "visit_date"
        const val COL_MEMO = "memo"
        const val COL_PHOTO_URI = "photo_uri"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"

        const val PHOTO_COL_ID = "id"
        const val PHOTO_COL_TRIP_NO = "trip_no"
        const val PHOTO_COL_URI = "uri"
        const val PHOTO_COL_LATITUDE = "latitude"
        const val PHOTO_COL_LONGITUDE = "longitude"
        const val PHOTO_COL_ORIGINAL_NAME = "original_name"
        const val PHOTO_COL_WIDTH = "width"
        const val PHOTO_COL_HEIGHT = "height"
        const val PHOTO_COL_IS_PRIMARY = "is_primary"
        const val PHOTO_COL_SORT_ORDER = "sort_order"
        const val PHOTO_COL_CREATED_AT = "created_at"
    }
}
