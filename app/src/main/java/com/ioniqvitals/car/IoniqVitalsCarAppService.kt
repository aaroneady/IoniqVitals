package com.ioniqvitals.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

import androidx.car.app.SessionInfo

/**
 * Car App Library service. Hosts [IoniqVitalsCarSession] -> [VehicleStatusScreen], the clean
 * full-screen template grid (no media scrim). The Android Auto media card is a separate
 * surface owned by [IoniqVitalsMediaBrowserService]; both read the same OBD data flow.
 */
class IoniqVitalsCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = IoniqVitalsCarSession()

    // Note: the shared MediaSession is intentionally NOT released here. It is owned by
    // IoniqVitalsMediaBrowserService; releasing it when the template closes would kill the
    // media card while it may still be on screen.
}
