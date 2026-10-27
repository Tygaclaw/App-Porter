package com.appporter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var countText: TextView
    private lateinit var loading: CircularProgressIndicator

    private var allApps: List<AppEntry> = emptyList()
    private var showSystem = false
    private var query = ""

    /** Which app we're waiting to save while the "Save as" screen is open. */
    private var pendingExportPkg: String? = null

    // "Save as" screens (one for .apk, one for .apks bundles)
    private val saveApkLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(Extractor.MIME_APK)) { uri ->
            onSaveTargetChosen(uri)
        }
    private val saveBundleLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(Extractor.MIME_BUNDLE)) { uri ->
            onSaveTargetChosen(uri)
        }

    // "Pick a file to install" screen
    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startInstall(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        pendingExportPkg = savedInstanceState?.getString(KEY_PENDING_EXPORT)

        // Android 16 draws apps edge-to-edge, so keep our content clear of the
        // status bar, navigation bar and keyboard.
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    loadApps(); true
                }
                R.id.action_help -> {
                    showHelp(); true
                }
                else -> false
            }
        }

        countText = findViewById(R.id.count)
        loading = findViewById(R.id.loading)

        adapter = AppAdapter(lifecycleScope, packageManager) { app -> showAppDialog(app) }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<TextInputEditText>(R.id.search).doAfterTextChanged { text ->
            query = text?.toString() ?: ""
            applyFilter()
        }

        findViewById<MaterialSwitch>(R.id.systemSwitch).setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            applyFilter()
        }

        findViewById<ExtendedFloatingActionButton>(R.id.fabInstall).setOnClickListener {
            onInstallClicked()
        }

        loadApps()

        if (savedInstanceState == null) handleInstallStatus(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_EXPORT, pendingExportPkg)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    // ---------------------------------------------------------------- app list

    private fun loadApps() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppRepository.loadApps(packageManager) }
            allApps = apps
            loading.visibility = View.GONE
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val filtered = allApps.filter { app ->
            (showSystem || !app.isSystem) &&
                (q.isEmpty() ||
                    app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q))
        }
        adapter.submit(filtered)
        countText.text = if (filtered.size == 1) "1 app" else "${filtered.size} apps"
    }

    private fun showAppDialog(app: AppEntry) {
        val size = Formatter.formatShortFileSize(this, app.totalBytes)
        val format = if (app.isSplit) {
            "This app comes in ${app.apkPaths.size} pieces, so it saves as one .apks file.\n" +
                "Install it on your other device with App Porter's \"Install app file\" button."
        } else {
            "This app is a single file, so it saves as a normal .apk you can tap to install anywhere."
        }
        val message = "Package: ${app.packageName}\n" +
            "Version: ${app.versionName}\n" +
            "Size: $size\n\n" +
            format

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setMessage(message)
            .setPositiveButton("Save to phone") { _, _ -> startSave(app) }
            .setNeutralButton("Share") { _, _ -> startShare(app) }
            .setNegativeButton("Cancel", null)
        adapter.cachedIcon(app.packageName)?.let { builder.setIcon(it) }
        builder.show()
    }

    // -------------------------------------------------------------- save / share

    private fun startSave(app: AppEntry) {
        pendingExportPkg = app.packageName
        val name = Extractor.fileName(app)
        try {
            if (app.isSplit) saveBundleLauncher.launch(name) else saveApkLauncher.launch(name)
        } catch (e: ActivityNotFoundException) {
            pendingExportPkg = null
            showError("Couldn't open the save screen on this phone.")
        }
    }

    private fun onSaveTargetChosen(uri: Uri?) {
        val pkg = pendingExportPkg
        pendingExportPkg = null
        if (uri == null || pkg == null) return

        lifecycleScope.launch {
            val app = findApp(pkg)
            if (app == null) {
                showError("That app isn't installed anymore.")
                return@launch
            }
            runWithProgress(
                title = "Saving ${app.label}…",
                work = { progress ->
                    val out = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Couldn't write to that location.")
                    out.use { Extractor.export(app, it, progress) }
                },
                onSuccess = {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Saved!")
                        .setMessage(
                            "${Extractor.fileName(app)} is saved.\n\n" +
                                "Move it to your other device (Quick Share, USB cable, Google Drive, etc.), " +
                                "then install it there."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                },
                onFailure = {
                    try {
                        DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (ignored: Exception) {
                    }
                },
            )
        }
    }

    private fun startShare(app: AppEntry) {
        runWithProgress(
            title = "Preparing ${app.label}…",
            work = { progress ->
                val dir = File(cacheDir, "exports")
                dir.deleteRecursively()
                dir.mkdirs()
                val file = File(dir, Extractor.fileName(app))
                file.outputStream().use { Extractor.export(app, it, progress) }
                file
            },
            onSuccess = { file ->
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(Extractor.mimeType(app))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Send ${app.label} to…"))
            },
        )
    }

    private suspend fun findApp(pkg: String): AppEntry? {
        allApps.firstOrNull { it.packageName == pkg }?.let { return it }
        val fresh = withContext(Dispatchers.IO) { AppRepository.loadApps(packageManager) }
        allApps = fresh
        applyFilter()
        return fresh.firstOrNull { it.packageName == pkg }
    }

    // ------------------------------------------------------------------ install

    private fun onInstallClicked() {
        if (!packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("One-time permission needed")
                .setMessage(
                    "To install apps, Android needs you to let App Porter \"Install unknown apps\".\n\n" +
                        "Tap Open settings, turn the switch ON, press Back, then tap " +
                        "\"Install app file\" again."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    val i = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    try {
                        startActivity(i)
                    } catch (e: ActivityNotFoundException) {
                        showError("Couldn't open that settings page on this phone.")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        try {
            pickFileLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            showError("Couldn't open the file picker on this phone.")
        }
    }

    private fun startInstall(uri: Uri) {
        runWithProgress(
            title = "Getting the app ready to install…",
            work = { progress -> Installer.install(this, uri, progress) },
            onSuccess = { /* Android now shows its own "Install?" screen */ },
        )
    }

    private fun handleInstallStatus(intent: Intent?) {
        if (intent == null || intent.action != Installer.ACTION_INSTALL_STATUS) return
        setIntent(Intent(this, MainActivity::class.java)) // don't handle it twice

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    try {
                        startActivity(confirm)
                    } catch (e: Exception) {
                        showError("Android couldn't show the install screen.\n\n${e.message ?: ""}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Installed!")
                    .setMessage("The app is installed on this device. You'll find it in your app drawer.")
                    .setPositiveButton("OK", null)
                    .show()
                loadApps()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Toast.makeText(this, "Install cancelled", Toast.LENGTH_SHORT).show()
            }
            else -> showError(Installer.friendlyError(status, message), title = "Couldn't install")
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Shows a progress popup, runs [work] in the background, then calls
     * [onSuccess] (or shows the error) back on the main screen.
     */
    private fun <T> runWithProgress(
        title: String,
        work: ((Int) -> Unit) -> T,
        onSuccess: (T) -> Unit,
        onFailure: () -> Unit = {},
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        val text = view.findViewById<TextView>(R.id.progressText)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
        text.text = title
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    work { percent ->
                        runOnUiThread {
                            bar.setProgressCompat(percent, true)
                            text.text = "$title  $percent%"
                        }
                    }
                }
                dialog.dismiss()
                onSuccess(result)
            } catch (e: Exception) {
                dialog.dismiss()
                withContext(Dispatchers.IO) { onFailure() }
                showError(e.message ?: e.toString())
            }
        }
    }

    private fun showError(message: String, title: String = "Something went wrong") {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("How App Porter works")
            .setMessage(
                "SAVE: tap any app, then Save to phone or Share.\n" +
                    "• Single-file apps save as .apk\n" +
                    "• Apps marked SPLIT come in pieces and save as one .apks file\n\n" +
                    "INSTALL on another device: put App Porter on that device too, tap " +
                    "\"Install app file\", and pick the .apk or .apks file.\n\n" +
                    "WHAT'S INCLUDED: the full app itself (all its code, pictures and pieces).\n\n" +
                    "WHAT'S NOT: your private data inside the app (logins, game saves, settings). " +
                    "Android locks that away so no app can read it without root. " +
                    "Most apps keep that in your account, so just sign in on the new device.\n\n" +
                    "TIP: split apps only include pieces for THIS phone's chip type, so they install " +
                    "best on devices with the same kind of chip (almost all modern phones are 64-bit ARM)."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    companion object {
        private const val KEY_PENDING_EXPORT = "pending_export"
    }
}
