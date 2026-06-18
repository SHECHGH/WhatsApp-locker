package com.whatsapplock.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnActivar = findViewById<Button>(R.id.btnActivar)
        val txtEstado = findViewById<TextView>(R.id.txtEstado)

        if (isAccessibilityEnabled()) {
            txtEstado.text = "✅ Servicio activo"
        } else {
            txtEstado.text = "❌ Servicio inactivo"
        }

        btnActivar.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${LockService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.contains(service) == true
    }
}
