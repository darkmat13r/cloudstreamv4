package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

/**
 * Writes the stack maps of a converted jar, which the JVM needs to verify class version 52.
 *
 * dex2jar's own pass cannot be used (`BUG-18`). When its walk over the dex's classes runs out, it asks ASM,
 * which looks both types up with `Class.forName`. For a class that is neither in the dex nor on this classpath,
 * such as one of `:app`'s, that throws, and dex2jar 2.4.38 catches it and returns the literal `java/util/Object`,
 * a class that does not exist. The JVM then throws `NoClassDefFoundError: java/util/Object` while verifying the
 * class. Its walk is also wrong with no lookup at all: it never compares the second type itself, so a class
 * joined with its own parent can come out one level too wide.
 *
 * Nothing here loads a class. A plugin's types come from the jar and everything else from `.class` files
 * read as data, so the conversion process still never defines a class it was handed.
 */
internal object StackMapFrames {

    /** Rewrites [jar] in place. A class that cannot be written fails the conversion, naming the class. */
    fun rewrite(jar: File) {
        val staging = File(jar.parentFile, "${jar.name}$STAGING_SUFFIX")
        try {
            ZipFile(jar).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val classes = entries.filter { it.name.endsWith(CLASS_SUFFIX) }
                    .associateWith { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                val hierarchy = ClassHierarchy(
                    local = classes.values.map(::ClassReader).associate { it.className to it.superName },
                    host = ::hostClass,
                )
                ZipOutputStream(staging.outputStream().buffered()).use { out ->
                    for (entry in entries) {
                        out.putNextEntry(ZipEntry(entry.name))
                        val bytes = classes[entry]
                        if (bytes != null) {
                            out.write(withFrames(entry.name, bytes, hierarchy))
                        } else {
                            zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
            Files.move(staging.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    private fun withFrames(name: String, bytes: ByteArray, hierarchy: ClassHierarchy): ByteArray = try {
        val writer = object : ClassWriter(COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String) = hierarchy.commonSuperclass(type1, type2)
        }
        ClassReader(bytes).accept(writer, ClassReader.SKIP_FRAMES)
        writer.toByteArray()
    } catch (failure: RuntimeException) {
        throw IllegalStateException("cannot write stack maps for $name: $failure", failure)
    }

    /**
     * The class a host would get for [type] from its own classpath, or null. Read through the same
     * loader a host's plugin loader has as its parent, because the conversion process runs the same
     * classpath as a host.
     */
    private fun hostClass(type: String): ClassHierarchy.HostClass? {
        val loader = StackMapFrames::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        return loader.getResourceAsStream("$type$CLASS_SUFFIX")?.use { ClassHierarchy.HostClass(ClassReader(it).superName) }
    }

    private const val CLASS_SUFFIX = ".class"
    private const val STAGING_SUFFIX = ".frames"
}

/**
 * The superclass chains the JVM's verifier will find, as far as they can be known without loading anything.
 *
 * @param local each of the plugin's own classes and its superclass.
 * @param host the superclass of a class on the host's classpath, or null when there is none of that name. A
 *   class that is found with no superclass is `java/lang/Object`. Asked first, because a host loads
 *   parent-first: a plugin's copy of a host class is never the one that runs.
 */
internal class ClassHierarchy(
    private val local: Map<String, String?>,
    private val host: (String) -> HostClass?,
) {
    class HostClass(val superName: String?)

    private val hostClasses = HashMap<String, HostClass?>()

    private fun superclass(type: String): String? {
        val found = hostClasses.getOrPut(type) { host(type) }
        return if (found != null) found.superName else local[type]
    }

    /** [type] and its superclasses, nearest first. Stops at one that cannot be found, or at a cycle. */
    private fun chain(type: String): List<String> {
        val seen = LinkedHashSet<String>()
        var current: String? = type
        while (current != null && seen.add(current)) current = superclass(current)
        return seen.toList()
    }

    /**
     * The nearest class both types extend.
     *
     * - **A class it cannot find ends its chain, and is itself still an answer.** Two classes extending the
     *   same missing class share that name without it having to be found.
     * - **Otherwise `java/lang/Object`, which is always true.** If the real answer is above a missing class
     *   and code then uses the value as that type, the verifier refuses the class. It would have needed the
     *   missing class to get that far anyway.
     * - **An interface needs no case of its own.** Its superclass is `java/lang/Object`, which is the answer,
     *   and the verifier accepts any reference where an interface is expected.
     */
    fun commonSuperclass(type1: String, type2: String): String {
        if (type1 == type2) return type1
        val ancestors = chain(type1).toHashSet()
        return chain(type2).firstOrNull { it in ancestors } ?: OBJECT
    }

    private companion object {
        const val OBJECT = "java/lang/Object"
    }
}
