package com.mutterboard

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Single-instance guard over a Unix domain socket in $XDG_RUNTIME_DIR.
 *
 * Only one Mutterboard can meaningfully run per session: the tray item, the
 * global shortcut binding, and llama-server's port are all session-wide, so a
 * second process fights the first over each of them (and tears the shared
 * shortcut down when it quits). The first instance binds the socket and holds
 * it for its lifetime; every later launch connects, asks it to show the
 * settings window, and exits — so clicking the app icon again behaves like
 * "open Mutterboard", not "start another one".
 *
 * $XDG_RUNTIME_DIR is per-login and wiped at logout, which handles most stale
 * sockets; a leftover from a crash is detected by the failed connect and
 * reclaimed.
 */
object SingleInstance {
    private val socketPath: Path = run {
        val runtime = System.getenv("XDG_RUNTIME_DIR")
        val base = if (!runtime.isNullOrBlank()) Path.of(runtime)
        else Path.of(System.getProperty("java.io.tmpdir"))
        // Under Flatpak, most of $XDG_RUNTIME_DIR is a per-instance tmpfs;
        // only app/$FLATPAK_ID is shared between launches of the same app,
        // so the socket must live there to be seen by a second launch.
        val flatpakId = System.getenv("FLATPAK_ID")
        val dir = if (flatpakId.isNullOrBlank()) base else base.resolve("app").resolve(flatpakId)
        dir.resolve("mutterboard.sock")
    }

    /**
     * Try to become the primary instance. Returns true when this process now
     * owns the socket ([onShowRequest] will fire, on a background thread, each
     * time a later launch knocks). Returns false when a live instance already
     * exists — it has been asked to show its window, and the caller should
     * simply exit.
     */
    fun acquire(onShowRequest: () -> Unit): Boolean {
        if (Files.exists(socketPath)) {
            try {
                SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use {
                    // Connecting at all proves a live instance; the write can
                    // lose a race with the server closing its end (EPIPE), and
                    // that must not be read as "nobody home".
                    runCatching { it.write(ByteBuffer.wrap("show\n".toByteArray())) }
                }
                return false
            } catch (e: Exception) {
                // Nobody answering: a crashed instance left the file behind.
                println("[instance] knock failed (${e::class.simpleName}: ${e.message}); reclaiming socket")
                runCatching { Files.deleteIfExists(socketPath) }
            }
        }
        val server = try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                .bind(UnixDomainSocketAddress.of(socketPath))
        } catch (e: Exception) {
            // Racing another launch or an unwritable runtime dir; better to
            // run un-guarded than not at all.
            println("[instance] guard unavailable (${e.message}); continuing without it")
            return true
        }
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching { Files.deleteIfExists(socketPath) } },
        )
        Thread {
            while (true) {
                try {
                    server.accept().use { ch ->
                        // Drain the knock before closing so the client's write
                        // completes; closing first EPIPEs it.
                        runCatching { ch.read(ByteBuffer.allocate(16)) }
                        onShowRequest()
                    }
                } catch (e: Exception) {
                    println("[instance] listener stopped: ${e.message}")
                    break
                }
            }
        }.apply { isDaemon = true; name = "single-instance" }.start()
        println("[instance] primary — listening on $socketPath")
        return true
    }
}
