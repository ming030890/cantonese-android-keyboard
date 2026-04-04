package com.example.cantonesevoiceinput

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var modelStatus: TextView

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val messageRes = if (granted) {
            R.string.permission_granted_message
        } else {
            R.string.permission_needed_message
        }
        modelStatus.append("\n\n" + getString(messageRes))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelStatus = findViewById(R.id.tvModelStatus)
        bindButtons()
        renderModelStatus()
    }

    private fun bindButtons() {
        findViewById<Button>(R.id.btnMicPermission).setOnClickListener {
            requestMicPermission()
        }

        findViewById<Button>(R.id.btnOpenImeSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnShowImePicker).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun requestMicPermission() {
        val permissionState = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            modelStatus.append("\n\n" + getString(R.string.permission_granted_message))
            return
        }
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun renderModelStatus() {
        val modelDir = File(filesDir, MODEL_DIR_NAME)
        val bundled = ModelAssetInstaller.hasBundledModel(assets)
        val modelFile = File(modelDir, "model.safetensors")
        val vocabFile = File(modelDir, "vocab.json")
        val mergesFile = File(modelDir, "merges.txt")
        val configFile = File(modelDir, "config.json")
        val weightFormat = ModelAssetInstaller.detectInstalledWeightFormat(modelDir)

        val ready = modelFile.exists() && vocabFile.exists() && mergesFile.exists() && configFile.exists()
        val statusText = when {
            ready -> getString(R.string.status_model_ready)
            bundled -> getString(R.string.status_model_bundled_pending)
            else -> getString(R.string.status_model_assets_missing)
        }

        modelStatus.text = buildString {
            append(statusText)
            append("\n")
            append(getString(R.string.model_dir_label, modelDir.absolutePath))
            append("\n")
            append("assets: ${if (bundled) "bundled" else "missing"}")
            append(" | ")
            append("weights: $weightFormat")
            append(" | ")
            append("safetensors: ${if (modelFile.exists()) "ok" else "missing"}")
            append(" | vocab: ${if (vocabFile.exists()) "ok" else "missing"}")
            append(" | merges: ${if (mergesFile.exists()) "ok" else "missing"}")
            append(" | config: ${if (configFile.exists()) "ok" else "missing"}")
        }
    }

    companion object {
        const val MODEL_DIR_NAME = "models/qwen3-asr-0.6b"
    }
}
