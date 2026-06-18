package com.whatsapplock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class LockService : AccessibilityService() {

    private val TAG = "LockService"
    private var lastPackage: String? = null
    private val targetPackage = "com.whatsapp"
    private var lastShowTime: Long = 0L

    // Timeout para reset automático después de autenticación exitosa (ms)
    private val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos

    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"
    private val KEY_LAST_AUTH_TS = "last_authenticated_at"

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isUnlockedForWhatsApp(): Boolean = prefs().getBoolean(KEY_UNLOCKED, false)
    private fun setUnlockedForWhatsApp(value: Boolean) = prefs().edit().putBoolean(KEY_UNLOCKED, value).apply()
    private fun getLastAuthTs(): Long = prefs().getLong(KEY_LAST_AUTH_TS, 0L)
    private fun clearLastAuthTs() = prefs().edit().remove(KEY_LAST_AUTH_TS).apply()

    // Receiver para resetear lastPackage cuando LockActivity notifica cancelación
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received reset broadcast; clearing lastPackage")
            lastPackage = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"))
            Log.d(TAG, "resetReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register resetReceiver", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: "null"

        Log.d(TAG, "onAccessibilityEvent type=$eventType pkg=$pkg last=$lastPackage unlocked=${isUnlockedForWhatsApp()} lastAuthTs=${getLastAuthTs()}")

        // Manejar tipos compatibles: cambio de ventana o cambios en contenido de la ventana
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val now = System.currentTimeMillis()

        // Safety: si había una autenticación previa y ya pasó el timeout, reseteamos el unlocked para evitar quedarse abierto indefinidamente.
        val lastAuth = getLastAuthTs()
        if (lastAuth > 0 && (now - lastAuth) > UNLOCK_TIMEOUT_MS) {
            Log.d(TAG, "Unlock timeout elapsed; resetting unlocked flag")
            setUnlockedForWhatsApp(false)
            clearLastAuthTs()
        }

        // lógica principal: si vemos com.whatsapp, mostramos LockActivity si corresponde
        // y reseteamos unlocked cuando salimos de com.whatsapp.
        if (pkg == targetPackage) {
            Log.d(TAG, "Saw package == targetPackage; unlocked=${isUnlockedForWhatsApp()} last=$lastPackage")
            // Mostrar lock solo si no está desbloqueado
            if (!isUnlockedForWhatsApp()) {
                // Evitar relanzar cuando la última package fue nuestra propia app
                if (lastPackage != packageName) {
                    // Debounce: evita reentradas rápidas
                    if ((now - lastShowTime) > 1000L) {
                        lastShowTime = now
                        showLockScreen()
                    } else {
                        Log.d(TAG, "Debounce: skipping showLockScreen")
                    }
                } else {
                    Log.d(TAG, "Last package was self; skipping showLockScreen this pass")
                }
            } else {
                Log.d(TAG, "Already unlocked for WhatsApp; not showing lock")
            }
        } else {
            // Si salimos de WhatsApp, reseteamos el flag y limpiamos la marca de tiempo
            if (lastPackage == targetPackage && pkg != targetPackage) {
                Log.d(TAG, "Detected exit from WhatsApp. Resetting unlocked flag and clearing lastAuthTs.")
                setUnlockedForWhatsApp(false)
                clearLastAuthTs()
            }
        }

        lastPackage = pkg
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(resetReceiver)
            Log.d(TAG, "resetReceiver unregistered")
        } catch (e: Exception) {
            // ignore
        }
    }
}
