package com.sysmonitor.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val PREFS_NAME = "controller_config"
const val KEY_AGENT_PACKAGE = "agent_package"

class ControllerService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private val gson = Gson()
    private var agentPackage = "com.sysmonitor"
    private var commandReceiver: CommandReceiver? = null
    private var resultReceiver: ResultReceiver? = null
    private val pendingCommands = ConcurrentHashMap<String, CommandCallback>()

    interface CommandCallback {
        fun onResult(taskId: String, result: CommandResult)
    }

    data class CommandResult(
        val result: String?,
        val error: String?,
        val status: String
    )

    inner class LocalBinder : Binder() {
        fun getService(): ControllerService = this@ControllerService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ControllerService", "onCreate")
        initializeService()
    }

    private fun initializeService() {
        try {
            loadConfig()
            running = true
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(1, notification)
            registerReceivers()
            Log.d("ControllerService", "Service initialized, agent: $agentPackage")
        } catch (e: Exception) {
            Log.e("ControllerService", "Init error", e)
        }
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        agentPackage = prefs.getString(KEY_AGENT_PACKAGE, "com.sysmonitor") ?: "com.sysmonitor"
    }

    private fun registerReceivers() {
        // Receive results from MonitorService
        resultReceiver = ResultReceiver()
        val resultFilter = IntentFilter("com.sysmonitor.ACTION_RESULT")
        registerReceiver(resultReceiver!!, resultFilter, Context.RECEIVER_NOT_EXPORTED)

        // Receive commands from UI (MainControllerActivity)
        commandReceiver = CommandReceiver()
        val commandFilter = IntentFilter("com.sysmonitor.controller.ACTION_COMMAND")
        registerReceiver(commandReceiver!!, commandFilter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        commandReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        resultReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("controller_service", "SysMonitor Controller", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Controller communication service" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainControllerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "controller_service")
            .setContentTitle("SysMonitor Controller")
            .setContentText("Connected to agent: $agentPackage")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // Send command to MonitorService (async with callback)
    fun sendCommand(capability: String, args: Map<String, Any>, callback: CommandCallback? = null): String {
        val taskId = java.util.UUID.randomUUID().toString()
        callback?.let { pendingCommands[taskId] = it }

        val intent = Intent().apply {
            setClassName(agentPackage, "com.sysmonitor.MonitorService")
            action = "com.sysmonitor.ACTION_COMMAND"
            putExtra("capability", capability)
            putExtra("args", gson.toJson(args))
            putExtra("task_id", taskId)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Log.d("ControllerService", "Sent $capability to $agentPackage ($taskId)")
        } catch (e: Exception) {
            Log.e("ControllerService", "Send failed", e)
            callback?.onResult(taskId, CommandResult(null, e.message ?: "send failed", "failed"))
            pendingCommands.remove(taskId)
        }
        return taskId
    }

    // Send command synchronously with timeout
    fun sendCommandSync(capability: String, args: Map<String, Any>, timeoutMs: Long = 30000): Map<String, Any> {
        val latch = CountDownLatch(1)
        var resultMap: Map<String, Any>? = null

        sendCommand(capability, args, object : CommandCallback {
            override fun onResult(taskId: String, result: CommandResult) {
                resultMap = mapOf(
                    "taskId" to taskId,
                    "result" to result.result ?: "",
                    "error" to result.error ?: "",
                    "status" to result.status
                )
                latch.countDown()
            }
        })

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return mapOf("error" to "timeout", "status" to "timeout")
        }
        return resultMap ?: mapOf("error" to "unknown")
    }

    inner class CommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val capability = intent.getStringExtra("capability") ?: return
            val argsJson = intent.getStringExtra("args") ?: "{}"
            val taskId = intent.getStringExtra("task_id") ?: java.util.UUID.randomUUID().toString()

            // Forward to MonitorService
            val agentIntent = Intent().apply {
                setClassName(agentPackage, "com.sysmonitor.MonitorService")
                action = "com.sysmonitor.ACTION_COMMAND"
                putExtra("capability", capability)
                putExtra("args", argsJson)
                putExtra("task_id", taskId)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(agentIntent) else startService(agentIntent)
                Log.d("ControllerService", "Forwarded $capability to agent ($taskId)")
            } catch (e: Exception) {
                Log.e("ControllerService", "Forward failed", e)
            }
        }
    }

    inner class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val taskId = intent.getStringExtra("task_id") ?: ""
            val result = intent.getStringExtra("result") ?: ""
            val error = intent.getStringExtra("error") ?: ""
            val status = intent.getStringExtra("status") ?: ""

            Log.d("ControllerService", "Result for $taskId: $status")

            // Notify callback
            pendingCommands.remove(taskId)?.onResult(taskId, CommandResult(result, error, status))

            // Broadcast to UI
            val uiIntent = Intent("com.sysmonitor.controller.ACTION_RESULT_UI").apply {
                putExtra("task_id", taskId)
                putExtra("result", result)
                putExtra("error", error)
                putExtra("status", status)
            }
            sendBroadcast(uiIntent)
        }
    }
}