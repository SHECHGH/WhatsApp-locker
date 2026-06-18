package com.whatsapplock.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class LockActivity : AppCompatActivity() {

    private val TAG = "LockActivity"
    private val REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No es necesario setContentView si solo usamos BiometricPrompt
        showPinPrompt()
    }

    private fun showPinPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.d(TAG, "Authentication succeeded")
                val prefs = getSharedPreferences("whlock_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("unlocked_whatsapp", true).apply()
                finish()
            }

            override fun onAuthenticationFailed() {
                Log.d(TAG, "Authentication failed")
                val prefs = getSharedPreferences("whlock_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("unlocked_whatsapp", false).apply()
                goHome()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.d(TAG, "Authentication error $errorCode: $errString")
                val prefs = getSharedPreferences("whlock_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("unlocked_whatsapp", false).apply()
                goHome()
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("WhatsApp bloqueado")
            .setSubtitle("Ingresa tu PIN para continuar")
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(info)
    }

    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
