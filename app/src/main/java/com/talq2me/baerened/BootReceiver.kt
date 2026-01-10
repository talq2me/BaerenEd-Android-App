package com.talq2me.baerened

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.talq2me.baerened.ContentUpdateService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed")
            
            // On Android 8.0+ (API 26+), starting background services from BroadcastReceiver
            // is not allowed and will throw IllegalStateException. Content will update when app opens.
            // On Android 12+ (API 31+), restrictions are even stricter.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Android 8.0+ detected - skipping service start from boot receiver")
                Log.d(TAG, "Content will be updated when app is next opened")
                return
            }
            
            // For Android 7.1 and below, we can still try to start the service
            // Wrap in try-catch as an extra safety measure
            try {
                val serviceIntent = Intent(context, ContentUpdateService::class.java)
                context.startService(serviceIntent)
                Log.d(TAG, "Content update service started successfully")
            } catch (e: IllegalStateException) {
                // This can happen if the app is in background on some Android versions
                Log.w(TAG, "Cannot start service from background: ${e.message}")
                Log.d(TAG, "Content will be updated when app is next opened")
            } catch (e: Exception) {
                // Fallback catch for any other unexpected errors
                Log.w(TAG, "Could not start content update service from background: ${e.message}")
                Log.d(TAG, "Content will be updated when app is next opened")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

