package com.example.srtwatcher

import android.app.Application
import com.example.srtwatcher.notification.NotificationHelper

class SrtWatcherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 알림 채널은 앱 시작 시 한 번 만들어 둔다. (DESIGN.md §19)
        NotificationHelper(this).createChannel()
    }
}
