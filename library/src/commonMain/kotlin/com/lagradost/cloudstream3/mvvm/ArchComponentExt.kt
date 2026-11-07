package com.lagradost.cloudstream3.mvvm

import androidx.annotation.AnyThread
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.WorkerThread
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

const val DEBUG_EXCEPTION = "THIS IS A DEBUG EXCEPTION!"
const val DEBUG_PRINT = "DEBUG PRINT"

class DebugException(message: String) : Exception("$DEBUG_EXCEPTION\n$message")

inline fun debugException(message: () -> String) {
    if (AppDebug.isDebug) {
        throw DebugException(message.invoke())
    }
}

inline fun debugPrint(tag: String = DEBUG_PRINT, message: () -> String) {
    if (AppDebug.isDebug) {
        Log.d(tag, message.invoke())
    }
}

inline fun debugWarning(message: () -> String) {
    if (AppDebug.isDebug) {
        logError(DebugException(message.invoke()))
    }
}

inline fun debugAssert(assert: () -> Boolean, message: () -> String) {
    if (AppDebug.isDebug && assert.invoke()) {
        throw DebugException(message.invoke())
    }
}

inline fun debugWarning(assert: () -> Boolean, message: () -> String) {
    if (AppDebug.isDebug && assert.invoke()) {
        logError(DebugException(message.invoke()))
    }
}

sealed class Resource<out T> {
    data class Success<out T>(val value: T) : Resource<T>()
    data class Failure(
        val isNetworkError: Boolean,
        val errorString: String,
    ) : Resource<Nothing>()

    data class Loading(val url: String? = null) : Resource<Nothing>()

    companion object {
        fun <T> fromResult(result: Result<T>) : Resource<T> {
            val value = result.getOrNull()
            return if (value != null) {
                Success(value)
            } else {
                throwAbleToResource(result.exceptionOrNull() ?: Exception("this should not be possible"))
            }
        }
    }
}

fun logError(throwable: Throwable) {
    Log.d("ApiError", "-------------------------------------------------------------------")
    Log.d("ApiError", "safeApiCall: " + throwable.message?.withoutUrls())
    // BUG-44: the trace printStackTrace() wrote, class and frames kept, with the urls and user paths in
    // every message (this one's, its causes', its suppressed ones') redacted. Same stream as before.
    printToStandardError(throwable.stackTraceToString().withoutUrls())
    Log.d("ApiError", "-------------------------------------------------------------------")
}

// BUG-44: one of three identical copies, which must stay in sync: `:app` `utils/LogRedaction.kt`,
// `:library` `mvvm/ArchComponentExt.kt` and `:feature:extensions:sandbox` `PluginHost.kt`.
// `:library` is the published API and cannot depend on an internal module, so they cannot share one.

/** `file:/…`, with any number of slashes, up to the next whitespace. */
private val FILE_URI = Regex("(?i)\\bfile:/\\S+")

/** `scheme://[userinfo@]host[:port]`, then whatever follows up to the next whitespace. */
private val URL_IN_TEXT = Regex("(?i)\\b([a-z][a-z0-9+.-]*)://(?:[^\\s/?#@]*@)?([^\\s/?#@]*)(\\S*)")

/**
 * An absolute path under a storage, home or volume root, up to a newline, a quote, `": "` or the
 * end. A path may hold spaces, so it is not cut at whitespace: what follows a path on its line is
 * lost with it, which is the safe way round.
 */
private val USER_PATH = Regex(
    "(?<![\\w.:/-])/(?:storage|sdcard|data/user|mnt|Users|home|Volumes)(?:/[^\\r\\n\"'<>]*?)?(?=: |[\\r\\n\"'<>]|\\z)"
)

/**
 * BUG-47: a Windows home path, `C:\Users\…` (any drive letter, either case, either slash), with the
 * same ends as [USER_PATH].
 */
private val WINDOWS_USER_PATH = Regex(
    "(?<![\\w.:/\\\\-])(?i:[a-z]):[\\\\/](?i:users)(?:[\\\\/][^\\r\\n\"'<>]*?)?(?=: |[\\r\\n\"'<>]|\\z)"
)

/**
 * BUG-52: control and format characters (Unicode Cc and Cf: ZWSP, ZWJ, ZWNJ, BOM, bidi marks and the
 * rest) out, except the line breaks and tabs that shape a stack trace. One inside `://`
 * (`https:<ZWSP>//`) would hide a url from [URL_IN_TEXT], so they go before any pattern runs.
 */
private fun String.withoutHiddenCharacters(): String = filterNot { c ->
    c.category == CharCategory.FORMAT ||
        (c.category == CharCategory.CONTROL && c != '\n' && c != '\r' && c != '\t')
}

/**
 * Text with a `file:` uri, a path under `/storage`, `/sdcard`, `/data/user`, `/mnt`, `/Users`, `/home`
 * or `/Volumes`, or a Windows `C:\Users\…` path replaced by `<path>`, and every other url cut to
 * `scheme://host[:port]/<…>`. The host is kept because it helps triage and is not a secret; the
 * userinfo, path, query and fragment are not.
 */
private fun String.withoutUrls(): String = this
    .withoutHiddenCharacters()
    .replace(FILE_URI, "<path>")
    .replace(URL_IN_TEXT) { match ->
        val (scheme, host, rest) = match.destructured
        "$scheme://$host" + if (rest.isEmpty()) "" else "/<…>"
    }
    .replace(USER_PATH, "<path>")
    .replace(WINDOWS_USER_PATH, "<path>")

@Deprecated(
    "Outdated function, use `safe` instead",
    replaceWith = ReplaceWith("safe"),
    level = DeprecationLevel.ERROR
)
fun <T> normalSafeApiCall(apiCall: () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        return null
    }
}

/** Catches any exception (or error) and only logs it.
 * Will return null on exceptions. */
fun <T> safe(apiCall: () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        return null
    }
}

/** Catches any exception (or error) and only logs it.
 * Will return null on exceptions. */
suspend fun <T> safeAsync(apiCall: suspend () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        return null
    }
}

@Deprecated(
    "Outdated function, use `safeAsync` instead",
    replaceWith = ReplaceWith("safeAsync"),
    level = DeprecationLevel.ERROR
)
suspend fun <T> suspendSafeApiCall(apiCall: suspend () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        return null
    }
}

fun Throwable.getAllMessages(): String {
    return (this.message ?: "") + (this.cause?.getAllMessages()?.let { "\n$it" } ?: "")
}

fun Throwable.getStackTracePretty(showMessage: Boolean = true): String {
    // BUG-44: the message reaches the screen through safeFail's Resource.Failure, so it is redacted.
    val prefix = if (showMessage) this.message?.let { "\n${it.withoutUrls()}" } ?: "" else ""
    return prefix + this.stackTraceToString()
        .lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("at ")) return@mapNotNull null
            // pull "File.kt:42" out of "at com.pkg.Class.method(File.kt:42)"
            Regex("""\(([^)]+)\)$""").find(trimmed)?.groupValues?.get(1)
                ?.replace(":", " ")
        }
        .joinToString("\n")
}

fun <T> safeFail(throwable: Throwable): Resource<T> {
    val stackTraceMsg = throwable.getStackTracePretty()
    return Resource.Failure(false, stackTraceMsg)
}

fun CoroutineScope.launchSafe(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val obj: suspend CoroutineScope.() -> Unit = {
        try {
            block()
        } catch (throwable: Throwable) {
            logError(throwable)
        }
    }

    return this.launch(context, start, obj)
}

expect fun <T> platformThrowAbleToResource(throwable: Throwable): Resource<T>

/** Writes [text] where `Throwable.printStackTrace()` writes on this platform: standard error. */
internal expect fun printToStandardError(text: String)

fun <T> throwAbleToResource(
    throwable: Throwable
): Resource<T> {
    return when (throwable) {
        is NullPointerException -> {
            val traceLine = throwable.stackTraceToString()
                .lines()
                .firstOrNull { it.contains("provider.kt", ignoreCase = true) }
                ?.let { Regex("""\(([^)]+)\)$""").find(it)?.groupValues?.get(1) }
            if (traceLine != null) {
                return Resource.Failure(
                    false,
                    "NullPointerException at $traceLine\nSite might have updated or added Cloudflare/DDOS protection"
                )
            }
            safeFail(throwable)
        }

        is ErrorLoadingException -> {
            Resource.Failure(
                true,
                throwable.message ?: "Error loading, try again later."
            )
        }

        is NotImplementedError -> {
            Resource.Failure(false, "This operation is not implemented.")
        }

        is CancellationException -> {
            throwable.cause?.let {
                throwAbleToResource(it)
            } ?: safeFail(throwable)
        }

        else -> platformThrowAbleToResource(throwable)
    }
}

@AnyThread
suspend fun <T> safeApiCall(
    @WorkerThread apiCall: suspend () -> T,
): Resource<T> {
    return apiCall.ioWork { work ->
        try {
            Resource.Success(work.invoke())
        } catch (throwable: Throwable) {
            logError(throwable)
            throwAbleToResource(throwable)
        }
    }
}
