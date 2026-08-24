package com.mutterboard

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the local llama.cpp server that runs the offline cleanup model.
 * Preferred engine is Qwen3-4B on the GPU (llama.cpp Vulkan build) — measured
 * 0.2-1.2s per cleanup on this machine's RTX 4070 — falling back to the CPU
 * build with Qwen3-0.6B if Vulkan can't run.
 *
 * Started lazily when a recording begins in offline mode and kept warm after.
 * `--parallel 1` pins every request to one slot so the ~1400-token system
 * prompt stays prefix-cached; a prime request right after startup (see the
 * controller) pays that prompt eval once, off the critical path.
 */
object LlamaServer {
    const val PORT = 8971
    const val BASE_URL = "http://127.0.0.1:$PORT/v1"
    const val MODEL = "local-qwen"

    private var proc: Process? = null
    private var primed = false
    @Volatile private var cpuFallback = false
    // Set by an explicit unload (tray "Unload model"), so an in-flight
    // awaitHealthy() can't relaunch the server we just freed the GPU of.
    // Cleared by the next ensureRunning(), i.e. the next dictation.
    @Volatile private var suspended = false
    private val http = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /** The best available binary+model pair, or null when nothing is provisioned. */
    private fun engine(): Pair<File, File>? = when {
        !cpuFallback && OfflinePaths.llamaVulkanServer.canExecute() &&
            OfflinePaths.qwen4bModel.exists() ->
            OfflinePaths.llamaVulkanServer to OfflinePaths.qwen4bModel
        OfflinePaths.llamaServer.canExecute() && OfflinePaths.qwenModel.exists() ->
            OfflinePaths.llamaServer to OfflinePaths.qwenModel
        else -> null
    }

    /** True while the model is resident on the GPU. */
    @Synchronized
    fun isRunning(): Boolean = proc?.isAlive == true

    /** Launch the server if it isn't already running. Returns immediately. */
    @Synchronized
    fun ensureRunning() {
        suspended = false
        if (proc?.isAlive == true) return
        val (server, model) = engine() ?: return
        primed = false
        runCatching {
            val args = buildList {
                add(server.absolutePath)
                add("-m"); add(model.absolutePath)
                add("--host"); add("127.0.0.1")
                add("--port"); add(PORT.toString())
                add("--ctx-size"); add("8192")
                add("-ngl"); add("99")
                add("--parallel"); add("1")
                // Only the Vulkan build has devices to choose between.
                if (server == OfflinePaths.llamaVulkanServer) {
                    chosenDevice?.let { add("--device"); add(it) }
                }
            }
            proc = ProcessBuilder(args).apply {
                environment()["LD_LIBRARY_PATH"] = server.parentFile.absolutePath
                redirectOutput(File(OfflinePaths.dataDir, "llama-server.log"))
                redirectErrorStream(true)
            }.start()
            println("[llama] started ${server.parentFile.name}/${model.name} pid=${proc?.pid()}")
        }.onFailure { println("[llama] start failed: ${it.message}") }
    }

    /** True the first time after each server start; the caller then primes the cache. */
    @Synchronized
    fun shouldPrime(): Boolean {
        if (primed || proc?.isAlive != true) return false
        primed = true
        return true
    }

    /**
     * Block (on a background thread) until the server answers /health, up to
     * [timeoutMs]. Relaunches a dead server once; if it dies again the GPU
     * engine is abandoned for the CPU fallback for the rest of the session.
     */
    fun awaitHealthy(timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var relaunches = 0
        while (System.currentTimeMillis() < deadline) {
            // Deliberately unloaded: give up rather than resurrect it. The
            // caller falls back to the raw transcript, which is a fine outcome.
            if (suspended) return false
            if (proc?.isAlive != true) {
                when (relaunches) {
                    0 -> {}
                    1 -> if (!cpuFallback) {
                        println("[llama] GPU engine keeps dying; falling back to CPU")
                        cpuFallback = true
                    } else return false
                    else -> return false
                }
                relaunches++
                ensureRunning()
            }
            val ok = runCatching {
                http.newCall(Request.Builder().url("http://127.0.0.1:$PORT/health").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(200)
        }
        return false
    }

    /**
     * Free the GPU on request (tray "Unload model"), leaving the weights on
     * disk. Stays down until the next [ensureRunning] — i.e. the next
     * dictation — rather than being warmed back up behind the user's back.
     */
    @Synchronized
    fun unload() {
        suspended = true
        stop()
        println("[llama] unloaded on request; VRAM released")
    }

    @Synchronized
    fun stop() {
        proc?.let {
            it.destroy() // SIGTERM; llama-server exits cleanly
            if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        proc = null
        primed = false
    }

    /**
     * A Vulkan device as llama.cpp sees it: the id to pass to `--device`, the
     * human name, and its reported memory.
     */
    private data class VkDevice(val id: String, val name: String, val memMiB: Int) {
        /** iGPUs report the CPU's own name and claim shared system RAM as VRAM. */
        val isIntegrated: Boolean = INTEGRATED.any { name.contains(it, ignoreCase = true) }

        private companion object {
            val INTEGRATED = listOf(
                "Processor", "RADV RAPHAEL", "RADV RENOIR", "RADV PHOENIX",
                "llvmpipe", "lavapipe", "SwiftShader",
            )
        }
    }

    /**
     * The device llama.cpp should run on, chosen once per session.
     *
     * Left to itself llama.cpp takes device 0, and that ordering is not stable:
     * a driver update reshuffled it once already, silently moving the model
     * onto the integrated GPU at a fifteenth of the speed. Picking by memory
     * alone doesn't help either — the iGPU here claims 16 GB of shared system
     * RAM against the discrete card's 12 GB — so integrated devices are ruled
     * out first and the largest of what's left wins.
     */
    private val chosenDevice: String? by lazy {
        val devices = listDevices()
        if (devices.isEmpty()) return@lazy null
        warnIfDiscreteGpuMissing(devices)
        val best = devices.filterNot { it.isIntegrated }.maxByOrNull { it.memMiB }
            ?: return@lazy null
        println("[llama] using ${best.id} — ${best.name} (${best.memMiB} MiB)")
        best.id
    }

    /** Ask the Vulkan build what it can see. Empty on any failure. */
    private fun listDevices(): List<VkDevice> = runCatching {
        val server = OfflinePaths.llamaVulkanServer
        if (!server.canExecute()) return emptyList()
        val proc = ProcessBuilder(server.absolutePath, "--list-devices").apply {
            environment()["LD_LIBRARY_PATH"] = server.parentFile.absolutePath
            redirectErrorStream(true)
        }.start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(20, TimeUnit.SECONDS)) proc.destroyForcibly()
        // "  Vulkan0: NVIDIA GeForce RTX 4070 SUPER (12282 MiB, 11086 MiB free)"
        val line = Regex("""^\s*(\S+):\s+(.+?)\s+\((\d+)\s*MiB""", RegexOption.MULTILINE)
        line.findAll(out).map {
            VkDevice(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt())
        }.toList()
    }.getOrDefault(emptyList())

    /**
     * The failure this exists for is silent: when the Flatpak's NVIDIA driver
     * extension falls behind the host driver, the card simply stops appearing
     * inside the sandbox and cleanup crawls along on the iGPU instead. The
     * kernel still lists the card in /sys, so the mismatch is detectable —
     * say so rather than letting the user wonder why dictation got slow.
     */
    private fun warnIfDiscreteGpuMissing(devices: List<VkDevice>) {
        if (devices.any { !it.isIntegrated }) return
        val drivers = runCatching {
            File("/sys/class/drm").listFiles().orEmpty()
                .mapNotNull { File(it, "device/uevent").takeIf { u -> u.canRead() } }
                .flatMap { it.readLines() }
                .filter { it.startsWith("DRIVER=") }
                .map { it.removePrefix("DRIVER=") }
                .toSet()
        }.getOrDefault(emptySet())
        val vendor = when {
            "nvidia" in drivers -> "NVIDIA"
            else -> return
        }
        println("[llama] $vendor GPU present on the host but invisible to Vulkan — stale driver extension")
        Notifier.notify(
            "Running on the integrated GPU",
            "Mutterboard can't see your $vendor card, so cleaning up dictation " +
                "will be slow. Its Flatpak graphics driver is out of date after " +
                "a system update — running flatpak update usually fixes it.",
        )
    }
}
