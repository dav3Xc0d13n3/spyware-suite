package com.sysmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            val code = intent.data?.schemeSpecificPart
            if (code == "2366") {
                Log.d("SecretCode", "Launching admin")
                val adminIntent = Intent(context, AdminActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(adminIntent)
            }
        }
    }
}