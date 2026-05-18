package com.sch.mobile.travelrecord.data

class PhotoMarker(
    @JvmField val photoId: Long,
    @JvmField val tripNo: Long,
    @JvmField val tripPlace: String?,
    @JvmField val visitDate: String?,
    @JvmField val photoUri: String?,
    @JvmField val originalName: String?,
    @JvmField val sortOrder: Int,
    @JvmField val latitude: Double,
    @JvmField val longitude: Double,
)
