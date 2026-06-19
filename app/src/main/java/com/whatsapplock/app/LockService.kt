package com.whatsapplock.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class LockService : AccessibilityService() {

    private val TAG = "LockService"
    private val targetPackage = "com.whatsapp"

    // Paquetes que consideramos "launcher / pantalla de inicio / recientes" del sistema.
    // Al no tener filtro en el XML, ahora el servicio SÍ detectará estos paquetes al salir.
    private val launcherPackages = setOf(
        "com.sec.android.app.launcher",   // Samsung One UI launcher
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.systemui"            // Recientes / barra de tareas
    )

    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"

    private var lastPackage: String? = null
    private var lastShowTime: Long = 0L

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isUnlockedForWhatsApp(): Boolean = prefs().getBoolean(KEY_UNLOCKED, false)
    private fun setUnlockedForWhatsApp(value: Boolean) = prefs().edit().putBoolean(KEY_UNLOCKED, value).apply()

    private fun resetUnlockState(reason: String) {
        Log.d(TAG, "Resetting unlock state. Reason: $reason")
        setUnlockedForWhatsApp(false)
    }

    // Receiver para resetear lastPackage cuando LockActivity notifica cancelación
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received RESET_LAST_PACKAGE; clearing lastPackage")
            lastPackage = null
        }
    }

    // Receiver para saber que hubo autenticación exitosa
    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received AUTH_SUCCESS; WhatsApp unlocked")
            setUnlockedForWhatsApp(true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(authReceiver, IntentFilter("com.whatsapplock.action.AUTH_SUCCESS"), Context.RECEIVER_NOT_EXPORTED)
                Log.d(TAG, "Receivers registrados correctamente (Tiramisu+)")
            } else {
                registerReceiver(resetReceiver, IntentFilter("com.whatsapplock.action.RESET_LAST_PACKAGE"))
                registerReceiver(authReceiver, IntentFilter("com.whatsapplock.action.AUTH_SUCCESS"))
                Log.d(TAG, "Receivers registrados correctamente (Legacy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receivers", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: "null"

        // TYPE_WINDOW_STATE_CHANGED es ideal y limpio para detectar cambios entre Apps/Pantallas
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        Log.d(TAG, "onAccessibilityEvent type=$eventType pkg=$pkg last=$lastPackage unlocked=${isUnlockedForWhatsApp()}")

        val now = System.currentTimeMillis()

        when {
            // Caso 1: El usuario intenta entrar o está viendo WhatsApp
            pkg == targetPackage -> {
                if (isUnlockedForWhatsApp()) {
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

            // Caso 2: El usuario salió explícitamente a un launcher / pantalla de inicio / recientes viniendo de WhatsApp
            pkg in launcherPackages && lastPackage == targetPackage -> {
                resetUnlockState("user navigated to home/recents from WhatsApp")
            }

            // Caso 3: El usuario abrió OTRA app viniendo de WhatsApp
            pkg != targetPackage && pkg != packageName && pkg !in launcherPackages && lastPackage == targetPackage -> {
                resetUnlockState("user opened another app ($pkg) from WhatsApp")
            }
        }

        // Guardamos el paquete de referencia siempre que no sea nuestra propia pantalla de bloqueo
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
        try { unregisterReceiver(resetReceiver) } catch (e: Exception) { /* ignore */ }
        try { unregisterReceiver(authReceiver) } catch (e: Exception) { /* ignore */ }
        Log.d(TAG, "Service destroyed, receivers unregistered")
    }
}
