package dev.g5.companion

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService

/**
 * Backward compatibility stub redirecting to [GlassesCompanionService].
 */
class GlassesService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        GlassesCompanionService.start(this)
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "glasses"

        fun start(ctx: Context) = GlassesCompanionService.start(ctx)
    }
}
