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

    /** Launch the server if it isn't already running. Returns immediately. */
    @Synchronized
    fun ensureRunning() {
        if (proc?.isAlive == true) return
        val (server, model) = engine() ?: return
        primed = false
        runCatching {
            proc = ProcessBuilder(
                server.absolutePath,
                "-m", model.absolutePath,
                "--host", "127.0.0.1",
                "--port", PORT.toString(),
                "--ctx-size", "8192",
                "-ngl", "99",
                "--parallel", "1",
            ).apply {
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

    @Synchronized
    fun stop() {
        proc?.let {
            it.destroy() // SIGTERM; llama-server exits cleanly
            if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        proc = null
        primed = false
    }
}
