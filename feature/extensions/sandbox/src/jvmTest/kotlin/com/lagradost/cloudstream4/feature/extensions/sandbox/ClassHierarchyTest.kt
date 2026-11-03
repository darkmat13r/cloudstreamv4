package com.lagradost.cloudstream4.feature.extensions.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals

/** The answers a stack map is written from, and the cases no real plugin in the fixture reaches. */
class ClassHierarchyTest {
    private val hostClasses = mapOf(
        "java/lang/Object" to null,
        "host/Parent" to "java/lang/Object",
        "host/ChildA" to "host/Parent",
        "host/ChildB" to "host/Parent",
    )

    private fun hierarchy(local: Map<String, String?>) = ClassHierarchy(local) { type ->
        if (type in hostClasses) ClassHierarchy.HostClass(hostClasses[type]) else null
    }

    @Test
    fun `a class joined with its own parent is that parent, either way round`() {
        val classes = hierarchy(mapOf("p/Base" to "java/lang/Object", "p/Child" to "p/Base"))
        assertEquals("p/Base", classes.commonSuperclass("p/Child", "p/Base"))
        assertEquals("p/Base", classes.commonSuperclass("p/Base", "p/Child"))
    }

    @Test
    fun `the walk carries on through the host's classes`() {
        val classes = hierarchy(mapOf("p/A" to "host/ChildA", "p/B" to "host/ChildB"))
        assertEquals("host/Parent", classes.commonSuperclass("p/A", "p/B"))
    }

    @Test
    fun `two classes extending the same missing class share it`() {
        val classes = hierarchy(mapOf("p/A" to "gone/Missing", "p/B" to "gone/Missing"))
        assertEquals("gone/Missing", classes.commonSuperclass("p/A", "p/B"))
    }

    @Test
    fun `chains that meet nowhere answer the one class every reference is`() {
        val classes = hierarchy(mapOf("p/A" to "gone/One", "p/B" to "gone/Two"))
        assertEquals("java/lang/Object", classes.commonSuperclass("p/A", "p/B"))
        assertEquals("java/lang/Object", classes.commonSuperclass("gone/One", "gone/Two"))
    }

    @Test
    fun `a dex whose classes extend each other does not hang the conversion`() {
        val classes = hierarchy(mapOf("p/A" to "p/B", "p/B" to "p/A", "p/C" to "java/lang/Object"))
        assertEquals("java/lang/Object", classes.commonSuperclass("p/A", "p/C"))
        assertEquals("p/B", classes.commonSuperclass("p/A", "p/B"))
    }

    @Test
    fun `a plugin's copy of a host class does not change the answer, because a host loads parent-first`() {
        val classes = hierarchy(mapOf("host/ChildA" to "java/lang/Object", "p/B" to "host/Parent"))
        assertEquals("host/Parent", classes.commonSuperclass("host/ChildA", "p/B"))
    }
}
