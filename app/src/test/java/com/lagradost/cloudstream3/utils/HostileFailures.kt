package com.lagradost.cloudstream3.utils

/**
 * `BUG-63`: what a provider's failure says, in the shape search, detail and downloads show it. The same hostile table
 * as Home's (`BUG-61`) and the redaction copies' (`BUG-64`).
 */
internal object HostileFailures {
    /** A label for the report, and the text a provider throws. None may reach a screen with its token or path. */
    val cases = listOf(
        "plain url with a token" to "Refused https://cdn.example/v/1.m3u8?token=SECRET0",
        "ZWSP in ://" to "Refused https:\u200B//cdn.example/v/1.m3u8?token=SECRET1",
        "NUL in ://" to "Refused https:/\u0000/cdn.example/v/1.m3u8?token=SECRET2",
        "BEL after the path" to "Refused https://cdn.example/v/1.m3u8\u0007?token=SECRET3",
        "ZWSP in /Users/" to "Cannot open /Us\u200Bers/someone/Movies/film.mkv: denied",
        "ZWSP in file:" to "Cannot open fi\u200Ble:///storage/emulated/0/someone/film.mkv",
        "CGJ in ://" to "Refused https:\u034F//cdn.example/v/1.m3u8?token=SECRET5",
        "VS16 in the scheme and between the slashes" to "Refused ht\uFE0Ftps:/\uFE0F/cdn.example/v/1.m3u8?token=SECRET6",
        "CGJ in /Users/" to "Cannot open /Us\u034Fers/someone/Movies/film.mkv: denied",
        "U+3164 after /Users" to "Cannot open /Users\u3164/someone/Movies/film.mkv: denied",
        "braille blank before //" to "Refused https:\u2800//cdn.example/v/1.m3u8?token=SECRET7",
        "braille blank between the slashes" to "Refused https:/\u2800/cdn.example/v/1.m3u8?token=SECRET8",
        "braille blank twice" to "Refused https:\u2800/\u2800/cdn.example/v/1.m3u8?token=SECRET9",
        "braille blank with spaces" to "Refused https: \u2800 //cdn.example/v/1.m3u8?token=SECRET10",
        "space and braille between the slashes" to "Refused https:/ \u2800/cdn.example/v/1.m3u8?token=SECRET11",
        "U+1D159 before //" to "Refused https:\uD834\uDD59//cdn.example/v/1.m3u8?token=SECRET12",
        "tag character in ://" to "Refused https:\uDB40\uDC41//cdn.example/v/1.m3u8?token=SECRET13",
    )

    /** Lines that only look a little like a link, which come through as they were. */
    val plain = listOf("Ratio 16:9 // wide", "Note: a/b", "Note: a/b/c", "x:a/b", "1:2/3/4", "HTTP 503")

    /** Why [shown] leaks, or null. */
    fun leakIn(shown: String): String? = when {
        "SECRET" in shown -> "a token"
        "someone" in shown -> "a user path"
        "/v/1.m3u8" in shown -> "a url's path"
        shown.any { it.isISOControl() || it.category == CharCategory.FORMAT } -> "an invisible character"
        else -> null
    }
}
