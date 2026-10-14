package com.lagradost.cloudstream4.media.model

/**
 * A width-to-height ratio, kept as the two integers rather than a float.
 *
 * `:app` uses `android.util.Rational`, which is `android.*` and so cannot be here. The integers are
 * kept because that is what the platform API wants back — Android's
 * `PictureInPictureParams.Builder.setAspectRatio` takes a `Rational` — and reconstructing an exact
 * ratio from a float is lossy in the one direction that matters.
 */
data class AspectRatio(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "AspectRatio must be positive, was ${width}x$height" }
    }

    val value: Float get() = width.toFloat() / height.toFloat()

    /**
     * This ratio clamped to what a picture-in-picture window accepts.
     *
     * Android rejects `PictureInPictureParams` outside roughly 1:2.39 .. 2.39:1 by throwing, so
     * every caller must clamp. `:app` does it with three magic numbers declared as local `val`s
     * inside the Android helper — `0.41841f`, `2.39f` and an accuracy of `100000` — where nothing
     * else can reuse or test them. They are named here instead.
     *
     * The bounds are Android's, and they are the only published ones; an engine on another platform
     * that accepts more is free to ignore this.
     */
    fun clampedForPictureInPicture(): AspectRatio {
        val target = value.coerceIn(MIN_PIP_RATIO, MAX_PIP_RATIO)
        if (target == value) return this
        return AspectRatio((target * RATIO_ACCURACY).toInt(), RATIO_ACCURACY)
    }

    companion object {
        /** Android's narrowest accepted picture-in-picture ratio, 1:2.39. */
        const val MIN_PIP_RATIO = 0.41841f

        /** Android's widest accepted picture-in-picture ratio, 2.39:1. */
        const val MAX_PIP_RATIO = 2.39f

        /** Denominator used to rebuild a clamped ratio as integers. */
        const val RATIO_ACCURACY = 100_000
    }
}

/**
 * Whether playback should be, or is, in a picture-in-picture window.
 *
 * **There is no type for this today**: it is `CommonActivity.isPipDesired` and
 * `CommonActivity.isInPIPMode`, two mutable globals, plus a `hasPipModeSupport` flag on the player
 * view and a rule recomputed inline in the Android helper.
 *
 * What is modelled here is the *decision*; what is not, and cannot be, is the platform's permission
 * to act on it. Whether picture-in-picture is available at all depends on three Android facts — the
 * SDK level, `PackageManager.FEATURE_PICTURE_IN_PICTURE`, and an `AppOpsManager` check the user can
 * revoke in Settings — none of which has a portable form. [isSupported] is the answer to that
 * question, supplied by the platform, not computed here.
 */
data class PipState(
    /** Whether the platform can do picture-in-picture at all, and is permitted to. */
    val isSupported: Boolean,
    /** Whether the user has turned it on. */
    val isEnabled: Boolean,
    /** Whether the window is showing right now. */
    val isActive: Boolean,
    val aspectRatio: AspectRatio? = null,
) {
    /**
     * Whether the window should be entered if the app is backgrounded now.
     *
     * This is the rule `:app` computes inline: desired while buffering or playing, not otherwise.
     * It is a function of [isEnabled], [isSupported] and the playback state, so it takes the state
     * rather than storing a fourth flag that can disagree with the other three.
     */
    fun isDesired(state: PlaybackState): Boolean {
        if (!isSupported || !isEnabled) return false
        return state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING
    }
}
