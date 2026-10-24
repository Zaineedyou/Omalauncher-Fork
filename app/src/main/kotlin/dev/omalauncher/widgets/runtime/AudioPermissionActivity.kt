package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Asks for what audio capture needs, in the order Android wants it: the microphone permission (RECORD_AUDIO,
 * which playback capture requires too), then the system's recording prompt. Both are the user's to give; with
 * them, it starts [AudioCaptureService] and closes. Has no screen of its own.
 */
class AudioPermissionActivity : ComponentActivity() {
    private val projection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AudioCaptureService::class.java)
                        .putExtra(AudioCaptureService.EXTRA_CODE, result.resultCode)
                        .putExtra(AudioCaptureService.EXTRA_DATA, data),
                )
            }
            finish()
        }

    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                askProjection()
            } else {
                Toast.makeText(this, "Audio capture needs the microphone permission", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection() else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // From Android 14 the prompt lets the user share one app's window, which isn't what's wanted here; the
        // default display config offers the whole screen, which is what playback capture of every app goes with.
        val intent =
            if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        projection.launch(intent)
    }
}
