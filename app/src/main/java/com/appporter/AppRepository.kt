package com.appporter

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/** Reads the list of installed apps. Call from a background thread. */
object AppRepository {

    fun loadApps(pm: PackageManager): List<AppEntry> {
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        val result = ArrayList<AppEntry>(packages.size)
        for (pkg in packages) {
            val ai: ApplicationInfo = pkg.applicationInfo ?: continue
            val base: String = ai.sourceDir ?: continue
            if (!File(base).canRead()) continue

            val paths = ArrayList<String>()
            paths.add(base)
            ai.splitSourceDirs?.let { splits ->
                for (s in splits) if (File(s).canRead()) paths.add(s)
            }

            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

            val label = try {
                ai.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg.packageName
            }

            result.add(
                AppEntry(
                    label = label,
                    packageName = pkg.packageName,
                    versionName = pkg.versionName ?: "unknown",
                    versionCode = PackageInfoCompat.getLongVersionCode(pkg),
                    apkPaths = paths,
                    totalBytes = paths.sumOf { File(it).length() },
                    isSystem = isSystem,
                )
            )
        }
        return result.sortedBy { it.label.lowercase() }
    }
}
