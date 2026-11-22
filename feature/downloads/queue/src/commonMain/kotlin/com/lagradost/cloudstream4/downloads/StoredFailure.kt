package com.lagradost.cloudstream4.downloads

import com.lagradost.cloudstream4.media.model.DownloadFailure

/**
 * A [DownloadFailure] as the downloads file holds it: the variant's name and its numbers, nothing else. No variant
 * carries text, so nothing from a link, a server or an exception can reach the file through here.
 *
 * The `when`s are exhaustive over the sealed interface, so a new variant fails this file's compile until it is
 * given a name.
 */
internal object StoredFailure {
    fun name(failure: DownloadFailure): String = when (failure) {
        DownloadFailure.NetworkLost -> "NetworkLost"
        is DownloadFailure.StorageFull -> "StorageFull"
        DownloadFailure.StorageUnavailable -> "StorageUnavailable"
        DownloadFailure.NoUsableLink -> "NoUsableLink"
        DownloadFailure.SourceFailed -> "SourceFailed"
        DownloadFailure.Unknown -> "Unknown"
        is DownloadFailure.ServerRefused -> "ServerRefused"
        DownloadFailure.SourceChanged -> "SourceChanged"
        is DownloadFailure.SizeMismatch -> "SizeMismatch"
        DownloadFailure.HashMismatch -> "HashMismatch"
        is DownloadFailure.InsufficientSpace -> "InsufficientSpace"
        DownloadFailure.AddressRefused -> "AddressRefused"
        DownloadFailure.DestinationExists -> "DestinationExists"
    }

    /** The variant's numbers, in declaration order. A null `bytesShort` is no number. */
    fun numbers(failure: DownloadFailure): List<Long> = when (failure) {
        is DownloadFailure.StorageFull -> listOfNotNull(failure.bytesShort)
        is DownloadFailure.ServerRefused -> listOf(failure.statusCode.toLong())
        is DownloadFailure.SizeMismatch -> listOf(failure.expected, failure.actual)
        is DownloadFailure.InsufficientSpace -> listOf(failure.bytesNeeded, failure.bytesAvailable)
        else -> emptyList()
    }

    /** The failure [name] and [numbers] describe, or [DownloadFailure.Unknown] when they do not describe one. */
    fun decode(name: String?, numbers: List<Long>): DownloadFailure = when (name) {
        "NetworkLost" -> DownloadFailure.NetworkLost
        "StorageFull" -> DownloadFailure.StorageFull(numbers.firstOrNull()?.takeIf { it >= 0L })
        "StorageUnavailable" -> DownloadFailure.StorageUnavailable
        "NoUsableLink" -> DownloadFailure.NoUsableLink
        "SourceFailed" -> DownloadFailure.SourceFailed
        "ServerRefused" -> numbers.firstOrNull()?.takeIf { it in 100L..999L }
            ?.let { DownloadFailure.ServerRefused(it.toInt()) } ?: DownloadFailure.Unknown
        "SourceChanged" -> DownloadFailure.SourceChanged
        "SizeMismatch" -> if (numbers.size == 2) DownloadFailure.SizeMismatch(numbers[0], numbers[1]) else DownloadFailure.Unknown
        "HashMismatch" -> DownloadFailure.HashMismatch
        "InsufficientSpace" ->
            if (numbers.size == 2) DownloadFailure.InsufficientSpace(numbers[0], numbers[1]) else DownloadFailure.Unknown
        "AddressRefused" -> DownloadFailure.AddressRefused
        "DestinationExists" -> DownloadFailure.DestinationExists
        else -> DownloadFailure.Unknown
    }
}
