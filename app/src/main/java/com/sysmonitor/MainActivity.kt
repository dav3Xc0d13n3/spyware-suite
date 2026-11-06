package com.sysmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()

        findViewById<Button>(R.id.btnOpenAdmin).setOnClickListener {
            // Require PIN to access admin
            showPinDialog()
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = grantResults.indices.filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }
                .map { permissions[it] }
            if (denied.isNotEmpty()) {
                Toast.makeText(this, "Some permissions denied: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPinDialog() {
        val prefs = getSharedPreferences("admin_config", MODE_PRIVATE)
        val savedPin = prefs.getString("admin_pin", "1234") ?: "1234"

        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter 4-digit PIN"
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setMessage("Enter PIN to access Admin Panel")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val entered = input.text.toString()
                if (entered == savedPin) {
                    startActivity(Intent(this, AdminActivity::class.java))
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}