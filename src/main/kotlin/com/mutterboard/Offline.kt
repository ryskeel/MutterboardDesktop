package com.mutterboard

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Locations of the offline engine pieces under XDG data
 * (~/.local/share/mutterboard): the sherpa-onnx CLI + Parakeet model for
 * transcription, and llama.cpp's llama-server + Qwen3 for the cleanup pass.
 * Everything here is provisioned by [OfflineDownloader]; the app never bundles
 * the models (≈1.5 GB).
 */
object OfflinePaths {
    val dataDir: File = run {
        val xdg = System.getenv("XDG_DATA_HOME")
        val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".local/share")
        File(base, "mutterboard")
    }

    val sherpaDir = File(dataDir, "sherpa")
    val sherpaBin = File(sherpaDir, "bin/sherpa-onnx-offline")
    val sherpaLib = File(sherpaDir, "lib")
    val parakeetDir = File(dataDir, "models/parakeet")
    val llamaDir = File(dataDir, "llama")
    val llamaServer = File(llamaDir, "llama-server")
    val qwenModel = File(dataDir, "models/qwen3-0.6b-q8_0.gguf")

    // The preferred refiner: Qwen3-4B on the GPU via llama.cpp's Vulkan build.
    // The CPU build + 0.6B above stay as the fallback for machines (or
    // moments) where Vulkan can't run.
    val llamaVulkanDir = File(dataDir, "llama-vulkan")
    val llamaVulkanServer = File(llamaVulkanDir, "llama-server")
    val qwen4bModel = File(dataDir, "models/qwen3-4b-instruct-2507-q8_0.gguf")

    fun transcriberReady(): Boolean =
        sherpaBin.canExecute() && File(parakeetDir, "encoder.int8.onnx").exists() &&
            File(parakeetDir, "tokens.txt").exists()

    fun refinerReady(): Boolean =
        (llamaVulkanServer.canExecute() && qwen4bModel.exists()) ||
            (llamaServer.canExecute() && qwenModel.exists())

    fun allReady(): Boolean = transcriberReady() && refinerReady()
}

/**
 * Fetches and unpacks the offline engine: four artifacts, downloaded in
 * sequence with a single 0..1 progress across the combined size. Runs on its
 * own thread; progress and errors are reported via callbacks (called off the
 * UI thread — the controller writes them into snapshot state).
 */
class OfflineDownloader(
    private val onProgress: (Float, String) -> Unit,
    private val onDone: (error: String?) -> Unit,
) {
    private data class Artifact(val url: String, val dest: String, val bytes: Long)

    // Sizes are approximate (for the progress bar only). Versions are pinned:
    // these are the builds the integration was verified against.
    private val artifacts = listOf(
        Artifact(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-v1.13.4-linux-x64-shared.tar.bz2",
            "sherpa.tar.bz2", 28_000_000,
        ),
        Artifact(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
            "parakeet.tar.bz2", 483_000_000,
        ),
        Artifact(
            "https://github.com/ggml-org/llama.cpp/releases/download/b9895/llama-b9895-bin-ubuntu-x64.tar.gz",
            "llama.tar.gz", 17_000_000,
        ),
        Artifact(
            "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            "qwen3-0.6b-q8_0.gguf", 640_000_000,
        ),
        Artifact(
            "https://github.com/ggml-org/llama.cpp/releases/download/b9895/llama-b9895-bin-ubuntu-vulkan-x64.tar.gz",
            "llama-vulkan.tar.gz", 31_000_000,
        ),
        Artifact(
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q8_0.gguf",
            "qwen3-4b-instruct-2507-q8_0.gguf", 4_280_000_000,
        ),
    )

    fun start() {
        Thread {
            try {
                val downloads = File(OfflinePaths.dataDir, "downloads").apply { mkdirs() }
                val total = artifacts.sumOf { it.bytes }.toFloat()
                var before = 0L
                for (a in artifacts) {
                    val dest = File(downloads, a.dest)
                    if (!dest.exists() || dest.length() < a.bytes / 2) {
                        download(a.url, dest) { got ->
                            onProgress((before + got) / total, "Downloading ${a.dest}…")
                        }
                    }
                    before += a.bytes
                }
                onProgress(1f, "Unpacking…")
                unpack(downloads)
                onDone(if (OfflinePaths.allReady()) null else "Unpacking failed — try again.")
            } catch (e: Exception) {
                onDone("Download failed: ${e.message}")
            }
        }.apply { isDaemon = true; name = "offline-downloader" }.start()
    }

    private fun download(url: String, dest: File, progress: (Long) -> Unit) {
        val client = OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw RuntimeException("empty response for $url")
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
                    progress(got)
                }
            }
            if (!tmp.renameTo(dest)) throw RuntimeException("could not finalize ${dest.name}")
        }
    }

    /** Unpacks via the system tar (this is a Linux desktop app, like pw-record). */
    private fun unpack(downloads: File) {
        val data = OfflinePaths.dataDir
        File(data, "models").mkdirs()

        if (!OfflinePaths.sherpaBin.exists()) {
            tar(downloads, "sherpa.tar.bz2", data)
            File(data, "sherpa-onnx-v1.13.4-linux-x64-shared").renameTo(OfflinePaths.sherpaDir)
        }
        if (!File(OfflinePaths.parakeetDir, "encoder.int8.onnx").exists()) {
            tar(downloads, "parakeet.tar.bz2", File(data, "models"))
            File(data, "models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8")
                .renameTo(OfflinePaths.parakeetDir)
        }
        if (!OfflinePaths.llamaServer.exists()) {
            OfflinePaths.llamaDir.mkdirs()
            tar(downloads, "llama.tar.gz", OfflinePaths.llamaDir)
            // The release tar nests everything one directory down (llama-bXXXX/).
            OfflinePaths.llamaDir.listFiles()?.singleOrNull { it.isDirectory }?.let { nested ->
                nested.listFiles()?.forEach { it.renameTo(File(OfflinePaths.llamaDir, it.name)) }
                nested.delete()
            }
        }
        if (!OfflinePaths.llamaVulkanServer.exists()) {
            OfflinePaths.llamaVulkanDir.mkdirs()
            tar(downloads, "llama-vulkan.tar.gz", OfflinePaths.llamaVulkanDir)
            OfflinePaths.llamaVulkanDir.listFiles()?.singleOrNull { it.isDirectory }?.let { nested ->
                nested.listFiles()?.forEach { it.renameTo(File(OfflinePaths.llamaVulkanDir, it.name)) }
                nested.delete()
            }
        }
        if (!OfflinePaths.qwenModel.exists()) {
            File(downloads, "qwen3-0.6b-q8_0.gguf").renameTo(OfflinePaths.qwenModel)
        }
        if (!OfflinePaths.qwen4bModel.exists()) {
            File(downloads, "qwen3-4b-instruct-2507-q8_0.gguf").renameTo(OfflinePaths.qwen4bModel)
        }
        OfflinePaths.sherpaBin.setExecutable(true)
        OfflinePaths.llamaServer.setExecutable(true)
        OfflinePaths.llamaVulkanServer.setExecutable(true)
    }

    private fun tar(dir: File, name: String, into: File) {
        val p = ProcessBuilder("tar", "xf", File(dir, name).absolutePath, "-C", into.absolutePath)
            .redirectErrorStream(true)
            .start()
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
            throw RuntimeException("tar failed for $name")
        }
    }
}
