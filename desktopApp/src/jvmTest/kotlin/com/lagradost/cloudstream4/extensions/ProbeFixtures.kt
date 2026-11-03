package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * A plugin that uses `java.io.File`, so the real scanner gives it a real `filesystem` warning, and that leaves
 * a mark the moment any of its code runs. The search box is how a test asks it to try something from inside
 * the real sandbox: each attempt comes back as a result named `allowed` or `denied`.
 *
 * Written here, for this. No third-party code is involved.
 */
@CloudstreamPlugin
class ProbePlugin : BasePlugin() {
    override fun load() {
        // A host points user.home at its own directory, so this is the one place this code can write.
        File(System.getProperty("user.home"), PROBE_MARK).writeText("ran")
        registerMainAPI(ProbeApi())
    }
}

class ProbeApi : MainAPI() {
    override var name = "Probe"
    override var mainUrl = "https://probe.invalid"
    override val supportedTypes = setOf(TvType.Others)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val verb = query.substringBefore(':')
        val argument = query.substringAfter(':', "")
        val outcome = try {
            when (verb) {
                "read" -> File(argument).readText().let { "allowed" }
                "write" -> File(argument).writeText("forged").let { "allowed" }
                "append" -> File(argument).appendText("forged").let { "allowed" }
                "delete" -> if (File(argument).delete()) "allowed" else "denied"
                "rename" -> if (File(argument).renameTo(File("$argument.moved"))) "allowed" else "denied"
                "replace" -> {
                    val forged = File(System.getProperty("user.home"), "forged").apply { writeText("forged") }
                    Files.move(forged.toPath(), File(argument).toPath())
                    "allowed"
                }
                "readlink" -> {
                    val link = File(System.getProperty("user.home"), "link-${argument.hashCode()}").toPath()
                    Files.deleteIfExists(link)
                    Files.createSymbolicLink(link, File(argument).toPath())
                    Files.readAllBytes(link).let { "allowed" }
                }
                "hardlink" -> {
                    // A second name inside its own directory for the same file, which the policy would then allow.
                    val link = File(System.getProperty("user.home"), "hard-${argument.hashCode()}").toPath()
                    Files.deleteIfExists(link)
                    Files.createLink(link, File(argument).toPath())
                    "allowed"
                }
                "stat" -> File(argument).length().toString()
                "list" -> if (File(argument).list() != null) "allowed" else "denied"
                else -> "unknown"
            }
        } catch (denied: Exception) {
            "denied"
        }
        val item = newMovieSearchResponse(outcome, "https://probe.invalid/x", TvType.Others, fix = false)
        return newSearchResponseList(listOf(item), false)
    }
}

/** Touches nothing the scanner warns about, so nobody has to be asked. */
@CloudstreamPlugin
class PlainPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PlainApi())
    }
}

class PlainApi : MainAPI() {
    override var name = "Plain"
    override var mainUrl = "https://plain.invalid"
    override val supportedTypes = setOf(TvType.Others)
}

/** Names a process API, which the scanner blocks. Only ever read as bytes. */
class BlockedFixture {
    fun run() = ProcessBuilder("/bin/true")
}

internal const val PROBE_MARK = "probe-ran"

/** A jar holding those classes as compiled. [padding] changes its bytes and so its hash, and nothing else. */
internal fun writeFixtureJar(target: File, vararg classes: Class<*>, padding: String? = null) {
    target.parentFile.mkdirs()
    JarOutputStream(target.outputStream()).use { out ->
        classes.forEach { type ->
            val path = type.name.replace('.', '/') + ".class"
            out.putNextEntry(JarEntry(path))
            type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(out) }
            out.closeEntry()
        }
        if (padding != null) {
            out.putNextEntry(JarEntry("padding.txt"))
            out.write(padding.toByteArray())
            out.closeEntry()
        }
    }
}

internal fun writeProbeJar(target: File, padding: String? = null) =
    writeFixtureJar(target, ProbePlugin::class.java, ProbeApi::class.java, padding = padding)

internal fun sha256Of(file: File): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
