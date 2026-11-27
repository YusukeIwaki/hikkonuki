package app.hikkonuki.android.platform

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class UsbDetachReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var detached = 0
    private val receiver = UsbDetachReceiver { detached++ }

    private fun send(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun cablePullWithoutDataUriIsReceived() {
        receiver.register(context)
        send(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED))
        assertEquals(1, detached)
    }

    @Test
    fun everyKindOfVolumeRemovalIsReceived() {
        receiver.register(context)
        for (action in UsbDetachReceiver.MEDIA_ACTIONS) send(Intent(action, Uri.parse("file:///storage/1234-ABCD")))
        assertEquals(4, detached)
    }

    @Test
    fun attachAndMountAreNotMistakenForRemoval() {
        receiver.register(context)
        send(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        send(Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file:///storage/1234-ABCD")))
        assertEquals(0, detached)
    }

    @Test
    fun nothingArrivesAfterUnregistering() {
        receiver.register(context)
        receiver.unregister(context)
        send(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED))
        send(Intent(Intent.ACTION_MEDIA_BAD_REMOVAL, Uri.parse("file:///storage/1234-ABCD")))
        assertEquals(0, detached)
    }
}
