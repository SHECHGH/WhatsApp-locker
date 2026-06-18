package com.whatsapplock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent

class LockService : AccessibilityService() {

    private var lastPackage: String? = null
    private val targetPackage = "com.whatsapp"

    private fun prefs() = getSharedPreferences("whlock_prefs", Context.MODE_PRIVATE)
    private fun isUnlockedForWhatsApp(): Boolean = prefs().getBoolean("unlocked_whatsapp", false)
    private fun setUnlockedForWhatsApp(value: Boolean) = prefs().edit().putBoolean("unlocked_whatsapp", value).apply()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // Si cambió el package
        if (pkg != lastPackage) {
            // Entrando a WhatsApp
            if (pkg == targetPackage) {
                if (!isUnlockedForWhatsApp()) {
                    showLockScreen()
                }
            }

            // Si salimos de WhatsApp, reseteamos el flag de desbloqueo
            if (lastPackage == targetPackage && pkg != targetPackage) {
                setUnlockedForWhatsApp(false)
            }

            lastPackage = pkg
        }
    }

    private fun showLockScreen() {
        try {
            val intent = Intent(this, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {}
}
