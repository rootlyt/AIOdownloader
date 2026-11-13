package com.rootlyt.aio

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        if (prefs.getBoolean("busy", false)) prefs.edit().putBoolean("busy", false)
            .putString("status", "Previous operation was interrupted. Retry; unfinished files are cleaned before the next operation.").apply()
    }
}
