package com.appporter

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Copies an installed app's APK file(s) out to a file you choose.
 *  - Single-file app  -> a normal .apk
 *  - Split app        -> a .apks bundle (a zip holding base.apk + all split parts)
 */
object Extractor {

    const val MIME_APK = "application/vnd.android.package-archive"
    // octet-stream stops Android from renaming "Game.apks" to "Game.apks.zip"
    const val MIME_BUNDLE = "application/octet-stream"

    fun fileName(app: AppEntry): String {
        val name = app.label
            .replace(Regex("[^A-Za-z0-9._ -]"), "")
            .trim()
            .replace(' ', '_')
            .ifEmpty { app.packageName }
        val ver = app.versionName.replace(Regex("[^A-Za-z0-9._-]"), "")
        val ext = if (app.isSplit) ".apks" else ".apk"
        return "${name}_v$ver$ext"
    }

    fun mimeType(app: AppEntry): String = if (app.isSplit) MIME_BUNDLE else MIME_APK

    /** Writes the app into [out]. Does NOT close [out]. Reports 0..100 to [onProgress]. */
    fun export(app: AppEntry, out: OutputStream, onProgress: (Int) -> Unit) {
        val total = app.totalBytes.coerceAtLeast(1L)
        var done = 0L
        var lastPercent = -1
        val buffer = ByteArray(256 * 1024)

        fun report(n: Int) {
            done += n
            val p = ((done * 100) / total).toInt().coerceIn(0, 100)
            if (p != lastPercent) {
                lastPercent = p
                onProgress(p)
            }
        }

        if (!app.isSplit) {
            FileInputStream(app.apkPaths[0]).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    report(n)
                }
            }
            out.flush()
            return
        }

        val buffered = BufferedOutputStream(out, 256 * 1024)
        val zip = ZipOutputStream(buffered)
        zip.setLevel(Deflater.BEST_SPEED)
        val usedNames = HashSet<String>()
        for ((index, path) in app.apkPaths.withIndex()) {
            var entryName = File(path).name
            if (!usedNames.add(entryName)) {
                entryName = "part${index}_$entryName"
                usedNames.add(entryName)
            }
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(path).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    zip.write(buffer, 0, n)
                    report(n)
                }
            }
            zip.closeEntry()
        }
        zip.finish()
        buffered.flush()
    }
}
