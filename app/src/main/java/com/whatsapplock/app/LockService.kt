package com.whatsapplock.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class LockService : AccessibilityService() {

    private val TAG = "LockService"
    private val targetPackage = "com.whatsapp"

    private val launcherPackages = setOf(
        "com.sec.android.app.launcher",   // Samsung One UI
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.systemui"            // Barra de tareas / Recientes
    )

    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"
    private val KEY_LAST_AUTH_TS = "last_authenticated_at"

    private val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos de sesión activa

    private var lastPackage: String? = null
    private var lastShowTime: Long = 0L

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isUnlockedForWhatsApp(): Boolean = prefs().getBoolean(KEY_UNLOCKED, false)
    private fun setUnlockedForWhatsApp(value: Boolean) = prefs().edit().putBoolean(KEY_UNLOCKED, value).apply()
    private fun getLastAuthTs(): Long = prefs().getLong(KEY_LAST_AUTH_TS, 0L)
    private fun setLastAuthTs(ts: Long) = prefs().edit().putLong(KEY_LAST_AUTH_TS, ts).apply()
    private fun clearLastAuthTs() = prefs().edit().remove(KEY_LAST_AUTH_TS).apply()

    private fun resetUnlockState(reason: String) {
        Log.d(TAG, "Resetting unlock state. Reason: $reason")
        setUnlockedForWhatsApp(false)
        clearLastAuthTs()
        stopSessionMonitor()
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var monitoring = false
    private val monitorRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                val lastAuth = getLastAuthTs()
                if (lastAuth == 0L) {
                    stopSessionMonitor()
                    return
                }
                if ((now - lastAuth) > UNLOCK_TIMEOUT_MS) {
                    resetUnlockState("safety timeout elapsed")
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
    }

    private fun stopSessionMonitor() {
        monitoring = false
        handler.removeCallbacks(monitorRunnable)
    }

    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastPackage = null
        }
    }

    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ts = intent?.getLongExtra("auth_ts", System.currentTimeMillis()) ?: System.currentTimeMillis()
            setLastAuthTs(ts)
            setUnlockedForWhatsApp(true)
            startSessionMonitor()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(authReceiver, IntentFilter("com.whatsapplock.action.AUTH_SUCCESS"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"))
                registerReceiver(authReceiver, IntentFilter("com.whatsapplock.action.AUTH_SUCCESS"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: "null"

        // Solo nos importan los cambios de estado de ventana (Activities)
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        // Filtro básico para omitir ruido de paquetes vacíos o de nuestra propia pantalla de bloqueo
        if (pkg == "null" || pkg.isBlank() || pkg == packageName) {
            return
        }

        val now = System.currentTimeMillis()
        val lastAuth = getLastAuthTs()
        if (lastAuth > 0 && (now - lastAuth) > UNLOCK_TIMEOUT_MS) {
            resetUnlockState("timeout elapsed on event")
        }

        // --- CLAVE DE LA SOLUCIÓN ---
        // Si el usuario ya está autenticado y el evento ocurre DENTRO de WhatsApp,
        // ignoramos por completo el evento. No recalculamos ni disparamos bloqueos.
        if (pkg == targetPackage && isUnlockedForWhatsApp()) {
            if (!monitoring && getLastAuthTs() > 0L) {
                startSessionMonitor()
            }
            // Guardamos el paquete actual y salimos pacíficamente
            lastPackage = pkg
            return
        }

        // Si no se cumple lo anterior, evaluamos las transiciones de salida/entrada
        when {
            // Caso 1: El usuario entra a WhatsApp desde afuera (y no está desbloqueado)
            pkg == targetPackage -> {
                if (!isUnlockedForWhatsApp()) {
                    if (lastPackage != packageName) {
                        if ((now - lastShowTime) > 1000L) {
                            lastShowTime = now
                            showLockScreen()
                        }
                    }
                }
            }

            // Caso 2: El usuario salió al Launcher viniendo de WhatsApp
            pkg in launcherPackages && lastPackage == targetPackage -> {
                resetUnlockState("user navigated to home/recents from WhatsApp")
            }

            // Caso 3: El usuario abrió OTRA aplicación viniendo de WhatsApp
            pkg != targetPackage && pkg !in launcherPackages && lastPackage == targetPackage -> {
                resetUnlockState("user opened another app ($pkg) from WhatsApp")
            }
        }

        // Siempre actualizamos el historial de navegación al final
        lastPackage = pkg
    }

    private fun showLockScreen() {
        try {
            val intent = Intent(this, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting LockActivity", e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(resetReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(authReceiver) } catch (e: Exception) {}
        stopSessionMonitor()
    }
}
