package com.whatsapplock.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPinPrompt()
    }

    private fun showPinPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                finish()
            }
            override fun onAuthenticationFailed() {
                goHome()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                goHome()
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("WhatsApp bloqueado")
            .setSubtitle("Ingresa tu PIN para continuar")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
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
