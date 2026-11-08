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

/**
 * `scheme://[userinfo@]host[:port]`, then whatever follows up to the next whitespace. BUG-52: also
 * when whitespace or a Unicode space (`\p{Z}`, NBSP among them) splits `scheme:` from `//`. BUG-64: and when
 * up to two other characters do, such as a braille blank (U+2800), which a screen draws as nothing, or up to two
 * that are neither letters nor digits sit between the slashes.
 */
private val URL_IN_TEXT = Regex(
    "(?i)\\b([a-z][a-z0-9+.-]*):[\\s\\p{Z}]*(?:[^\\s\\p{Z}/][\\s\\p{Z}]*){0,2}/[\\s\\p{Z}]*(?:[^\\s\\p{Z}/\\p{L}\\p{N}][\\s\\p{Z}]*){0,2}/(?:[^\\s/?#@]*@)?([^\\s/?#@]*)(\\S*)"
)

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
 * rest) out before any pattern runs, since one inside `://` (`https:<ZWSP>//`) would hide a url from
 * [URL_IN_TEXT]. Line breaks stay, because they shape a stack trace. A tab becomes a space: a screen
 * that strips controls after redaction (Home, Detail, the HUD) would otherwise rejoin `https:<tab>//`.
 * BUG-64: then every code point Unicode calls default-ignorable, which Cc and Cf do not cover.
 */
private fun String.withoutHiddenCharacters(): String = replace('\t', ' ').filterNot { c ->
    c.category == CharCategory.FORMAT ||
        (c.category == CharCategory.CONTROL && c != '\n' && c != '\r')
}.withoutDefaultIgnorable()

/**
 * BUG-64: Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt), the code points a screen draws nothing
 * for. Cc and Cf miss some of them: U+034F, the variation selectors, the Hangul fillers. One of these inside `://` or
 * `/Users/` hides it from a pattern while the text reads the same. The same list is in every copy of this check.
 */
private fun isDefaultIgnorable(codePoint: Int): Boolean = when (codePoint) {
    0x00AD, 0x034F, 0x061C, 0x3164, 0xFEFF, 0xFFA0 -> true
    in 0x115F..0x1160, in 0x17B4..0x17B5, in 0x180B..0x180F, in 0x200B..0x200F, in 0x202A..0x202E,
    in 0x2060..0x206F, in 0xFE00..0xFE0F, in 0xFFF0..0xFFF8, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A,
    in 0xE0000..0xE0FFF -> true
    else -> false
}

/** Without [isDefaultIgnorable]'s code points, read a code point at a time, so a surrogate pair goes or stays whole. */
private fun String.withoutDefaultIgnorable(): String {
    val text = this
    return buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val codePoint =
                if (pair) 0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else text[i].code
            val end = if (pair) i + 2 else i + 1
            if (!isDefaultIgnorable(codePoint)) appendRange(text, i, end)
            i = end
        }
    }
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
                // BUG-52: this text reaches Home's and Detail's failure lines.
                throwable.message?.withoutUrls() ?: "Error loading, try again later."
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
