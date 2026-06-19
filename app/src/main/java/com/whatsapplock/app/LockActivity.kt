package com.whatsapplock.app

import android.app.ActivityManager
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

    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPinPrompt()
    }

    private fun showPinPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.d(TAG, "Authentication succeeded")
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_UNLOCKED, true).apply()

                // Notificamos al servicio que se desbloqueó correctamente
                val authIntent = Intent("com.whatsapplock.action.AUTH_SUCCESS")
                sendBroadcast(authIntent)

                finish()
            }

            override fun onAuthenticationFailed() {
                Log.d(TAG, "Authentication failed")
                handleAuthFailure()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.d(TAG, "Authentication error $errorCode: $errString")
                handleAuthFailure()
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

    private fun handleAuthFailure() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_UNLOCKED, false).apply()

        // Forzamos el cierre en segundo plano de WhatsApp para asegurar la protección
        killWhatsAppProcess()

        // Notificamos al servicio que limpie referencias anteriores
        val resetIntent = Intent("com.whatsapplock.action.RESET_LAST_PACKAGE")
        sendBroadcast(resetIntent)

        goHome()
    }

    private fun killWhatsAppProcess() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses("com.whatsapp")
            Log.d(TAG, "Requested killBackgroundProcesses for com.whatsapp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill WhatsApp process", e)
        }
    }

    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
