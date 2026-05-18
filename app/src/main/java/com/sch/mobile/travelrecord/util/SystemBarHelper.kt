package com.sch.mobile.travelrecord.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

object SystemBarHelper {
    @JvmStatic
    fun apply(activity: Activity, topView: View?, bottomView: View?) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        val nightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = !nightMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }

        val topBasePaddingLeft = topView?.paddingLeft ?: 0
        val topBasePaddingTop = topView?.paddingTop ?: 0
        val topBasePaddingRight = topView?.paddingRight ?: 0
        val topBasePaddingBottom = topView?.paddingBottom ?: 0
        val bottomBasePaddingLeft = bottomView?.paddingLeft ?: 0
        val bottomBasePaddingTop = bottomView?.paddingTop ?: 0
        val bottomBasePaddingRight = bottomView?.paddingRight ?: 0
        val bottomBasePaddingBottom = bottomView?.paddingBottom ?: 0

        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topView?.setPadding(topBasePaddingLeft, topBasePaddingTop + systemBars.top, topBasePaddingRight, topBasePaddingBottom)
            bottomView?.setPadding(bottomBasePaddingLeft, bottomBasePaddingTop, bottomBasePaddingRight, bottomBasePaddingBottom + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
