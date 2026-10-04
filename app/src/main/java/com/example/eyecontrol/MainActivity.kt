package com.example.eyecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eyecontrol.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "La cámara es necesaria para seguir la mirada", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("eye_control", MODE_PRIVATE)
        binding.blinkEnabled.isChecked = prefs.getBoolean("deliberate_blink", false)
        binding.blinkEnabled.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("deliberate_blink", enabled).apply()
        }
        binding.dwellTime.setSelection(when (prefs.getLong("dwell_ms", 1000L)) {
            1500L -> 1
            2000L -> 2
            else -> 0
        })
        binding.dwellTime.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putLong("dwell_ms", longArrayOf(1000L, 1500L, 2000L)[position]).apply()
            }
        }
        binding.btnCamera.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        binding.btnCalibrate.setOnClickListener {
            if (!hasCameraPermission()) {
                Toast.makeText(this, "Primero concede el permiso de cámara", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, CalibrationActivity::class.java))
            }
        }
        binding.btnAccessibility.setOnClickListener {
            if (!GazeModel.exists(this)) {
                Toast.makeText(this, "Primero completa la calibración", Toast.LENGTH_SHORT).show()
            } else {
                // 無障礙服務只能由使用者在系統設定中手動開啟
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Activa EyeControl en Accesibilidad → Aplicaciones instaladas", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun refreshStatus() {
        val cam = if (hasCameraPermission()) "✅" else "❌"
        val cal = if (GazeModel.exists(this)) "✅" else "❌"
        val svc = if (isServiceEnabled()) "✅ Activado" else "❌ Desactivado"
        binding.statusText.text =
            "Estado:\n  Cámara $cam\n  Calibración $cal\n  Servicio EyeControl $svc"
    }
}
