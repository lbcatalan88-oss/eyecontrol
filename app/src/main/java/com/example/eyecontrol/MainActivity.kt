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
                Toast.makeText(this, "沒有相機權限就無法追蹤眼球", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCamera.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        binding.btnCalibrate.setOnClickListener {
            if (!hasCameraPermission()) {
                Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, CalibrationActivity::class.java))
            }
        }
        binding.btnAccessibility.setOnClickListener {
            if (!GazeModel.exists(this)) {
                Toast.makeText(this, "請先完成校正", Toast.LENGTH_SHORT).show()
            } else {
                // 無障礙服務只能由使用者在系統設定中手動開啟
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "請在清單中找到「眼控 EyeControl」並開啟", Toast.LENGTH_LONG).show()
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
        val svc = if (isServiceEnabled()) "✅ 運作中" else "❌ 未開啟"
        binding.statusText.text =
            "狀態：\n  相機權限 $cam\n  眼球校正 $cal\n  眼控服務 $svc"
    }
}
