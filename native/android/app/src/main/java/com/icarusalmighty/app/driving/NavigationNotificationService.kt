package com.icarusalmighty.app.driving

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NavigationNotificationService : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() = NavigationFeed.publish(NavigationSnapshot())

    private fun refresh() {
        val candidate = activeNotifications
            ?.filter { it.packageName in SUPPORTED_PACKAGES && it.isOngoing }
            ?.maxByOrNull { it.postTime }
        if (candidate == null) {
            NavigationFeed.publish(NavigationSnapshot())
            return
        }
        val extras = candidate.notification.extras
        val source = if (candidate.packageName == GOOGLE_MAPS) "GOOGLE MAPS" else "WAZE"
        NavigationFeed.publish(NavigationTextParser.parse(listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            *extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty(),
        ), source))
    }

    companion object {
        private const val GOOGLE_MAPS = "com.google.android.apps.maps"
        private val SUPPORTED_PACKAGES = setOf(GOOGLE_MAPS, "com.waze")
    }
}
