package dev.petrov.ymplayer2.sidebar

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/** The reboot-only subset of K4811 SettingFeature. Never accept a caller-supplied reset type. */
object K4811RebootProtocol {
    private const val DESCRIPTOR = "com.nwd.setting.service.SettingFeature"
    private const val REBOOT_TRANSACTION = 0x1c

    fun serviceIntent(): Intent = Intent("com.nwd.setting.service.ACTION_SETTING_SERVICE")
        .setComponent(ComponentName("com.nwd.setting.service", "com.nwd.setting.service.SettingService"))

    @Throws(RemoteException::class)
    fun verify(service: IBinder?) {
        if (service == null || service.interfaceDescriptor != DESCRIPTOR) {
            throw RemoteException("Unexpected K4811 setting service interface")
        }
    }

    /** Called only after explicit user confirmation, off the UI thread. */
    @Throws(RemoteException::class)
    fun reboot(service: IBinder?, cancelled: () -> Boolean): Boolean {
        verify(service)
        if (cancelled()) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            // Native K4811 RebootActivity sends type 2; other types can erase settings.
            data.writeByte(2)
            if (!service!!.transact(REBOOT_TRANSACTION, data, reply, 0)) {
                throw RemoteException("K4811 reboot transaction is unsupported")
            }
            reply.readException()
            return true
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
