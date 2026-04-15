package com.talq2me.baerened

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed")
            
            Log.d(TAG, "No boot-time content updater. GitHub game/video JSON is fetched on demand.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

