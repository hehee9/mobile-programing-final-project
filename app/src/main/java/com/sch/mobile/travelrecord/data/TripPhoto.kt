package com.sch.mobile.travelrecord.data

import kotlin.math.max
import kotlin.math.min

class TripPhoto(
    @JvmField var id: Long = 0,
    @JvmField var tripNo: Long = 0,
    @JvmField var uriString: String? = null,
    @JvmField var latitude: Double? = null,
    @JvmField var longitude: Double? = null,
    @JvmField var originalName: String? = null,
    @JvmField var width: Int = 0,
    @JvmField var height: Int = 0,
    @JvmField var primary: Boolean = false,
    @JvmField var sortOrder: Int = 0,
    @JvmField var createdAt: Long = 0,
) {
    fun hasLocation(): Boolean = latitude != null && longitude != null

    fun aspectRatio(): Float {
        if (width <= 0 || height <= 0) return 1f
        return max(0.55f, min(1.8f, width / height.toFloat()))
    }
}
