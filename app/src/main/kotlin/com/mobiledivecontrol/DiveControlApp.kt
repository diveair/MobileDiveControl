package com.mobiledivecontrol

import android.app.Application

class DiveControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: Initialize BLE service, diagnostics, crash reporting
    }
}
