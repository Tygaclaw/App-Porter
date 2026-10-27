package com.appporter

/** One installed app, plus where its APK file(s) live on the phone. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /** base.apk first, then any split parts (config.arm64_v8a.apk, etc.) */
    val apkPaths: List<String>,
    val totalBytes: Long,
    val isSystem: Boolean,
) {
    /** True when the app is delivered in several pieces (most Play Store apps). */
    val isSplit: Boolean get() = apkPaths.size > 1
}
