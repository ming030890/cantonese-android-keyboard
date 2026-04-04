package com.example.cantonesevoiceinput

import android.Manifest
import android.content.ComponentName
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

class MainActivity : AppCompatActivity() {
    private lateinit var setupHeadline: TextView
    private lateinit var setupBody: TextView
    private lateinit var micPermissionButton: Button
    private lateinit var openImeSettingsButton: Button
    private lateinit var showImePickerButton: Button

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        renderSetupState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupHeadline = findViewById(R.id.tvSetupHeadline)
        setupBody = findViewById(R.id.tvSetupBody)
        micPermissionButton = findViewById(R.id.btnMicPermission)
        openImeSettingsButton = findViewById(R.id.btnOpenImeSettings)
        showImePickerButton = findViewById(R.id.btnShowImePicker)
        bindButtons()
        renderSetupState()
    }

    override fun onResume() {
        super.onResume()
        renderSetupState()
    }

    private fun bindButtons() {
        micPermissionButton.setOnClickListener {
            requestMicPermission()
        }

        openImeSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        showImePickerButton.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun requestMicPermission() {
        val permissionState = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            renderSetupState()
            return
        }
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun renderSetupState() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val imeEnabled = isImeEnabled()
        val imeSelected = isImeSelected()

        val headlineRes: Int
        val bodyRes: Int

        when {
            hasPermission && imeEnabled && imeSelected -> {
                headlineRes = R.string.setup_ready_title
                bodyRes = R.string.setup_ready_body
            }

            hasPermission && imeEnabled -> {
                headlineRes = R.string.setup_picker_title
                bodyRes = R.string.setup_picker_body
            }

            hasPermission -> {
                headlineRes = R.string.setup_enable_title
                bodyRes = R.string.setup_enable_body
            }

            else -> {
                headlineRes = R.string.setup_permission_title
                bodyRes = R.string.setup_permission_body
            }
        }

        setupHeadline.setText(headlineRes)
        setupBody.setText(bodyRes)

        micPermissionButton.text = getString(
            if (hasPermission) R.string.grant_mic_permission_done else R.string.grant_mic_permission
        )
        micPermissionButton.isEnabled = !hasPermission
        micPermissionButton.alpha = if (hasPermission) 0.65f else 1f

        openImeSettingsButton.text = getString(
            if (imeEnabled) R.string.open_ime_settings_done else R.string.open_ime_settings
        )

        showImePickerButton.text = getString(
            if (imeSelected) R.string.show_ime_picker_done else R.string.show_ime_picker
        )
        showImePickerButton.isEnabled = imeEnabled
        showImePickerButton.alpha = if (imeEnabled) 1f else 0.65f
    }

    private fun isImeEnabled(): Boolean {
        val enabledInputMethods = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: return false

        val ime = ComponentName(this, VoiceImeService::class.java)
        val candidates = setOf(ime.flattenToString(), ime.flattenToShortString())
        return enabledInputMethods
            .split(':')
            .any { it in candidates }
    }

    private fun isImeSelected(): Boolean {
        val selected = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return false

        val ime = ComponentName(this, VoiceImeService::class.java)
        return selected == ime.flattenToString() || selected == ime.flattenToShortString()
    }

    companion object {
        const val MODEL_DIR_NAME = "models/qwen3-asr-0.6b"
    }
}
