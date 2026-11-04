package io.github.kazumaproject.cuperalauncher

import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BadgeListener : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()
    override fun onListenerDisconnected() = publish(emptyMap())

    private fun refresh() {
        val ranking = Ranking()
        val rankings = currentRanking
        val counts = runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray())
            .filter {
                it.user == Process.myUserHandle() &&
                    it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 &&
                    rankings?.getRanking(it.key, ranking) == true && ranking.canShowBadge()
            }.groupingBy { it.packageName }.eachCount()
        publish(counts)
    }

    private fun publish(values: Map<String, Int>) {
        if (counts == values) return
        counts = values
        changed?.invoke()
    }

    companion object {
        var counts: Map<String, Int> = emptyMap()
            private set
        var changed: (() -> Unit)? = null
    }
}
