package com.proofmark.qrstudio

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Bridges the web app's client-side file downloads (QR exports as PNG,
 * JPEG, WEBP, SVG, and PDF — all produced in-page as blob: or data: URLs
 * via `<a download>` clicks) into real files saved to the device's
 * Downloads collection.
 *
 * Chromium WebView does not natively resolve blob:/data: URLs the way a
 * full browser does when an `<a download>` element is clicked, so
 * [MainActivity] injects a small JavaScript snippet that intercepts those
 * clicks, reads the blob/data content as base64 in-page, and forwards it
 * here through this `@JavascriptInterface`.
 */
class AndroidDownloader(private val context: Context) {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "AndroidDownloader"
        private const val CHANNEL_ID = "downloads"
        private var notificationId = 4200
    }

    /**
     * @param base64Data Raw base64 payload (no `data:...;base64,` prefix).
     * @param filename Suggested filename, including extension.
     * @param mimeType MIME type of the file, e.g. "image/png".
     */
    @JavascriptInterface
    fun saveFile(base64Data: String, filename: String, mimeType: String) {
        ioExecutor.execute {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val safeName = sanitizeFilename(filename)
                val savedUri = writeToDownloads(safeName, mimeType, bytes)
                postToMain {
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_complete, safeName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showDownloadNotification(safeName, savedUri)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to save downloaded file", t)
                postToMain {
                    Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.replace(Regex("[^a-zA-Z0-9._-]"), "-")
        return if (cleaned.isBlank()) "proofmark-download-${System.currentTimeMillis()}" else cleaned
    }

    private fun writeToDownloads(filename: String, mimeType: String, bytes: ByteArray): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(filename, mimeType, bytes)
        } else {
            writeViaLegacyFile(filename, bytes)
        }
    }

    @SuppressLint("InlinedApi")
    private fun writeViaMediaStore(filename: String, mimeType: String, bytes: ByteArray): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(itemUri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        return itemUri
    }

    private fun writeViaLegacyFile(filename: String, bytes: ByteArray): Uri {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val file = File(downloadsDir, filename)
        FileOutputStream(file).use { it.write(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun showDownloadNotification(filename: String, uri: Uri?) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "QR code export downloads" }
            manager.createNotificationChannel(channel)
        }

        // Android 13+ requires the runtime POST_NOTIFICATIONS permission; if
        // it hasn't been granted the download still succeeds, we just skip
        // the notification rather than crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val openIntent = uri?.let {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it, context.contentResolver.getType(it))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val pendingIntent = openIntent?.let {
            PendingIntent.getActivity(
                context, notificationId, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.download_complete, filename))
            .setContentText(filename)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()

        manager.notify(notificationId++, notification)
    }

    private fun postToMain(action: () -> Unit) {
        android.os.Handler(context.mainLooper).post(action)
    }
}
