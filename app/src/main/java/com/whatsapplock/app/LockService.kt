package com.whatsapplock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
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
            Log.d(TAG, "Received RESET_LAST_PACKAGE; clearing lastPackage")
            lastPackage = null
        }
    }

    // Receiver para saber que hubo autenticación exitosa y arrancar monitoreo
    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received AUTH_SUCCESS; starting session monitor")
            startSessionMonitor()
        }
    }

    // Handler + Runnable para monitor periodic (usa rootInActiveWindow)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var monitoring = false
    private val monitorRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                val lastAuth = getLastAuthTs()

                // Si no hay autenticación registrada, desactivar monitor
                if (lastAuth == 0L) {
                    stopSessionMonitor()
                    return
                }

                // Safety timeout
                if ((now - lastAuth) > UNLOCK_TIMEOUT_MS) {
                    Log.d(TAG, "Session monitor: timeout elapsed; resetting unlocked")
                    setUnlockedForWhatsApp(false)
                    clearLastAuthTs()
                    stopSessionMonitor()
                    return
                }

                // Intentar obtener el paquete de la ventana activa
                val root = rootInActiveWindow
                val currentPkg = root?.packageName?.toString() ?: "null"

                Log.d(TAG, "Session monitor: activePkg=$currentPkg target=$targetPackage")

                if (currentPkg != targetPackage) {
                    // El usuario ya no está en WhatsApp -> reset
                    Log.d(TAG, "Session monitor: user left WhatsApp; resetting unlocked")
                    setUnlockedForWhatsApp(false)
                    clearLastAuthTs()
                    lastPackage = currentPkg
                    stopSessionMonitor()
                    return
                }

            } catch (e: Exception) {
                Log.e(TAG, "Session monitor error", e)
            } finally {
                if (monitoring) {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    private fun startSessionMonitor() {
        if (monitoring) return
        monitoring = true
        handler.post(monitorRunnable)
        Log.d(TAG, "Session monitor started")
    }

    private fun stopSessionMonitor() {
        monitoring = false
        handler.removeCallbacks(monitorRunnable)
        Log.d(TAG, "Session monitor stopped")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            // Android 13+ requiere indicar si el receiver será expuesto o no.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(authReceiver, IntentFilter("com.whatsapplock.action.AUTH_SUCCESS"), Context.RECEIVER_NOT_EXPORTED)
                Log.d(TAG, "resetReceiver & authReceiver registered (RECEIVER_NOT_EXPORTED)")
            } else {
                registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"))
                registerReceiver(authReceiver, IntentFilter("com.whatsapplock.action.AUTH_SUCCESS"))
                Log.d(TAG, "resetReceiver & authReceiver registered (legacy)")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to register receivers", e)
            // Fallback: no podemos registrar; seguir sin receivers (evita crash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers (unknown)", e)
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
            Log.d(TAG, "Unlock timeout elapsed on event; resetting unlocked flag")
            setUnlockedForWhatsApp(false)
            clearLastAuthTs()
            stopSessionMonitor()
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
                Log.d(TAG, "Detected exit from WhatsApp on event. Resetting unlocked flag and clearing lastAuthTs.")
                setUnlockedForWhatsApp(false)
                clearLastAuthTs()
                stopSessionMonitor()
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
        } catch (e: Exception) { /* ignore */ }
        try {
            unregisterReceiver(authReceiver)
        } catch (e: Exception) { /* ignore */ }
        stopSessionMonitor()
        Log.d(TAG, "receivers unregistered and monitor stopped")
    }
}
