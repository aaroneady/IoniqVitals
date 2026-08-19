package com.ioniqvitals.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Auto-connects the OBD reader when the paired OBD adapter connects over Bluetooth — i.e. when the
 * car powers on and the dongle comes online. This makes "get in the car → dashboard connects" work
 * without opening the phone app or Android Auto.
 *
 * Registered statically in the manifest for ACTION_ACL_CONNECTED (a protected system broadcast), so
 * it can wake the app even if nothing of ours is running. We only react to the specific saved
 * adapter, then start [ObdReaderService] — which itself guards against duplicate connects.
 */
class ObdConnectionReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is declared and granted for normal use.
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val connectedAddress = device?.address ?: return

        val savedAddress = ObdPreferences.getSelectedDeviceAddress(context) ?: return
        // The mock adapter has no real Bluetooth device; nothing to auto-connect to here.
        if (savedAddress == ObdReaderService.MOCK_DEVICE_ADDRESS) return
        if (!connectedAddress.equals(savedAddress, ignoreCase = true)) return

        Log.i(TAG, "Saved OBD adapter $connectedAddress connected over Bluetooth — auto-connecting")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ObdReaderService::class.java).apply {
                    action = ObdReaderService.ACTION_CONNECT
                    putExtra(ObdReaderService.EXTRA_DEVICE_ADDRESS, savedAddress)
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Auto-connect from Bluetooth receiver failed", e)
        }
    }

    companion object {
        private const val TAG = "ObdConnReceiver"
    }
}
