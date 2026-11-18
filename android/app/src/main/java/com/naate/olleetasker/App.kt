package com.naate.olleetasker

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: use wallpaper-based colours on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
