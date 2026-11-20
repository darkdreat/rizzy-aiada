package com.fluxai.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class FluxNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Privacy-first: notification text is not read or transmitted automatically.
        // Notification access is reserved for a future explicit, user-triggered reply flow.
    }
}
