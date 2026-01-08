package com.talq2me.baerened

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.talq2me.baerened.ContentUpdateService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, attempting to start content update service")

            try {
                val serviceIntent = Intent(context, ContentUpdateService::class.java)
                context.startService(serviceIntent)
                Log.d(TAG, "Content update service started successfully")
            } catch (e: Exception) {
                // On Android 8.0+ (API 26+), starting background services from BroadcastReceiver
                // is not allowed. This is expected behavior - content will update when app opens.
                Log.w(TAG, "Could not start content update service from background: ${e.message}")
                Log.d(TAG, "Content will be updated when app is next opened")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

