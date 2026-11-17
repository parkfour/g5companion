package dev.g5.companion

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service maintaining the connected device session for DPVR G5 glasses.
 *
 * Runs with `foregroundServiceType="connectedDevice|microphone"` to ensure Background Activity
 * Launch (BAL) exemption when paired with CompanionDeviceManager. Acquires a timed PARTIAL_WAKE_LOCK
 * during inference and executes voice commands even while the phone screen is locked and in pocket.
 */
class GlassesCompanionService : LifecycleService() {
    private lateinit var router: GlassesCommandRouter
    private lateinit var ruleStore: IntentRuleStore
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ruleStore = IntentRuleStore(this)
        router = GlassesCommandRouter(this, ruleStore)

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "G5:InferenceWakeLock")?.apply {
            setReferenceCounted(false)
        }

        lifecycleScope.launch {
            Glasses.events.collect { event ->
                if (event is Glasses.Event.AiButton) {
                    handleAiButtonEvent()
                }
            }
        }

        lifecycleScope.launch {
            Glasses.state.collect { s ->
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(this@GlassesCompanionService).notify(ID, notification(s))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val mic = if (BtAudio.hasMicPermission(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(
            this,
            ID,
            notification(Glasses.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or mic,
        )

        when (intent?.action) {
            ACTION_STOP -> {
                GeminiLive.stop(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_AI -> {
                handleAiButtonEvent()
                return START_NOT_STICKY
            }
            ACTION_ROUTE_COMMAND -> {
                val cmd = intent.getStringExtra("command") ?: "navigate to Central Park"
                lifecycleScope.launch {
                    val res = router.route(cmd, null)
                    Log.i(TAG, "ACTION_ROUTE_COMMAND result: $res")
                }
                return START_NOT_STICKY
            }
        }

        Glasses.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    /**
     * Handles incoming AI button trigger with timed PARTIAL_WAKE_LOCK.
     */
    private fun handleAiButtonEvent() {
        lifecycleScope.launch {
            // Acquire wake lock for inference duration (with 15-second safety limit)
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            try {
                Glasses.stopVoiceAssistant()

                // Route audio to Bluetooth headset
                BtAudio.routeToGlasses(this@GlassesCompanionService)

                coroutineScope {
                    val photoJob = async { Glasses.capturePhoto() }
                    val voiceJob = async(Dispatchers.IO) {
                        if (BtAudio.hasMicPermission(this@GlassesCompanionService)) {
                            BtAudio.recordUtterance()
                        } else null
                    }

                    val rawPhoto = photoJob.await()
                    val rawAudio = voiceJob.await()

                    if (rawAudio != null) {
                        val transcript = Speech.transcribe(this@GlassesCompanionService, rawAudio)
                        if (!transcript.isNullOrBlank()) {
                            router.route(transcript, rawPhoto)
                        } else {
                            Log.i(TAG, "On-device speech gave no match; delegating audio to Assistant")
                            val prefs = Prefs(this@GlassesCompanionService)
                            if (prefs.apiKey(prefs.provider) != null || prefs.provider == Provider.CUSTOM) {
                                Assistant.ask(audio = rawAudio, image = rawPhoto, typed = null)
                            } else {
                                Assistant.say("Sorry, I didn't catch that.")
                            }
                        }
                    } else if (rawPhoto != null) {
                        router.route("What am I looking at?", rawPhoto)
                    } else {
                        Assistant.say("I didn't hear a question.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling glasses AI event", e)
                Assistant.say("Could not complete request.")
            } finally {
                BtAudio.release(this@GlassesCompanionService)
                // Always release wake lock immediately in finally block
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        }
    }

    private fun notification(s: Glasses.State): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, GlassesCompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            s.connected -> "Connected" + (s.battery?.let { " · $it%" } ?: "") + if (s.charging) " ⚡" else ""
            s.connecting -> "Connecting…"
            else -> "Waiting for glasses"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(s.name ?: "DPVR G5")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "GlassesCompanionService"
        const val CHANNEL = "glasses"
        private const val ID = 1
        private const val ACTION_STOP = "stop"
        const val ACTION_TRIGGER_AI = "dev.g5.companion.TRIGGER_AI"
        const val ACTION_ROUTE_COMMAND = "dev.g5.companion.ROUTE_COMMAND"
        private const val WAKE_LOCK_TIMEOUT_MS = 45_000L // covers a spoken yes/no confirmation (HandsFree)

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, GlassesCompanionService::class.java))

        fun triggerAiButton(ctx: Context) {
            val intent = Intent(ctx, GlassesCompanionService::class.java).apply {
                action = ACTION_TRIGGER_AI
            }
            ctx.startForegroundService(intent)
        }

        fun routeCommand(ctx: Context, command: String) {
            val intent = Intent(ctx, GlassesCompanionService::class.java).apply {
                action = ACTION_ROUTE_COMMAND
                putExtra("command", command)
            }
            ctx.startForegroundService(intent)
        }
    }
}
