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

    // Paquetes que consideramos "launcher / pantalla de inicio / recientes" del sistema.
    private val launcherPackages = setOf(
        "com.sec.android.app.launcher",   // Samsung One UI launcher
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher"
    )

    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"
    private val KEY_LAST_AUTH_TS = "last_authenticated_at"

    private val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos

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
        Log.d(TAG, "Session monitor started")
    }

    private fun stopSessionMonitor() {
        monitoring = false
        handler.removeCallbacks(monitorRunnable)
        Log.d(TAG, "Session monitor stopped")
    }

    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received RESET_LAST_PACKAGE; clearing lastPackage")
            lastPackage = null
        }
    }

    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received AUTH_SUCCESS; starting session monitor")
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

        // Solo procesamos cambios de estado de ventanas completas
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        // CORRECCIÓN: Filtramos los paquetes fantasma/ruido dentro de una variable booleana,
        // en lugar de usar un "return" directo. Así aseguramos que lastPackage se actualice siempre.
        val isNoisePackage = pkg == "null" || pkg.isBlank() || pkg == "android" || 
                             pkg == "com.android.systemui" || pkg.contains("inputmethod") || 
                             pkg.contains("keyboard") || pkg == packageName

        Log.d(TAG, "onAccessibilityEvent pkg=$pkg last=$lastPackage noise=$isNoisePackage unlocked=${isUnlockedForWhatsApp()}")

        val now = System.currentTimeMillis()
        val lastAuth = getLastAuthTs()
        if (lastAuth > 0 && (now - lastAuth) > UNLOCK_TIMEOUT_MS) {
            resetUnlockState("timeout elapsed on event")
        }

        // Si NO es un paquete de ruido, evaluamos la lógica de bloqueo/salida
        if (!isNoisePackage) {
            when {
                // Caso 1: Estamos interactuando activamente con WhatsApp
                pkg == targetPackage -> {
                    if (isUnlockedForWhatsApp()) {
                        if (!monitoring && getLastAuthTs() > 0L) {
                            startSessionMonitor()
                        }
                    } else {
                        if (lastPackage != packageName) {
                            if ((now - lastShowTime) > 1000L) {
                                lastShowTime = now
                                showLockScreen()
                            }
                        }
                    }
                }

                // Caso 2: Salida explícita al Launcher / Home viniendo de WhatsApp
                pkg in launcherPackages && lastPackage == targetPackage -> {
                    resetUnlockState("user navigated to home/recents from WhatsApp")
                }

                // Caso 3: Apertura de otra aplicación viniendo de WhatsApp
                pkg != targetPackage && pkg !in launcherPackages && lastPackage == targetPackage -> {
                    resetUnlockState("user opened another app ($pkg) from WhatsApp")
                }
            }
        }

        // Se guarda SIEMPRE el paquete actual como referencia para el siguiente evento (evita congelamiento de historial)
        if (pkg != packageName) {
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(resetReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(authReceiver) } catch (e: Exception) {}
        stopSessionMonitor()
    }
}
