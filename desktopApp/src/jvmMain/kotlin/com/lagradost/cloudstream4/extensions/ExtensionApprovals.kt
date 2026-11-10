package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanFinding
import com.lagradost.cloudstream4.feature.extensions.sandbox.ScanSeverity
import java.io.IOException
import com.lagradost.cloudstream4.extensions.repositories.PaneReviews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the screen can ask for. [SandboxedExtensions] is the real one. */
interface ExtensionControl {
    /**
     * Remembers Allow for this file and hash, then takes the file through the whole pipeline.
     *
     * @return where it ended up, or null when the file has gone.
     * @throws IOException when the choice could not be saved. Nothing was started.
     */
    suspend fun allow(fileName: String, sha256: String): SandboxedLoad?

    /**
     * Stops it if it is running, then remembers Keep off.
     *
     * @return where it ended up, or null when the file has gone.
     * @throws IOException when the choice could not be saved. It was stopped all the same.
     */
    suspend fun keepOff(fileName: String, sha256: String): SandboxedLoad?
}

/** Where one reviewed extension stands. No words: those are resolved in the composition (`I18N-08`). */
sealed interface ReviewStatus {
    /** Off, and waiting to be asked. */
    data object Held : ReviewStatus
    data object KeptOff : ReviewStatus
    data object Allowed : ReviewStatus
    data object Starting : ReviewStatus

    /** Allowed, and its host failed. [detail] is the host's own output, so it is cleaned and untrusted. */
    data class StartFailed(val detail: String) : ReviewStatus

    /** Refused by the scan. There is no action that changes it. */
    data object Blocked : ReviewStatus

    /** Refused for not matching a published hash. There is no action that changes it. */
    data object HashMismatch : ReviewStatus
}

/** One scan rule's findings. [details] are cleaned and bounded, and [hidden] is how many were left out. */
data class ReviewFinding(val rule: String, val details: List<String>, val hidden: Int)

data class ExtensionReview(
    /** The real name, for asking about this file. Not for showing: [displayName] is. */
    val fileName: String,
    val displayName: String,
    val sha256: String?,
    val status: ReviewStatus,
    val findings: List<ReviewFinding>,
    /** Waiting because the file changed since it was last decided on or run. */
    val newVersion: Boolean = false,
)

data class ExtensionReviewState(
    val reviews: List<ExtensionReview> = emptyList(),
    /** Saved decisions failed their check, so they were ignored. */
    val decisionsUnreadable: Boolean = false,
    /** The display name of the extension whose choice could not be saved. */
    val notSaved: String? = null,
    /** Goes up when the set of running providers may have changed, for a screen holding a list of them. */
    val providerChanges: Int = 0,
) {
    val waiting: Int get() = reviews.count { it.status == ReviewStatus.Held }
}

sealed interface ReviewAction {
    data class Allow(val fileName: String) : ReviewAction
    data class KeepOff(val fileName: String) : ReviewAction
}

/**
 * The state of the list of extensions the scan had something to say about.
 *
 * Actions run one at a time. A second press on a row already being changed waits for the first, and a row with
 * nothing to decide ignores them: approving a blocked extension is not a thing this class can be asked to do.
 */
class ExtensionApprovals(
    private val control: ExtensionControl,
    report: SandboxReport,
    private val scope: CoroutineScope,
) : PaneReviews {
    private val _state = MutableStateFlow(
        ExtensionReviewState(
            // Held first, once. Not re-sorted on a choice: a row must not jump out from under the pointer.
            reviews = report.loads.mapNotNull { it.toReview() }
                .sortedWith(compareBy({ it.status != ReviewStatus.Held }, { it.fileName })),
            decisionsUnreadable = report.decisionsUnreadable,
        ),
    )
    override val state: StateFlow<ExtensionReviewState> = _state

    private val actions = Mutex()

    /**
     * Takes in what became of one file after the launch (`KMP-110c`): a repository installed it, or replaced it. Its row
     * is replaced, added at the end, or removed when it has nothing to ask; nothing else moves.
     */
    override fun track(load: SandboxedLoad) {
        val review = load.toReview()
        _state.update { state ->
            val others = state.reviews.filter { it.fileName != load.fileName }
            val at = state.reviews.indexOfFirst { it.fileName == load.fileName }
            val reviews = when {
                review == null -> others
                at < 0 -> others + review
                else -> others.toMutableList().apply { add(at, review) }
            }
            state.copy(reviews = reviews, providerChanges = state.providerChanges + 1)
        }
    }

    /** Forgets [fileName]'s row, when a repository removed the file (`KMP-110c`). */
    override fun forget(fileName: String) {
        _state.update { state -> state.copy(reviews = state.reviews.filter { it.fileName != fileName }) }
    }

    fun onAction(action: ReviewAction) {
        when (action) {
            is ReviewAction.Allow -> decide(action.fileName, ReviewStatus.Starting, control::allow)
            is ReviewAction.KeepOff -> decide(action.fileName, null, control::keepOff)
        }
    }

    private fun decide(
        fileName: String,
        meanwhile: ReviewStatus?,
        apply: suspend (String, String) -> SandboxedLoad?,
    ) {
        val before = _state.value.reviews.firstOrNull { it.fileName == fileName } ?: return
        val sha256 = before.sha256 ?: return
        if (before.status == ReviewStatus.Blocked || before.status == ReviewStatus.HashMismatch) return
        if (meanwhile != null) replace(fileName) { it.copy(status = meanwhile) }
        scope.launch {
            actions.withLock {
                val wasRunning = before.status == ReviewStatus.Allowed
                try {
                    val load = apply(fileName, sha256)
                    _state.update { state ->
                        state.copy(
                            reviews = state.reviews.mapNotNull { review ->
                                if (review.fileName == fileName) load?.toReview() else review
                            },
                            notSaved = null,
                            decisionsUnreadable = false,
                            providerChanges = state.providerChanges +
                                if (wasRunning || load?.state == LoadState.Running) 1 else 0,
                        )
                    }
                } catch (_: IOException) {
                    // Whichever way it went, this file is off and the file on disk says nothing new about it.
                    replace(fileName) { it.copy(status = if (wasRunning) ReviewStatus.Held else before.status) }
                    _state.update { state ->
                        state.copy(
                            notSaved = before.displayName,
                            providerChanges = state.providerChanges + if (wasRunning) 1 else 0,
                        )
                    }
                }
            }
        }
    }

    private fun replace(fileName: String, change: (ExtensionReview) -> ExtensionReview) {
        _state.update { state ->
            state.copy(reviews = state.reviews.map { if (it.fileName == fileName) change(it) else it })
        }
    }

    private fun SandboxedLoad.toReview(): ExtensionReview? {
        val warned = scan?.warnings ?: 0
        val status = when (state) {
            LoadState.Held -> ReviewStatus.Held
            LoadState.KeptOff -> ReviewStatus.KeptOff
            LoadState.Blocked -> ReviewStatus.Blocked
            LoadState.HashMismatch -> ReviewStatus.HashMismatch
            LoadState.Running -> if (warned > 0 || chosen) ReviewStatus.Allowed else return null
            // Only something allowed can have reached a start with warnings. The rest has no choice to offer.
            LoadState.Failed ->
                if (warned > 0) ReviewStatus.StartFailed(outcome.untrustedText(MAX_FAILURE_CODE_POINTS)) else return null
            LoadState.Scanned -> return null
        }
        val severity = if (status == ReviewStatus.Blocked) ScanSeverity.Block else ScanSeverity.Warn
        return ExtensionReview(
            fileName = fileName,
            displayName = fileName.untrustedText(),
            sha256 = sha256,
            status = status,
            findings = scan?.findings.orEmpty().filter { it.severity == severity }.toReviewFindings(),
            newVersion = newVersion,
        )
    }

    private fun List<ScanFinding>.toReviewFindings(): List<ReviewFinding> =
        groupBy { it.rule }.map { (rule, findings) ->
            ReviewFinding(
                rule = rule,
                details = findings.take(MAX_DETAILS_PER_RULE).map { it.detail.untrustedText() },
                hidden = (findings.size - MAX_DETAILS_PER_RULE).coerceAtLeast(0),
            )
        }

    private companion object {
        const val MAX_DETAILS_PER_RULE = 3
        const val MAX_FAILURE_CODE_POINTS = 240
    }
}
