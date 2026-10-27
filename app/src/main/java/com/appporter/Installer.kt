package com.appporter

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Installs an .apk, .apks, .xapk or .zip (anything holding .apk files) using
 * Android's own PackageInstaller. Android then shows its normal "Install?" screen.
 */
object Installer {

    const val ACTION_INSTALL_STATUS = "com.appporter.INSTALL_STATUS"

    private class Part(val name: String, val size: Long, val open: () -> InputStream)

    /** Prepares and hands the app to Android. Call from a background thread. */
    fun install(context: Context, uri: Uri, onProgress: (Int) -> Unit) {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        // 1) Copy the picked file into our private cache so we can read it reliably.
        val tmp = File(appContext.cacheDir, "incoming.tmp")
        tmp.delete()
        val input = resolver.openInputStream(uri) ?: throw IOException("Couldn't open that file.")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }

        try {
            val zipFile = try {
                ZipFile(tmp)
            } catch (e: Exception) {
                throw IOException("That file isn't an app file (.apk or .apks), or it's damaged.")
            }

            zipFile.use { zf ->
                // A real .apk has AndroidManifest.xml inside it. A bundle has .apk files inside it.
                val isSingleApk = zf.getEntry("AndroidManifest.xml") != null

                val parts = ArrayList<Part>()
                if (isSingleApk) {
                    parts.add(Part("base.apk", tmp.length()) { FileInputStream(tmp) })
                } else {
                    val used = HashSet<String>()
                    for (entry in zf.entries().toList()) {
                        if (entry.isDirectory) continue
                        if (!entry.name.endsWith(".apk", ignoreCase = true)) continue
                        var name = File(entry.name).name
                        if (!used.add(name)) {
                            name = "${used.size}_$name"
                            used.add(name)
                        }
                        parts.add(Part(name, entry.size) { zf.getInputStream(entry) })
                    }
                }
                if (parts.isEmpty()) {
                    throw IOException("No app files were found inside that file.")
                }

                val totalBytes = parts.sumOf { if (it.size > 0) it.size else 0L }.coerceAtLeast(1L)
                var done = 0L
                var lastPercent = -1

                // 2) Open an install session and stream every piece into it.
                val installer = appContext.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                params.setInstallReason(PackageManager.INSTALL_REASON_USER)
                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                try {
                    val buffer = ByteArray(256 * 1024)
                    for (part in parts) {
                        val sizeForSession = if (part.size > 0) part.size else -1L
                        session.openWrite(part.name, 0, sizeForSession).use { out ->
                            part.open().use { ins ->
                                while (true) {
                                    val n = ins.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    done += n
                                    val p = ((done * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    if (p != lastPercent) {
                                        lastPercent = p
                                        onProgress(p)
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }

                    // 3) Ask Android to install. The result comes back to MainActivity.
                    val statusIntent = Intent(appContext, MainActivity::class.java)
                        .setAction(ACTION_INSTALL_STATUS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    var flags = PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                    val pending = PendingIntent.getActivity(appContext, sessionId, statusIntent, flags)
                    session.commit(pending.intentSender)
                    session.close()
                } catch (t: Throwable) {
                    try {
                        session.abandon()
                    } catch (ignored: Throwable) {
                    }
                    throw t
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** Turns Android's install error codes into plain English. */
    fun friendlyError(status: Int, rawMessage: String?): String {
        val raw = rawMessage ?: ""
        val simple = when {
            raw.contains("NO_MATCHING_ABIS", true) ->
                "This phone's chip type (CPU) is different from the phone you copied the app from, " +
                    "so the app's pieces don't fit. Copy it from a phone with the same kind of chip, " +
                    "or get it from the store on this device."
            raw.contains("UPDATE_INCOMPATIBLE", true) || status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "A version of this app is already installed here and it doesn't match (different signature, " +
                    "or the installed one is newer). Uninstall the app on this device first, then try again."
            raw.contains("OLDER_SDK", true) ->
                "This device's Android version is too old for this app."
            raw.contains("MISSING_SPLIT", true) ->
                "Part of the app is missing. Make sure you copied the .apks file (not just one .apk)."
            status == PackageInstaller.STATUS_FAILURE_STORAGE ->
                "There isn't enough free storage on this device."
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "This app isn't compatible with this device (chip type or Android version)."
            status == PackageInstaller.STATUS_FAILURE_INVALID ->
                "The app file is damaged or incomplete. Try exporting it again."
            status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "This device blocked the install. On Samsung phones, turn off Auto Blocker " +
                    "(Settings > Security and privacy > Auto Blocker) and try again."
            else -> "The install didn't finish."
        }
        return if (raw.isNotBlank()) "$simple\n\nTechnical detail: $raw" else simple
    }
}
