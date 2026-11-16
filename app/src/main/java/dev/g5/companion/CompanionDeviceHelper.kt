package dev.g5.companion

import android.app.Activity
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.regex.Pattern

/**
 * Manages CompanionDeviceManager (CDM) associations for DPVR G5 glasses.
 *
 * Holding a CDM association while running a Foreground Service with
 * `foregroundServiceType="connectedDevice"` exempts the app from Background
 * Activity Launch (BAL) restrictions on Android 10-15+, permitting
 * `startActivity()` invocations while the phone screen is off and locked in a pocket.
 */
object CompanionDeviceHelper {
    private const val TAG = "CompanionDeviceHelper"

    private val _associated = MutableStateFlow(false)
    val associated = _associated.asStateFlow()

    fun isDeviceAssociated(context: Context, macAddress: String?): Boolean {
        if (macAddress.isNullOrBlank()) return false
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(macAddress, ignoreCase = true) }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.any { it.equals(macAddress, ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking CDM associations", e)
            false
        }.also { _associated.value = it }
    }

    fun requestAssociation(
        activity: Activity,
        targetName: String? = null,
        targetMac: String? = null,
        launcher: ActivityResultLauncher<IntentSenderRequest>? = null,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            onError?.invoke("CompanionDeviceManager not available")
            return
        }

        val filterBuilder = BluetoothDeviceFilter.Builder()
        if (!targetName.isNullOrBlank()) {
            filterBuilder.setNamePattern(Pattern.compile(".*$targetName.*", Pattern.CASE_INSENSITIVE))
        }
        if (!targetMac.isNullOrBlank()) {
            filterBuilder.setAddress(targetMac)
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(filterBuilder.build())
            .setSingleDevice(true)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                try {
                    if (launcher != null) {
                        launcher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    } else {
                        @Suppress("DEPRECATION")
                        activity.startIntentSenderForResult(chooserLauncher, 9001, null, 0, 0, 0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch CDM chooser", e)
                    onError?.invoke(e.message ?: "Failed to open companion device chooser")
                }
            }

            override fun onFailure(error: CharSequence?) {
                Log.e(TAG, "CDM association failed: $error")
                onError?.invoke(error?.toString() ?: "Association failed")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.associate(request, activity.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                cdm.associate(request, callback, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking associate", e)
            onError?.invoke(e.message ?: "Error initiating association")
        }
    }

    fun disassociate(context: Context, macAddress: String?) {
        if (macAddress.isNullOrBlank()) return
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val match = cdm.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(macAddress, ignoreCase = true) }
                if (match != null) {
                    cdm.disassociate(match.id)
                }
            } else {
                @Suppress("DEPRECATION")
                cdm.disassociate(macAddress)
            }
            _associated.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disassociate $macAddress", e)
        }
    }
}
