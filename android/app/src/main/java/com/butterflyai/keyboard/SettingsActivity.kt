package com.butterflyai.keyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var networkService: NetworkService

    private lateinit var etServerUrl: EditText
    private lateinit var btnSaveUrl: Button
    private lateinit var btnTestConnection: Button
    private lateinit var tvTestResult: TextView
    private lateinit var btnEnableIme: Button
    private lateinit var btnSelectIme: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()
        setContentView(R.layout.activity_settings)

        networkService = NetworkService(this)

        etServerUrl = findViewById(R.id.etServerUrl)
        btnSaveUrl = findViewById(R.id.btnSaveUrl)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        tvTestResult = findViewById(R.id.tvTestResult)
        btnEnableIme = findViewById(R.id.btnEnableIme)
        btnSelectIme = findViewById(R.id.btnSelectIme)

        val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        val currentUrl = prefs.getString("server_url", "http://192.168.1.105:8000")
        etServerUrl.setText(currentUrl)

        btnSaveUrl.setOnClickListener {
            val inputUrl = etServerUrl.text.toString().trim()
            if (inputUrl.isNotEmpty() && (inputUrl.startsWith("http://") || inputUrl.startsWith("https://"))) {
                prefs.edit().putString("server_url", inputUrl).apply()
                Toast.makeText(this, "Server URL saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a valid HTTP/HTTPS URL (e.g. http://192.168.1.105:8000)", Toast.LENGTH_LONG).show()
            }
        }

        btnTestConnection.setOnClickListener {
            val inputUrl = etServerUrl.text.toString().trim()
            if (inputUrl.isNotEmpty() && (inputUrl.startsWith("http://") || inputUrl.startsWith("https://"))) {
                prefs.edit().putString("server_url", inputUrl).apply()
            }

            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = "Testing connection to Butterfly AI backend..."
            tvTestResult.setTextColor(getColor(R.color.text_secondary))

            activityScope.launch {
                val result = networkService.testConnection()
                if (result.success) {
                    tvTestResult.text = "CONNECTED! ${result.message}"
                    tvTestResult.setTextColor(getColor(R.color.accent_green))
                } else {
                    tvTestResult.text = "CONNECTION FAILED: ${result.message}"
                    tvTestResult.setTextColor(getColor(R.color.accent_red))
                }
            }
        }

        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSelectIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
