package dev.g5.companion

import android.companion.CompanionDeviceService
import android.util.Log

/**
 * Service bound by Android's CompanionDeviceManager when the associated DPVR G5
 * glasses connect or appear nearby. Eliminates CDM service connector errors.
 */
class G5CompanionDeviceService : CompanionDeviceService() {
    override fun onDeviceAppeared(address: String) {
        Log.i(TAG, "DPVR G5 companion device appeared: $address")
        Glasses.ensureConnected()
    }

    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "DPVR G5 companion device disappeared: $address")
    }

    companion object {
        private const val TAG = "G5CompanionDevice"
    }
}
