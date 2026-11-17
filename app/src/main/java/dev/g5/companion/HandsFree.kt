package dev.g5.companion

import android.Manifest
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Built-in voice commands that finish without touching the phone: reply to the last message,
 * call / text a contact (after a spoken yes), timers, reminders, walking directions and
 * "what did I miss". Runs before the custom intent rules and the cloud LLM.
 */
object HandsFree {
    private const val TAG = "HandsFree"
    private const val MAX_NAME_WORDS = 3

    sealed interface Cmd {
        data class Reply(val body: String) : Cmd
        data class Call(val who: String) : Cmd
        /** [who] is null when the recipient/body split must come from the contact list. */
        data class Text(val who: String?, val body: String) : Cmd
        data class Timer(val seconds: Int, val label: String?) : Cmd
        data class Alarm(val hour: Int, val minute: Int, val label: String?) : Cmd
        data class Walk(val place: String) : Cmd
        data object Missed : Cmd
    }

    data class Contact(val name: String, val number: String, val primary: Boolean = false, val mobile: Boolean = false)

    private val IC = RegexOption.IGNORE_CASE
    private val REPLY = Regex("""^(?:reply|respond)(?:\s+(?:with|saying|that))?[\s,:]+(.+)$""", IC)
    private val CALL = Regex("""^(?:call|phone|dial|ring)\s+(.+)$""", IC)
    private val TEXT = Regex("""^(?:text|message|send\s+(?:a\s+)?(?:text|message)\s+to)\s+(.+)$""", IC)
    private val SAYING = Regex("""\s+(?:saying|that says|that)\s+""", IC)
    private val TIMER = Regex("""^(?:set\s+)?(?:a\s+)?timer\s+(?:for\s+)?(.+)$""", IC)
    private val REMIND_IN = Regex("""^remind me\s+(?:to\s+(.+?)\s+)?in\s+(.+?)(?:\s+to\s+(.+))?$""", IC)
    private val REMIND_AT = Regex("""^remind me\s+(?:to\s+(.+?)\s+)?at\s+(.+?)(?:\s+to\s+(.+))?$""", IC)
    private val WALK = Regex("""^(?:walk(?:\s+me)?\s+to|walking directions\s+to|directions\s+to)\s+(.+)$""", IC)
    private val MISSED = Regex(
        """^(?:what did i miss|catch me up|read (?:me )?(?:my )?notifications|any (?:new )?(?:messages|notifications))$""", IC,
    )
    private val DURATION_PART = Regex("""\b(\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty)\s*(s|sec|second|m|min|minute|h|hr|hour)s?\b""", IC)
    private val CLOCK = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(a\.?\s?m\.?|p\.?\s?m\.?)?$""", IC)
    private val WORD_NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
    )
    private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "send", "correct", "affirmative", "do")

    fun parse(raw: String, now: LocalTime = LocalTime.now()): Cmd? {
        val t = raw.trim().trimEnd('.', '!', '?').trim()
        if (MISSED.matches(t)) return Cmd.Missed
        REPLY.matchEntire(t)?.let { return Cmd.Reply(it.groupValues[1].trim()) }
        TEXT.matchEntire(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            val parts = rest.split(SAYING, limit = 2)
            return if (parts.size == 2) Cmd.Text(parts[0].trim(), parts[1].trim()) else Cmd.Text(null, rest)
        }
        CALL.matchEntire(t)?.let { return Cmd.Call(it.groupValues[1].trim()) }
        TIMER.matchEntire(t)?.let { m -> seconds(m.groupValues[1])?.let { return Cmd.Timer(it, null) } }
        REMIND_IN.matchEntire(t)?.let { m ->
            val label = m.groupValues[1].ifBlank { m.groupValues[3] }.trim().ifBlank { null }
            seconds(m.groupValues[2])?.let { return Cmd.Timer(it, label) }
        }
        REMIND_AT.matchEntire(t)?.let { m ->
            val label = m.groupValues[1].ifBlank { m.groupValues[3] }.trim().ifBlank { null }
            clock(m.groupValues[2], now)?.let { (h, min) -> return Cmd.Alarm(h, min, label) }
        }
        WALK.matchEntire(t)?.let { return Cmd.Walk(it.groupValues[1].trim()) }
        return null
    }

    /** "1 hour and 30 minutes" -> 5400; "half an hour" -> 1800; null when no duration is present. */
    fun seconds(text: String): Int? {
        val s = text.trim()
        var total = if (Regex("""\bhalf an? hour\b""", IC).containsMatchIn(s)) 1800 else 0
        for (m in DURATION_PART.findAll(s.replace(Regex("""half an? hour""", IC), ""))) {
            val n = m.groupValues[1].toIntOrNull() ?: WORD_NUMBERS[m.groupValues[1].lowercase()] ?: continue
            total += n * when (m.groupValues[2].lowercase().first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
        }
        if (Regex("""\band a half\b""", IC).containsMatchIn(s)) {
            total += if (Regex("""hour|hr""", IC).containsMatchIn(s)) 1800 else 30
        }
        return total.takeIf { it > 0 }
    }

    /** "5 pm" -> 17:00; bare "5" -> the next 5 o'clock after [now]; "noon"/"midnight" too. */
    fun clock(text: String, now: LocalTime): Pair<Int, Int>? {
        val s = text.trim().lowercase()
        if (s == "noon") return 12 to 0
        if (s == "midnight") return 0 to 0
        val m = CLOCK.matchEntire(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifBlank { "0" }.toInt()
        if (h > 23 || min > 59) return null
        val ampm = m.groupValues[3].firstOrNull()
        if (ampm != null) {
            if (h !in 1..12) return null
            val h24 = (h % 12) + if (ampm == 'p') 12 else 0
            return h24 to min
        }
        if (h >= 12 || h == 0) return h to min
        val morning = LocalTime.of(h, min)
        return if (morning.isAfter(now)) h to min else if (LocalTime.of(h + 12, min).isAfter(now)) (h + 12) to min else h to min
    }

    fun isYes(answer: String?): Boolean {
        val words = answer?.lowercase()?.split(Regex("""[^a-z']+"""))?.filter { it.isNotEmpty() }.orEmpty()
        if (words.isEmpty() || words.any { it in setOf("no", "nope", "don't", "cancel", "stop", "wait" ) }) return false
        return words.first() in YES || words.joinToString(" ").startsWith("go ahead")
    }

    /** Best contact for [query]: exact name, then first-name, then substring; primary/mobile numbers break ties. */
    fun bestContact(query: String, rows: List<Contact>, strict: Boolean = false): Contact? {
        val q = query.trim().lowercase()
        fun score(c: Contact): Int {
            val n = c.name.lowercase()
            return when {
                n == q -> 3
                n.split(' ').firstOrNull() == q || n.startsWith("$q ") -> 2
                !strict && n.contains(q) -> 1
                else -> 0
            }
        }
        return rows.filter { score(it) > 0 }
            .maxWithOrNull(compareBy<Contact>({ score(it) }, { it.primary }, { it.mobile }))
    }

    /** "alex smith running late" -> (contact for the longest leading name, "running late"). */
    fun splitRecipient(rest: String, lookup: (String) -> Contact?): Pair<Contact, String>? {
        val words = rest.trim().split(Regex("""\s+"""))
        for (n in minOf(MAX_NAME_WORDS, words.size - 1) downTo 1) {
            val c = lookup(words.take(n).joinToString(" ")) ?: continue
            return c to words.drop(n).joinToString(" ")
        }
        return null
    }

    fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
            m.takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
            s.takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" },
        ).joinToString(" ")
    }

    /** Runs a hands-free command if [text] is one. Returns false to let the normal rules / LLM handle it. */
    suspend fun handle(ctx: Context, text: String): Boolean {
        val cmd = parse(text) ?: return false
        val needsPhone = cmd is Cmd.Call || cmd is Cmd.Text
        if (needsPhone && !Prefs(ctx).voiceCallsTexts) return false
        try {
            when (cmd) {
                is Cmd.Missed -> Assistant.say(NotificationReader.missedSummary() ?: "Give the app notification access first.")
                is Cmd.Reply -> reply(ctx, cmd.body)
                is Cmd.Call -> call(ctx, cmd.who)
                is Cmd.Text -> text(ctx, cmd)
                is Cmd.Timer -> timer(ctx, cmd)
                is Cmd.Alarm -> alarm(ctx, cmd)
                is Cmd.Walk -> walk(ctx, cmd.place)
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app for $cmd", e)
            Assistant.say("No app on the phone can do that.")
        } catch (e: SecurityException) {
            Log.w(TAG, "permission missing for $cmd", e)
            Assistant.say("The G5 app needs permission for that. Turn on voice calls and texts in its settings.")
        }
        return true
    }

    private suspend fun confirm(prompt: String): Boolean {
        val ok = isYes(Assistant.askAndListen(prompt))
        if (!ok) Assistant.say("Okay, cancelled.")
        return ok
    }

    private suspend fun reply(ctx: Context, body: String) {
        val r = NotificationReader.lastReply ?: return Assistant.say("There's no message to reply to.")
        if (!confirm("Reply to ${r.to}: $body. Send it?")) return
        val inputs = r.action.remoteInputs
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, body) } }
        val intent = Intent().also { RemoteInput.addResultsToIntent(inputs, it, results) }
        try {
            r.action.actionIntent.send(ctx, 0, intent)
            Assistant.say("Sent.")
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "reply intent cancelled", e)
            Assistant.say("That message can't be replied to anymore.")
        }
    }

    private suspend fun call(ctx: Context, who: String) {
        if (!granted(ctx, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) throw SecurityException("call")
        val c = findContact(ctx, who) ?: return Assistant.say("I couldn't find $who in your contacts.")
        Assistant.sayAndWait("Calling ${c.name}.")
        ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", c.number, null), Bundle())
    }

    private suspend fun text(ctx: Context, cmd: Cmd.Text) {
        if (!granted(ctx, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)) throw SecurityException("text")
        val (c, body) = if (cmd.who != null) {
            (findContact(ctx, cmd.who) ?: return Assistant.say("I couldn't find ${cmd.who} in your contacts.")) to cmd.body
        } else {
            splitRecipient(cmd.body) { findContact(ctx, it, strict = true) }
                ?: return Assistant.say("Say text, then the name, then saying, then your message.")
        }
        if (body.isBlank()) return Assistant.say("What should the message say? Try again with saying, then the message.")
        if (!confirm("Text ${c.name}: $body. Send it?")) return
        val sms = ctx.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(c.number, null, sms.divideMessage(body), null, null)
        Assistant.say("Sent.")
    }

    private fun timer(ctx: Context, cmd: Cmd.Timer) {
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { cmd.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val d = describe(cmd.seconds)
        Assistant.say(cmd.label?.let { "I'll remind you in $d to $it." } ?: "Timer set for $d.")
    }

    private fun alarm(ctx: Context, cmd: Cmd.Alarm) {
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { cmd.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val at = LocalTime.of(cmd.hour, cmd.minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        Assistant.say(cmd.label?.let { "I'll remind you at $at to $it." } ?: "Alarm set for $at.")
    }

    private fun walk(ctx: Context, place: String) {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}&mode=w"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Assistant.say("Walking directions to $place.")
    }

    private fun granted(ctx: Context, vararg perms: String) =
        perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun findContact(ctx: Context, query: String, strict: Boolean = false): Contact? {
        val rows = ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.TYPE),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${query.trim()}%"),
            null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    add(Contact(name, number, c.getInt(2) == 1, c.getInt(3) == Phone.TYPE_MOBILE))
                }
            }
        }.orEmpty()
        return bestContact(query, rows, strict)
    }
}
