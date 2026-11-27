package app.hikkonuki.android.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

/**
 * Tells the export when the camera goes away.
 *
 * Two filters on purpose: an IntentFilter with a "file" data scheme never matches
 * ACTION_USB_DEVICE_DETACHED (it carries no data URI), so one combined filter would
 * silently ignore cable pulls. ACTION_MEDIA_BAD_REMOVAL is what is sent when a mounted
 * volume disappears without a clean eject.
 */
class UsbDetachReceiver(private val onDetached: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) onDetached()
    }

    fun register(context: Context) {
        val usb = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val media = IntentFilter().apply {
            MEDIA_ACTIONS.forEach(::addAction)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, this, usb, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, this, media, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(this) }
    }

    companion object {
        val MEDIA_ACTIONS = listOf(
            Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL,
        )
        val ACTIONS = MEDIA_ACTIONS + UsbManager.ACTION_USB_DEVICE_DETACHED
    }
}
