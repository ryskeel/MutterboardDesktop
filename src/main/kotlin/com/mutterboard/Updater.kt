package com.mutterboard

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** The About card's update flow, observed by Compose via the controller. */
sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    object Installing : UpdateState
    data class Installed(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from GitHub releases: check the latest release, download its
 * .flatpak bundle, and install it through the host's flatpak. Inside the
 * sandbox that goes via `flatpak-spawn --host`, which the manifest allows with
 * --talk-name=org.freedesktop.Flatpak. The new version takes effect on the
 * next launch, so the flow ends with a restart.
 */
object Updater {
    private const val REPO = "ryskeel/MutterboardDesktop"
    private const val APP_ID = "io.github.ryskeel.Mutterboard"

    /** Stamped into the launcher by jpackage; "dev" under a bare `gradlew run`. */
    val currentVersion: String = System.getProperty("jpackage.app-version") ?: "dev"

    data class Release(val version: String, val bundleUrl: String, val bundleBytes: Long)

    private fun inSandbox() = File("/.flatpak-info").exists()

    fun checkLatest(onResult: (Release?, error: String?) -> Unit) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .callTimeout(30, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val json = JSONObject(resp.body!!.string())
                    val version = json.getString("tag_name").removePrefix("v")
                    val assets = json.getJSONArray("assets")
                    var url: String? = null
                    var size = 0L
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".flatpak")) {
                            url = a.getString("browser_download_url")
                            size = a.getLong("size")
                        }
                    }
                    if (url == null) throw RuntimeException("release $version has no .flatpak")
                    onResult(Release(version, url, size), null)
                }
            } catch (e: Exception) {
                onResult(null, "Update check failed: ${e.message}")
            }
        }.apply { isDaemon = true; name = "update-check" }.start()
    }

    /** Numeric dotted-version compare; a "dev" build never self-updates. */
    fun isNewer(candidate: String): Boolean {
        if (currentVersion == "dev") return false
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(currentVersion)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Downloads the bundle into the cache dir (which lives in the real home,
     * so the host's flatpak can read it) and installs it. Callbacks arrive on
     * a worker thread.
     */
    fun downloadAndInstall(
        release: Release,
        cacheDir: File,
        onProgress: (Float) -> Unit,
        onInstalling: () -> Unit,
        onDone: (error: String?) -> Unit,
    ) {
        Thread {
            try {
                cacheDir.mkdirs()
                val bundle = File(cacheDir, "Mutterboard-${release.version}.flatpak")
                download(release, bundle, onProgress)
                onInstalling()
                install(bundle)
                bundle.delete()
                onDone(null)
            } catch (e: Exception) {
                onDone("Update failed: ${e.message}")
            }
        }.apply { isDaemon = true; name = "update-install" }.start()
    }

    private fun download(release: Release, dest: File, onProgress: (Float) -> Unit) {
        val client = OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(release.bundleUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("empty response")
            val total = if (release.bundleBytes > 0) release.bundleBytes.toFloat() else Float.MAX_VALUE
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var got = 0L
                val input = body.byteStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    onProgress((got / total).coerceIn(0f, 1f))
                }
            }
            if (!tmp.renameTo(dest)) throw RuntimeException("could not finalize download")
        }
    }

    private fun install(bundle: File) {
        val flatpak = listOf(
            "flatpak", "install", "--user", "--noninteractive", "--reinstall",
            bundle.absolutePath,
        )
        val cmd = if (inSandbox()) listOf("flatpak-spawn", "--host") + flatpak else flatpak
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            throw RuntimeException("flatpak install timed out")
        }
        if (p.exitValue() != 0) {
            throw RuntimeException(output.trim().lines().lastOrNull() ?: "flatpak install failed")
        }
    }

    /**
     * Launches the freshly installed version, detached from this process (the
     * nohup grandchild survives the sandbox going away), then the caller
     * exits. The one-second sleep lets the single-instance socket close first.
     */
    fun relaunch() {
        if (!inSandbox()) return // dev build: just quit, reopen by hand
        runCatching {
            ProcessBuilder(
                "flatpak-spawn", "--host", "sh", "-c",
                "nohup sh -c 'sleep 1; flatpak run $APP_ID' >/dev/null 2>&1 &",
            ).start()
        }
    }
}
