package com.whatsapplock.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class LockActivity : Activity() {

    private val TAG = "LockActivity"
    private val PREFS_NAME = "whlock_prefs"
    private val KEY_UNLOCKED = "unlocked_whatsapp"
    private val KEY_LAST_AUTH_TS = "last_authenticated_at"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple UI: diálogo con opciones "Autenticar" (simula éxito) y "Cancelar"
        val dialog = AlertDialog.Builder(this)
            .setTitle("Authenticator")
            .setMessage("Por favor autentícate (simulado).")
            .setPositiveButton("Autenticar") { _, _ ->
                onAuthSucceeded()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                onAuthCancelled()
            }
            .setOnCancelListener {
                onAuthCancelled()
            }
            .create()
        dialog.setCancelable(false)
        dialog.show()
    }

    private fun onAuthSucceeded() {
        try {
            val now = System.currentTimeMillis()
            // Guardar estado en prefs
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_UNLOCKED, true).putLong(KEY_LAST_AUTH_TS, now).apply()

            Log.d(TAG, "Authentication succeeded")
            Toast.makeText(this, "Autenticación OK", Toast.LENGTH_SHORT).show()

            // Enviar broadcast AUTH_SUCCESS con timestamp (garantiza que LockService lo reciba)
            val i = Intent("com.whatsapplock.action.AUTH_SUCCESS")
            i.putExtra("auth_ts", now)
            sendBroadcast(i)
            Log.d(TAG, "AUTH_SUCCESS sent with ts=$now")

        } catch (e: Exception) {
            Log.e(TAG, "Error handling success", e)
        } finally {
            finish()
        }
    }

    private fun onAuthCancelled() {
        try {
            Log.d(TAG, "Authentication cancelled by user — sending RESET_LAST_PACKAGE + suppression")
            Toast.makeText(this, "Autenticación cancelada", Toast.LENGTH_SHORT).show()

            // Enviar RESET_LAST_PACKAGE con un suppression de 2000 ms para evitar re-show inmediato
            val suppressMs = 2000L
            val intent = Intent("com.whatsapplock.action.RESET_LAST_PACKAGE")
            intent.putExtra("suppress_ms", suppressMs)
            sendBroadcast(intent)
            Log.d(TAG, "RESET_LAST_PACKAGE sent with suppress_ms=$suppressMs")

            // Opcional: solicitar killBackgroundProcesses para com.whatsapp (como en tus logs)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses("com.whatsapp")
                Log.d(TAG, "Requested killBackgroundProcesses for com.whatsapp")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to kill background processes", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling cancel", e)
        } finally {
            finish()
        }
    }

    override fun onBackPressed() {
        // Tratar back como cancel
        onAuthCancelled()
    }
}
