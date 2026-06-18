package com.whatsapplock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class LockService : AccessibilityService() {

    private val TAG = "LockService"
    private var lastPackage: String? = null
    private val targetPackage = "com.whatsapp"

    private fun prefs() = getSharedPreferences("whlock_prefs", Context.MODE_PRIVATE)
    private fun isUnlockedForWhatsApp(): Boolean = prefs().getBoolean("unlocked_whatsapp", false)
    private fun setUnlockedForWhatsApp(value: Boolean) = prefs().edit().putBoolean("unlocked_whatsapp", value).apply()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: "null"
        val cls = event.className?.toString() ?: "null"
        val title = event.contentDescription?.toString() ?: (event.text?.joinToString() ?: "")

        Log.d(TAG, "onAccessibilityEvent type=$eventType pkg=$pkg cls=$cls title=$title last=$lastPackage unlocked=${isUnlockedForWhatsApp()}")

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventType != AccessibilityEvent.TYPE_WINDOW_CHANGED) {
            return
        }

        if (pkg != lastPackage) {
            Log.d(TAG, "package changed: $lastPackage -> $pkg")

            if (pkg == targetPackage) {
                Log.d(TAG, "Detected entry to WhatsApp; unlocked=${isUnlockedForWhatsApp()}")
                if (!isUnlockedForWhatsApp()) {
                    showLockScreen()
                } else {
                    Log.d(TAG, "Already unlocked for this WhatsApp session.")
                }
            }

            if (lastPackage == targetPackage && pkg != targetPackage) {
                Log.d(TAG, "Detected exit from WhatsApp. Resetting unlocked flag.")
                setUnlockedForWhatsApp(false)
            }

            lastPackage = pkg
        }
    }

    private fun showLockScreen() {
        try {
            Log.d(TAG, "showLockScreen() starting LockActivity")
            val intent = Intent(this, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting LockActivity", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }
}
