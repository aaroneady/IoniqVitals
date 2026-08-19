package com.ioniqvitals.obd

import android.content.Context

object ObdPreferences {

    private const val PREFS_NAME = "obd_prefs"
    private const val KEY_DEVICE_ADDRESS = "selected_device_address"

    fun getSelectedDeviceAddress(context: Context): String? {
        val address = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ADDRESS, null)
        return address?.takeIf { it.isNotBlank() }
    }

    fun setSelectedDeviceAddress(context: Context, address: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_ADDRESS, address)
            .apply()
    }
}
