package dev.omalauncher.widgets.runtime

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.omalauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the MediaProjection the user agreed to, in a foreground service of type mediaProjection: Android needs
 * the service running, with that type, before getMediaProjection works, and for as long as the capture does.
 * Started by [AudioPermissionActivity] once the user has said yes; stopped from the notification or `audio("stop")`.
 */
class AudioCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_DATA)
            }
        if (code != Activity.RESULT_OK || data == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The type goes first: getMediaProjection throws on Android 14+ unless the service already runs with it.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        release()
        val p =
            try {
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            } catch (e: SecurityException) {
                Log.w("audio", "no projection: ${e.message}")
                null
            }
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        // The user can end it from the system's status chip or quick settings: stop with it, or it would read silence.
        callback =
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }.also { p.registerCallback(it, Handler(Looper.getMainLooper())) }
        scope.launch { AudioCapture.run(p) }
        return START_NOT_STICKY
    }

    private fun release() {
        callback?.let { projection?.unregisterCallback(it) }
        callback = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        scope.cancel()
        release()
        AudioCapture.state.value = AudioCapture.State()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Audio capture", NotificationManager.IMPORTANCE_LOW))
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setContentTitle("Spectrum analyzer is listening to the audio your phone plays")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "dev.omalauncher.audio.STOP"
        private const val CHANNEL = "audio_capture"
        private const val NOTIFICATION_ID = 0x4155
    }
}
