package android.app

import android.content.ContextWrapper
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostContext

/**
 * Types only. No activity exists in a host, so `CommonActivity.activity` is null and a plugin's
 * "only with an activity" branches do not run. `Activity` is a `Context`, as on Android, because a
 * plugin passes one where a `Context` is declared and the verifier checks that.
 */
open class Activity : ContextWrapper(HostContext.current)
open class Application : ContextWrapper(HostContext.current) {
    interface ActivityLifecycleCallbacks
}
open class Dialog
open class AlertDialog : Dialog() {
    open class Builder
}
open class ActivityManager
open class UiModeManager
