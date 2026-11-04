package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/**
 * What a hostile plugin, or code that took over the media host, would try. Runs inside the sandbox.
 *
 * Reports only what happened, in a word or a count, and never what was read: a hole found here must not put
 * someone's key in a test report. `allowed` means the kernel let it through, `denied` that it refused,
 * and anything else names how the attempt failed without the sandbox being the reason.
 */
internal object EscapeAttempts {
    fun run(attempt: String): String {
        val verb = attempt.substringBefore('|')
        val argument = attempt.substringAfter('|', "")
        return try {
            when (verb) {
                "read" -> File(argument).inputStream().use { it.read() }.let { "allowed" }
                "list" -> File(argument).list()?.let { "allowed" } ?: "denied"
                "stat" -> if (File(argument).exists()) "exists" else "absent"
                "write" -> File(argument).writeText("written by a plugin").let { "allowed" }
                "append" -> File(argument).appendText("tampered").let { "allowed" }
                "delete" -> if (File(argument).delete()) "allowed" else "denied"
                "exec" -> ProcessBuilder(argument).start().waitFor().let { "allowed" }
                "connect" -> connect(argument)
                "connect-direct" -> connect(argument, Proxy.NO_PROXY)
                "udp" -> DatagramSocket().use { socket ->
                    val address = InetAddress.getByName(argument.substringBefore('/'))
                    socket.send(DatagramPacket(ByteArray(1), 1, address, argument.substringAfter('/').toInt()))
                    "allowed"
                }
                "unix" -> SocketChannel.open(StandardProtocolFamily.UNIX).use {
                    it.connect(UnixDomainSocketAddress.of(argument))
                    "allowed"
                }
                "listen" -> java.net.ServerSocket(0).use { "allowed" }
                "hardlink-read" -> {
                    val (source, link) = argument.split('>')
                    Files.createLink(File(link).toPath(), File(source).toPath())
                    File(link).inputStream().use { it.read() }
                    "allowed"
                }
                "symlink-read" -> {
                    val (source, link) = argument.split('>')
                    Files.createSymbolicLink(File(link).toPath(), File(source).toPath())
                    File(link).inputStream().use { it.read() }
                    "allowed"
                }
                "move-out" -> {
                    val (source, target) = argument.split('>')
                    Files.move(File(source).toPath(), File(target).toPath(), StandardCopyOption.ATOMIC_MOVE)
                    "allowed"
                }
                "load" -> System.load(argument).let { "allowed" }
                "processes" -> ProcessHandle.allProcesses().count().toString()
                "process-command" -> ProcessHandle.of(argument.toLong()).flatMap { it.info().command() }
                    .map { "visible" }.orElse("hidden")
                "signal" -> ProcessHandle.of(argument.toLong()).map { it.destroyForcibly() }.orElse(false).toString()
                "property" -> System.getProperty(argument).orEmpty()
                "hostname" -> InetAddress.getLocalHost().hostName
                "addresses" -> NetworkInterface.getNetworkInterfaces().toList()
                    .count { it.hardwareAddress != null }.toString()
                "cwd" -> File("").absolutePath
                "spin" -> repeat(argument.toInt()) { thread(isDaemon = true) { while (true) Math.sqrt(System.nanoTime().toDouble()) } }.let { "spinning" }
                "heap" -> mutableListOf<ByteArray>().let { list -> while (true) list += ByteArray(MEGABYTE) }.let { "unreachable" }
                "direct" -> mutableListOf<java.nio.ByteBuffer>().let { list -> while (true) list += java.nio.ByteBuffer.allocateDirect(MEGABYTE) }.let { "unreachable" }
                "threads" -> {
                    var started = 0
                    try {
                        while (started < argument.toInt()) {
                            thread(isDaemon = true) { Thread.sleep(Long.MAX_VALUE) }
                            started++
                        }
                    } catch (_: Throwable) {
                        Unit
                    }
                    "threads:$started"
                }
                "big-file" -> File(argument).outputStream().use { out ->
                    val chunk = ByteArray(MEGABYTE)
                    var written = 0
                    try {
                        while (written < MAX_FILE_MEGABYTES) {
                            out.write(chunk)
                            written++
                        }
                    } catch (_: java.io.IOException) {
                        Unit
                    }
                    "written:$written"
                }
                "many-files" -> {
                    File(argument).mkdirs()
                    repeat(MAX_FILE_MEGABYTES) { File(argument, "f$it").writeBytes(ByteArray(MEGABYTE)) }
                    "written"
                }
                else -> "unknown"
            }
        } catch (failure: Throwable) {
            classify(failure)
        }
    }

    private fun connect(argument: String, proxy: Proxy? = null): String = (proxy?.let(::Socket) ?: Socket()).use { socket ->
        socket.connect(
            InetSocketAddress(argument.substringBeforeLast('/'), argument.substringAfterLast('/').toInt()),
            CONNECT_TIMEOUT_MILLIS,
        )
        "allowed"
    }

    private fun classify(failure: Throwable): String {
        val text = generateSequence(failure) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        return when {
            "not permitted" in text || "denied" in text.lowercase() -> "denied"
            "ruleset" in text -> "refused-by-gate"
            "refused" in text -> "reached:refused"
            "timed out" in text -> "no-answer"
            "No such file" in text -> "absent"
            else -> "failed:${failure::class.java.simpleName}"
        }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 2_000
    private const val MEGABYTE = 1024 * 1024
    private const val MAX_FILE_MEGABYTES = 600
}

/** Runs [EscapeAttempts] as the media host's process, because a media host has no plugins to load one. */
internal object EscapeAttemptsMain {
    @JvmStatic
    fun main(args: Array<String>) {
        File("attempts.txt").readLines().forEach { println("RESULT\t$it\t${EscapeAttempts.run(it)}") }
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }
}
