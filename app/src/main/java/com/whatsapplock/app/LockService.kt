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
    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"
    private val KEY_LAST_AUTH_TS = "last_authenticated_at"
    private val KEY_SUPPRESS_UNTIL = "suppress_until_ms"

    // Timeout para reset automático después de autenticación exitosa (ms)
    private val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos

    private var lastPackage: String? = null
    private var lastShowTime: Long = 0L

    // Supresión temporal después de Cancel (ms epoch)
    private var suppressUntilMs: Long = 0L

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isUnlockedForWhatsApp(): Boolean = prefs().getBoolean(KEY_UNLOCKED, false)
    private fun setUnlockedForWhatsApp(value: Boolean) = prefs().edit().putBoolean(KEY_UNLOCKED, value).apply()
    private fun getLastAuthTs(): Long = prefs().getLong(KEY_LAST_AUTH_TS, 0L)
    private fun setLastAuthTs(ts: Long) = prefs().edit().putLong(KEY_LAST_AUTH_TS, ts).apply()
    private fun clearLastAuthTs() = prefs().edit().remove(KEY_LAST_AUTH_TS).apply()

    private fun setSuppressUntil(untilMs: Long) {
        suppressUntilMs = untilMs
        prefs().edit().putLong(KEY_SUPPRESS_UNTIL, untilMs).apply()
    }
    private fun loadSuppressUntil() {
        suppressUntilMs = prefs().getLong(KEY_SUPPRESS_UNTIL, 0L)
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

    // Receiver para resetear lastPackage cuando LockActivity notifica cancelación
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received RESET_LAST_PACKAGE; clearing lastPackage")
            lastPackage = null

            // Si vienen ms de supresión, aplicarlos
            val suppressMs = intent?.getLongExtra("suppress_ms", 0L) ?: 0L
            if (suppressMs > 0L) {
                val until = System.currentTimeMillis() + suppressMs
                Log.d(TAG, "Applying suppression until $until (+$suppressMs ms)")
                setSuppressUntil(until)
            }
        }
    }

    // Receiver para saber que hubo autenticación exitosa y arrancar monitoreo
    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received AUTH_SUCCESS; starting session monitor")
            // Guardar timestamp si viene en extras (opcional)
            val ts = intent?.getLongExtra("auth_ts", System.currentTimeMillis()) ?: System.currentTimeMillis()
            setLastAuthTs(ts)
            setUnlockedForWhatsApp(true)
            // Limpia supresión si quedara
            setSuppressUntil(0L)
            startSessionMonitor()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // cargar supresión previa si existe
        loadSuppressUntil()
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers (unknown)", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: "null"

        // LOG compacto para debug
        Log.d(TAG, "onAccessibilityEvent type=$eventType pkg=$pkg last=$lastPackage unlocked=${isUnlockedForWhatsApp()} lastAuthTs=${getLastAuthTs()} suppressUntilMs=$suppressUntilMs")

        // PROTECCIÓN: procesar SOLO cambios de estado de ventana (no content changes)
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // ignorar TYPE_WINDOW_CONTENT_CHANGED y otros para evitar re-shows dentro de la misma app
            return
        }

        val now = System.currentTimeMillis()

        // Timeout safety
        val lastAuth = getLastAuthTs()
        if (lastAuth > 0 && (now - lastAuth) > UNLOCK_TIMEOUT_MS) {
            Log.d(TAG, "Unlock timeout elapsed on event; resetting unlocked flag")
            setUnlockedForWhatsApp(false)
            clearLastAuthTs()
            stopSessionMonitor()
        }

        // Lógica central:
        // - Sólo mostrar LockActivity si entramos en WhatsApp desde fuera (lastPackage != targetPackage)
        // - No mostrar por movimientos internos dentro de WhatsApp (p. ej. abrir chat, ajustes)
        if (pkg == targetPackage) {
            // Entramos o estamos en WhatsApp
            Log.d(TAG, "Saw package == targetPackage; unlocked=${isUnlockedForWhatsApp()} last=$lastPackage")
            if (isUnlockedForWhatsApp()) {
                // Si está desbloqueado, arrancar el monitor si aún no lo hace (fallback)
                if (!monitoring && getLastAuthTs() > 0L) {
                    Log.d(TAG, "Unlocked detected on event — starting session monitor (fallback)")
                    startSessionMonitor()
                }
                Log.d(TAG, "Already unlocked for WhatsApp; not showing lock")
            } else {
                // Si NO está desbloqueado, mostrar Lock ONLY if we came from outside WhatsApp
                if (lastPackage == null || lastPackage != targetPackage) {
                    // Debounce y supresión
                    if ((now - lastShowTime) > 1000L) {
                        if (now < suppressUntilMs) {
                            Log.d(TAG, "Suppressed showLockScreen due to recent cancel (until=$suppressUntilMs). Skipping.")
                        } else {
                            lastShowTime = now
                            showLockScreen()
                        }
                    } else {
                        Log.d(TAG, "Debounce: skipping showLockScreen")
                    }
                } else {
                    // lastPackage == targetPackage -> estamos navegando internamente -> no mostrar
                    Log.d(TAG, "Inside WhatsApp navigation detected (lastPackage==target). Skipping showLockScreen.")
                }
            }
        } else {
            // No estamos en WhatsApp
            // Si salimos de WhatsApp, resetear estados si procede
            if (lastPackage == targetPackage && pkg != targetPackage) {
                Log.d(TAG, "Detected exit from WhatsApp on event. Resetting unlocked flag and clearing lastAuthTs.")
                setUnlockedForWhatsApp(false)
                clearLastAuthTs()
                stopSessionMonitor()
            }
        }

        // actualizar lastPackage para próximas comparaciones
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
