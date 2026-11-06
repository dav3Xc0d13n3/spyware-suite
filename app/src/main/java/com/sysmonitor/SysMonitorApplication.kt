package com.sysmonitor

import android.app.Application
import android.content.Intent
import android.util.Log

class SysMonitorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("SysMonitor", "Application starting...")
        
        // Start MonitorService
        val serviceIntent = Intent(this, MonitorService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d("SysMonitor", "MonitorService started")
    }
}