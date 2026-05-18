package com.sch.mobile.travelrecord.data

class Trip(
    @JvmField var no: Long = 0,
    @JvmField var place: String? = null,
    @JvmField var visitDate: String? = null,
    @JvmField var memo: String? = null,
    @JvmField var photoUri: String? = null,
    @JvmField var latitude: Double? = null,
    @JvmField var longitude: Double? = null,
    @JvmField var photoCount: Int = 0,
    @JvmField var gpsPhotoCount: Int = 0,
    @JvmField var createdAt: Long = 0,
    @JvmField var updatedAt: Long = 0,
) {
    fun hasPhoto(): Boolean = !photoUri.isNullOrBlank()
    fun hasLocation(): Boolean = latitude != null && longitude != null
}
