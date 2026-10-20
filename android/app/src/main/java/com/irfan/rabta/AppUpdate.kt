package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Updates for the public app. Rabta reads the newest GitHub release's rabta-update.json and installs that APK
 * through Android's own installer, which keeps the account and chats because the package and key stay the same.
 * Debuggable builds (Rabta Test, CI) never check, so test phones and device tests are not interrupted.
 */
object AppUpdate {
    /** Public, APK-only repository, so the source repository can stay private. */
    private const val RELEASES = "https://github.com/irfan347/rabta-app/releases"
    private const val LATEST = "$RELEASES/latest/download/rabta-update.json"
    private const val EVERY = 6 * 60 * 60 * 1000L
    private const val RETRY = 30 * 60 * 1000L

    data class Release(val code: Long, val name: String, val apk: String, val sha256: String, val size: Long, val minCode: Long, val notes: String)

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    @Volatile private var checking = false
    @Volatile private var downloading = false
    /** A release waiting for the user to allow "Install unknown apps" for Rabta. */
    private var waiting: Release? = null

    /** Not in test builds, nor in the Google Play build, which Play updates (res/values/store.xml). */
    fun enabled(c: Context) = !Config.testBuild(c) && c.resources.getBoolean(R.bool.self_update)
    fun versionName(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()
    private fun installed(c: Context) = code(c.packageManager.getPackageInfo(c.packageName, 0))
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    private fun prefs(c: Context) = c.getSharedPreferences("updates", 0)
    private fun toast(c: Context, text: String) = Toast.makeText(c, text, Toast.LENGTH_LONG).show()

    /**
     * From the chats screen: at most every 6 hours, but a required update is shown on every visit.
     * From Settings ([manual]): always, and says so when Rabta is already up to date. Main thread.
     */
    fun check(a: Activity, manual: Boolean = false) {
        if (!enabled(a) || downloading) return
        waiting?.let { if (canInstall(a)) { waiting = null; download(a, it); return } }
        val known = runCatching { prefs(a).getString("release", null)?.let { parse(JSONObject(it)) } }.getOrNull()
        val current = installed(a)
        if (!manual && known != null && known.code > current && known.minCode > current) { offer(a, known, true); return }
        val now = System.currentTimeMillis()
        if (checking || (!manual && now < prefs(a).getLong("next", 0))) return
        if (known == null || known.code <= current) cleanup(a)
        checking = true
        Thread {
            val release = runCatching { fetch() }.getOrNull()
            a.runOnUiThread {
                checking = false
                prefs(a).edit().putLong("next", now + if (release == null) RETRY else EVERY).apply()
                if (release != null) prefs(a).edit().putString("release", release.json()).apply()
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                when {
                    release == null -> if (manual) toast(a, "Could not check for updates. Check your internet and try again.")
                    release.code > current -> offer(a, release, release.minCode > current)
                    manual -> toast(a, "Rabta is up to date")
                }
            }
        }.start()
    }

    private fun fetch(): Release = http.newCall(Request.Builder().url(LATEST).header("Cache-Control", "no-cache").build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        parse(JSONObject(r.body?.string().orEmpty()))
    }

    private fun parse(o: JSONObject): Release {
        val release = Release(o.getLong("versionCode"), o.getString("versionName"), o.getString("apk"), o.getString("sha256").lowercase(),
            o.optLong("size"), o.optLong("minVersionCode"), o.optString("notes"))
        // Only Rabta's own releases on GitHub; anything else is ignored.
        require(release.apk.startsWith("$RELEASES/download/") && release.sha256.matches(Regex("[0-9a-f]{64}")))
        return release
    }

    private fun Release.json() = JSONObject().put("versionCode", code).put("versionName", name).put("apk", apk).put("sha256", sha256)
        .put("size", size).put("minVersionCode", minCode).put("notes", notes).toString()

    private var offered = WeakReference<AlertDialog>(null)
    private var offeredIn = WeakReference<Activity>(null)
    private fun offer(a: Activity, release: Release, required: Boolean) {
        if (offeredIn.get() === a && offered.get()?.isShowing == true) return
        val size = if (release.size > 0) "\n\nDownload size: ${"%.1f".format(release.size / 1048576.0)} MB" else ""
        val what = if (release.notes.isNotBlank()) "\n\n${release.notes}" else ""
        val lead = if (required) "This version of Rabta is no longer supported. Update to keep using it." else "Rabta ${release.name} is ready to install."
        offered = WeakReference(AlertDialog.Builder(a).setTitle(if (required) "Update required" else "Update available").setMessage(lead + what + size)
            .setCancelable(!required).setPositiveButton("Update") { _, _ -> start(a, release) }
            .apply { if (!required) setNegativeButton("Later", null) }.show())
        offeredIn = WeakReference(a)
    }

    private fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    private fun start(a: Activity, release: Release) {
        if (canInstall(a)) { download(a, release); return }
        waiting = release
        AlertDialog.Builder(a).setTitle("Allow Rabta to update itself")
            .setMessage("Android asks once before an app can install updates. Turn on \"Allow from this source\" for Rabta, then come back.")
            .setCancelable(release.minCode <= installed(a))
            .setPositiveButton("Open settings") { _, _ ->
                runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) }
                    .onFailure { toast(a, "Open Settings › Apps › Rabta › Install unknown apps") }
            }
            .apply { if (release.minCode <= installed(a)) setNegativeButton("Later") { _, _ -> waiting = null } }.show()
    }

    private fun download(a: Activity, release: Release) {
        val stop = AtomicBoolean(false)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(AppTheme.accent(a))
        }
        val label = RabtaUi.text(a, "Downloading Rabta ${release.name}…", 14f, color = AppTheme.sub(a)).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(RabtaUi.dp(a, 24), RabtaUi.dp(a, 8), RabtaUi.dp(a, 24), 0)
            addView(label); addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = RabtaUi.dp(a, 12) })
        }
        val dialog = AlertDialog.Builder(a).setTitle("Updating Rabta").setView(box).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> stop.set(true) }.show()
        downloading = true
        Thread {
            val result = runCatching { fetchApk(a, release, stop) { p -> a.runOnUiThread { bar.progress = p; label.text = "Downloading Rabta ${release.name}… $p%" } } }
            a.runOnUiThread {
                downloading = false
                runCatching { dialog.dismiss() }
                if (a.isDestroyed || a.isFinishing || stop.get()) return@runOnUiThread
                result.onSuccess { install(a, it) }.onFailure {
                    AlertDialog.Builder(a).setTitle("Update not downloaded").setMessage("Check your internet connection and try again.")
                        .setCancelable(release.minCode <= installed(a)).setPositiveButton("Try again") { _, _ -> download(a, release) }
                        .apply { if (release.minCode <= installed(a)) setNegativeButton("Later", null) }.show()
                }
            }
        }.start()
    }

    /** Downloads (or reuses) the APK and checks its SHA-256, package, version and signature before anything installs it. */
    private fun fetchApk(c: Context, release: Release, stop: AtomicBoolean, progress: (Int) -> Unit): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "Rabta-${release.code}.apk")
        if (file.exists() && sha256(file) == release.sha256) return file.also { verify(c, it, release) }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, file.name + ".part")
        http.newCall(Request.Builder().url(release.apk).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L; var last = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (stop.get()) { part.delete(); error("Cancelled") }
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); digest.update(buffer, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt().coerceIn(0, 100); if (p != last) { last = p; progress(p) } }
                    }
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) { part.delete(); error("Checksum mismatch") }
        }
        if (!part.renameTo(file)) error("Could not save the update")
        return file.also { verify(c, it, release) }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Set<String> =
        ((if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners else null) ?: info?.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private fun verify(c: Context, file: File, release: Release) {
        val pm = c.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: run { file.delete(); error("Not an app") }
        val theirs = signers(archive).ifEmpty { signers(pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)) }
        val ours = signers(pm.getPackageInfo(c.packageName, flags))
        // Android also refuses an update signed by another key; this check only fails earlier with a clear reason.
        if (archive.packageName != c.packageName || code(archive) != release.code || release.code <= installed(c) || (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours)) {
            file.delete(); error("This file is not a Rabta update")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(a: Activity, file: File) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
        runCatching {
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(a, "Could not open Android's installer") }
    }

    /** Removes downloads of versions that are already installed. */
    private fun cleanup(c: Context) {
        runCatching { File(c.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
    }
}
