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

    // Timeout para reset automático después de autenticación exitosa (ms)
    private val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos

    private var lastPackage: String? = null
    private var lastShowTime: Long = 0L

    // Contador de lecturas consecutivas "fuera de WhatsApp" para evitar falsos positivos
    private var awayCount = 0
    private val AWAY_THRESHOLD = 3

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isUnlockedForWhatsApp(): Boolean = prefs().getBoolean(KEY_UNLOCKED, false)
    private fun setUnlockedForWhatsApp(value: Boolean) = prefs().edit().putBoolean(KEY_UNLOCKED, value).apply()
    private fun getLastAuthTs(): Long = prefs().getLong(KEY_LAST_AUTH_TS, 0L)
    private fun setLastAuthTs(ts: Long) = prefs().edit().putLong(KEY_LAST_AUTH_TS, ts).apply()
    private fun clearLastAuthTs() = prefs().edit().remove(KEY_LAST_AUTH_TS).apply()

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

                Log.d(TAG, "Session monitor: activePkg=$currentPkg target=$targetPackage awayCount=$awayCount")

                // Solo contamos como "fuera" si el paquete es real y distinto al target.
                // Ignoramos lecturas "null" (transitorias durante animaciones/cambios de pantalla).
                if (currentPkg != targetPackage && currentPkg != "null") {
                    awayCount++
                    Log.d(TAG, "Session monitor: away from WhatsApp, count=$awayCount")
                    if (awayCount >= AWAY_THRESHOLD) {
                        Log.d(TAG, "Session monitor: confirmed user left WhatsApp; resetting unlocked")
                        setUnlockedForWhatsApp(false)
                        clearLastAuthTs()
                        lastPackage = currentPkg
                        awayCount = 0
                        stopSessionMonitor()
                        return
                    }
                } else {
                    // Estamos de vuelta en WhatsApp (o lectura transitoria null) -> reset del contador
                    awayCount = 0
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
        awayCount = 0
        handler.post(monitorRunnable)
        Log.d(TAG, "Session monitor started")
    }

    private fun stopSessionMonitor() {
        monitoring = false
        awayCount = 0
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
            // Guardar timestamp si viene en extras (opcional)
            val ts = intent?.getLongExtra("auth_ts", System.currentTimeMillis()) ?: System.currentTimeMillis()
            setLastAuthTs(ts)
            setUnlockedForWhatsApp(true)
            startSessionMonitor()
        }
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

        // lógica principal: si vemos com.whatsapp, mostramos LockActivity si corresponde.
        // NOTA: el reset de "unlocked" cuando se sale de WhatsApp ya NO se hace aquí;
        // es responsabilidad exclusiva del monitorRunnable (que filtra falsos positivos
        // con AWAY_THRESHOLD), para evitar dobles disparos y bloqueos prematuros
        // al navegar entre pantallas internas de WhatsApp (chats, Estados, Ajustes, etc).
        if (pkg == targetPackage) {
            Log.d(TAG, "Saw package == targetPackage; unlocked=${isUnlockedForWhatsApp()} last=$lastPackage")
            // Si está desbloqueado, arrancar monitor si no se está ejecutando (fallback robusto)
            if (isUnlockedForWhatsApp()) {
                if (!monitoring && getLastAuthTs() > 0L) {
                    Log.d(TAG, "Unlocked detected on event — starting session monitor (fallback)")
                    startSessionMonitor()
                }
                Log.d(TAG, "Already unlocked for WhatsApp; not showing lock")
            } else {
                // Mostrar lock solo si no está desbloqueado
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
