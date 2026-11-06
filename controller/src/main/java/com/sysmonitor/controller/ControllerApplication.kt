package com.sysmonitor.controller

import android.app.Application
import android.content.Intent
import android.util.Log

class ControllerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("ControllerApp", "Starting ControllerService")
        val intent = Intent(this, ControllerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}