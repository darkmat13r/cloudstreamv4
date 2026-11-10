package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.safeApiCall
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * BUG-92 (audit A-2): a failure a sandboxed plugin reported goes through `safeApiCall` like any other, and its log says
 * only what it is. The plugin's words, redacted or not, go to no log; the screen still gets its reason.
 */
class PluginFailedLogTest {

    /** What [block] prints to stdout and stderr together. */
    private fun printed(block: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val realOut = System.out
        val realErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(out, true))
        try {
            block()
        } finally {
            System.setOut(realOut)
            System.setErr(realErr)
        }
        return out.toString()
    }

    @Test
    fun `a plugin's failure is logged by its class, and none of its words`() {
        var result: Resource<Unit>? = null
        val log = printed {
            result = runBlocking {
                withTimeout(60_000) {
                    safeApiCall<Unit> { throw PluginFailed("IllegalStateException: searched MARKERwords for viewer") }
                }
            }
        }
        assertTrue("PluginFailed: IllegalStateException" in log, log)
        assertFalse("MARKERwords" in log, log)
        assertFalse("viewer" in log, log)
        // The screen keeps its reason: the failure's text is built from the redacted message, not from the log line.
        val failure = assertIs<Resource.Failure>(result)
        assertTrue("MARKERwords" in failure.errorString, failure.errorString)
    }

    @Test
    fun `any other failure is logged as before, its message redacted`() {
        val log = printed {
            runBlocking { safeApiCall<Unit> { throw IOException("upstream said no at https://cdn.example/x?t=1") } }
        }
        assertTrue("safeApiCall: upstream said no at https://cdn.example/<…>" in log, log)
    }

    @Test
    fun `its string and the first line of its trace are fixed in shape`() {
        val failure = PluginFailed("IllegalStateException: searched MARKERwords")
        assertEquals("PluginFailed: IllegalStateException", failure.toString())
        assertEquals("PluginFailed: IllegalStateException", failure.stackTraceToString().lineSequence().first())
    }
}
