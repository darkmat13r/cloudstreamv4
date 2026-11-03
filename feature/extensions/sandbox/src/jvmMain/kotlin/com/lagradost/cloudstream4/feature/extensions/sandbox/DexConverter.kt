package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.googlecode.d2j.dex.Dex2jar
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Translates a `.cs3` (a zip of `manifest.json` + `classes.dex`) into a JVM jar.
 *
 * **Only ever called inside the conversion process**, which has no network and can write only to
 * its output directory. dex2jar is a large parser pointed at hostile bytes, so a crafted dex must
 * not be able to do anything useful even if it finds a bug. No plugin code runs here: translation
 * reads the dex and never loads a class from it.
 */
internal object DexConverter {

    fun convert(cs3: File, output: File) {
        val dex = ZipFile(cs3).use { zip ->
            // Refused rather than ignored: quietly dropping classes2.dex would produce a jar that
            // loads and then fails at some arbitrary later call.
            val extra = zip.entries().asSequence().map { it.name }
                .firstOrNull { it.matches(EXTRA_DEX) }
            check(extra == null) { "multidex extension is not supported: $extra" }
            val entry = checkNotNull(zip.getEntry(DEX_ENTRY)) { "no $DEX_ENTRY in ${cs3.name}" }
            zip.getInputStream(entry).use { it.readBounded(MAX_DEX_BYTES) }
        }
        output.delete()
        Dex2jar.from(dex)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(false)
            .optimizeSynchronized(true)
            .printIR(false)
            .noCode(false)
            .skipExceptions(false)
            // Both measured on real plugins (`KMP-18`). dex2jar otherwise rewrites `-` to `_` in names,
            // so `kotlin.Result.constructor-impl` becomes a method that does not exist, and every
            // `runCatching` throws `NoSuchMethodError` the first time it runs. 30 of 53 sampled jars.
            .dontSanitizeNames(true)
            // A dex of format 037 or later becomes class version 52, which the JVM refuses to verify
            // without stack maps. Computed from the dex's own hierarchy, so no class is ever loaded.
            .computeFrames(true)
            .to(output.toPath())
        check(output.isFile && output.length() > 0) { "dex2jar produced no output" }
    }

    /** Reads at most [limit] bytes, because a zip entry's declared size is attacker-supplied. */
    private fun InputStream.readBounded(limit: Int): ByteArray {
        val bytes = readNBytes(limit + 1)
        check(bytes.size <= limit) { "$DEX_ENTRY is larger than $limit bytes" }
        return bytes
    }

    private const val DEX_ENTRY = "classes.dex"
    private const val MAX_DEX_BYTES = 32 * 1024 * 1024
    private val EXTRA_DEX = Regex("classes[0-9]+\\.dex")
}
