package com.smartclass.manager

import android.app.Application
import com.smartclass.manager.core.ErrorTracker

class SmartClassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorTracker.initCrashHandler(this)
        ErrorTracker.addBreadcrumb("تشغيل التطبيق")
    }
}
