package com.whatsapplock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat

class LockService : AccessibilityService() {

    private var whatsappLocked = true
    private var currentPackage = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            if (packageName == "com.whatsapp" && whatsappLocked) {
                whatsappLocked = false
                showLockScreen()
            }

            if (packageName != "com.whatsapp") {
                whatsappLocked = true
            }
        }
    }

    private fun showLockScreen() {
        val intent = Intent(this, LockActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onInterrupt() {}
}
