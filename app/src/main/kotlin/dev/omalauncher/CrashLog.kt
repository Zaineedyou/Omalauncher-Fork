package dev.omalauncher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes an uncaught exception to a file before the app dies, so "it force closes" comes with the stack trace that
 * says why. It lands in Download/Omalauncher (where any file manager sees it) and, if that fails, in the app's own
 * folder under Android/data. The trace is of the release build, so its names are R8's: the build's
 * app/build/outputs/mapping/release/mapping.txt turns them back. A crash in native code (proot, the Wayland bridge)
 * isn't an exception and leaves nothing here.
 */
object CrashLog {
    private const val KEEP = 10

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Whatever goes wrong while writing mustn't hide the crash it is about.
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(
        context: Context,
        thread: Thread,
        error: Throwable,
    ) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val version =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }
                .getOrDefault("?")
        val text =
            buildString {
                appendLine("time: $stamp")
                appendLine("thread: ${thread.name}")
                appendLine("app: $version")
                appendLine("android: ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine()
                append(Log.getStackTraceString(error))
            }
        val name = "omalauncher-crash-$stamp.txt"
        val saved =
            runCatching {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Omalauncher")
                    }
                val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            }.isSuccess
        if (!saved) {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash").apply { mkdirs() }
            File(dir, name).writeText(text)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        }
    }
}
