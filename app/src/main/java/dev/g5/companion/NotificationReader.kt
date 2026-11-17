package dev.g5.companion

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** The newest notification that can be answered inline (Messages, WhatsApp, Signal…). */
data class Replyable(val to: String, val action: Notification.Action)

/**
 * Reads new phone notifications aloud through the glasses (phone stays in the pocket), announces
 * incoming calls, and remembers the last message for "reply …". Only notifications that would
 * actually alert count: silent/low-importance ones and anything blocked by Do Not Disturb are skipped.
 */
class NotificationReader : NotificationListenerService() {
    private val lastSpoken = HashMap<String, String>() // key -> phrase; apps repost the same notification on every update

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !alerts(sbn)) return
        val n = sbn.notification
        val prefs = Prefs(this)
        val connected = Glasses.state.value.connected
        val title = (n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim()

        if (n.category == Notification.CATEGORY_CALL) {
            if (prefs.announceCalls && connected && isIncomingCall(n)) speakOnce(sbn.key, "Call from ${title ?: "unknown caller"}")
            return
        }
        if (sbn.isOngoing) return
        // An update the app itself marks as non-alerting (e.g. our own reply echoed back) isn't news.
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && sbn.key in lastSpoken) return
        replyAction(n)?.let { lastReply = Replyable(title ?: appLabel(sbn.packageName) ?: "them", it) }

        if (!prefs.readNotifications || !connected) return
        speakOnce(sbn.key, phraseFor(sbn) ?: return)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSpoken.remove(sbn.key)
    }

    private fun speakOnce(key: String, phrase: String) {
        if (lastSpoken.put(key, phrase) == phrase) return
        Assistant.say(phrase, flush = false)
    }

    private fun alerts(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val r = Ranking()
        return currentRanking.getRanking(sbn.key, r) &&
            r.importance >= NotificationManager.IMPORTANCE_DEFAULT && r.matchesInterruptionFilter()
    }

    private fun appLabel(pkg: String): String? =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()

    private fun phraseFor(sbn: StatusBarNotification): String? {
        val e = sbn.notification.extras
        return phrase(appLabel(sbn.packageName), e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT))
    }

    /** Newest-first phrases for the alerting notifications still in the shade. */
    private fun unread(): List<String> = activeNotifications.orEmpty()
        .filter { it.packageName != packageName && !it.isOngoing && it.notification.category != Notification.CATEGORY_CALL && alerts(it) }
        .sortedByDescending { it.postTime }
        .mapNotNull { phraseFor(it) }

    companion object {
        private const val MAX_CHARS = 300
        private const val MAX_MISSED = 5

        @Volatile private var instance: NotificationReader? = null
        @Volatile var lastReply: Replyable? = null
            private set

        /** "Messages: Alex. Running late" — null when there's nothing to say. */
        fun phrase(app: String?, title: CharSequence?, text: CharSequence?): String? {
            val t = title?.toString()?.trim().orEmpty()
            val b = text?.toString()?.trim().orEmpty().takeIf { it != t }.orEmpty()
            if (t.isEmpty() && b.isEmpty()) return null
            val body = listOf(t, b).filter { it.isNotEmpty() }.joinToString(". ")
            return (if (app.isNullOrBlank()) body else "$app: $body").take(MAX_CHARS)
        }

        /** Spoken summary of what's waiting, or null if notification access isn't granted. */
        fun missedSummary(): String? {
            val items = instance?.unread() ?: return null
            if (items.isEmpty()) return "Nothing new."
            val head = if (items.size == 1) "One notification." else "${items.size} notifications."
            return (listOf(head) + items.take(MAX_MISSED)).joinToString(" ")
        }

        private fun isIncomingCall(n: Notification): Boolean =
            n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) == Notification.CallStyle.CALL_TYPE_INCOMING || n.fullScreenIntent != null

        private fun replyAction(n: Notification): Notification.Action? = n.actions?.firstOrNull {
            !it.remoteInputs.isNullOrEmpty() && it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

        fun hasAccess(ctx: Context): Boolean {
            val me = ComponentName(ctx, NotificationReader::class.java).flattenToString()
            return Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.split(':')?.contains(me) == true
        }
    }
}
