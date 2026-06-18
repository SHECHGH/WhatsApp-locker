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
    // Si el usuario llega a uno de estos, consideramos que SALIÓ de verdad de WhatsApp.
    private val launcherPackages = setOf(
        "com.sec.android.app.launcher",   // Samsung One UI launcher
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.systemui"            // recientes / barra de tareas
    )

    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"
    private val KEY_LAST_AUTH_TS = "last_authenticated_at"

    // Timeout para reset automático después de autenticación exitosa (ms)
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

    // Handler + Runnable: ya NO decide salidas por "ausencia detectada N veces".
    // Solo se encarga del timeout de seguridad (5 min) por si el usuario deja
    // WhatsApp abierto y desbloqueado mucho tiempo sin que lleguen eventos.
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
                Log.d(TAG, "resetReceiver & authReceiver registered (RECEIVER_NOT_EXPORTED)")
            } else {
                registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"))
                registerReceiver(authReceiver, IntentFilter("com.whatsapplock.action.AUTH_SUCCESS"))
                Log.d(TAG, "resetReceiver & authReceiver registered (legacy)")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to register receivers", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers (unknown)", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: "null"

        Log.d(TAG, "onAccessibilityEvent type=$eventType pkg=$pkg last=$lastPackage unlocked=${isUnlockedForWhatsApp()} lastAuthTs=${getLastAuthTs()}")

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val now = System.currentTimeMillis()

        // Safety: timeout de 5 min por si quedó abierto desbloqueado
        val lastAuth = getLastAuthTs()
        if (lastAuth > 0 && (now - lastAuth) > UNLOCK_TIMEOUT_MS) {
            resetUnlockState("timeout elapsed on event")
        }

        when {
            // Caso 1: estamos viendo WhatsApp
            pkg == targetPackage -> {
                Log.d(TAG, "Saw package == targetPackage; unlocked=${isUnlockedForWhatsApp()} last=$lastPackage")
                if (isUnlockedForWhatsApp()) {
                    if (!monitoring && getLastAuthTs() > 0L) {
                        startSessionMonitor()
                    }
                    Log.d(TAG, "Already unlocked for WhatsApp; not showing lock")
                } else {
                    if (lastPackage != packageName) {
                        if ((now - lastShowTime) > 1000L) {
                            lastShowTime = now
                            showLockScreen()
                        } else {
                            Log.d(TAG, "Debounce: skipping showLockScreen")
                        }
                    } else {
                        Log.d(TAG, "Last package was self; skipping showLockScreen this pass")
                    }
                }
            }

            // Caso 2: el usuario llegó explícitamente a un launcher / pantalla de inicio / recientes
            // viniendo de WhatsApp -> esto SÍ es una salida real, resetea sin importar el tiempo.
            pkg in launcherPackages && lastPackage == targetPackage -> {
                resetUnlockState("user navigated to home/recents from WhatsApp")
            }

            // Caso 3: el usuario abrió OTRA app (distinta a WhatsApp, a nuestra propia app,
            // y distinta a un launcher ya cubierto arriba) viniendo de WhatsApp -> también es salida real.
            pkg != targetPackage && pkg != packageName && pkg !in launcherPackages && lastPackage == targetPackage -> {
                resetUnlockState("user opened another app ($pkg) from WhatsApp")
            }

            // Caso 4 (implícito): cualquier otro evento -- por ejemplo, lecturas "null" transitorias,
            // o eventos de nuestra propia LockActivity -- no hacen nada. Esto es lo que evita
            // que se vuelva a pedir el PIN al saltar entre chats, Estados, Ajustes, etc.
        }

        // Solo actualizamos lastPackage cuando el evento es "real" (no nuestra propia pantalla de bloqueo,
        // para no perder de referencia que "veníamos de WhatsApp" mientras el PIN está en pantalla).
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
