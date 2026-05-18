package com.sch.mobile.travelrecord.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DateUtils {
    private const val DATE_PATTERN = "yyyy-MM-dd"

    @JvmStatic
    fun today(): String = format(Calendar.getInstance())

    @JvmStatic
    fun format(calendar: Calendar): String = formatter().format(calendar.time)

    @JvmStatic
    fun parseOrToday(value: String?): Calendar {
        val calendar = Calendar.getInstance()
        if (value.isNullOrBlank()) return calendar
        try {
            val date = formatter().parse(value.trim())
            if (date != null) calendar.time = date
        } catch (_: ParseException) {
            // 잘못된 날짜 문자열은 DatePicker 기본값만 오늘로 둔다.
        }
        return calendar
    }

    private fun formatter(): SimpleDateFormat = SimpleDateFormat(DATE_PATTERN, Locale.KOREA).apply {
        isLenient = false
    }
}
