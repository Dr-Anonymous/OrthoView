package life.ortho.ortholink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.os.Build
import android.provider.Settings

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                
                if (!incomingNumber.isNullOrEmpty()) {
                    startOverlayService(context, incomingNumber)
                }
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                val serviceIntent = Intent(context, OverlayService::class.java)
                serviceIntent.action = "STOP_WITH_DELAY"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } else if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val outgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            if (!outgoingNumber.isNullOrEmpty()) {
                startOverlayService(context, outgoingNumber)
            }
        }
    }

    private fun startOverlayService(context: Context, phoneNumber: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            // Permission not granted, can't show overlay
            return
        }

        val serviceIntent = Intent(context, OverlayService::class.java)
        serviceIntent.putExtra("PHONE_NUMBER", phoneNumber)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
