package com.ioniqvitals

import android.app.Application

class IoniqVitalsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ObdDataRepository.init(this)
    }
}
