package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream4.feature.extensions.sandbox.HostContext

/**
 * The `:app` classes an extension reaches for its `Context` and its settings: the host's context and
 * store, and nothing of the app's. `:app` owns the real ones; this module is not on its classpath
 * and `:app:makeJar` never merges it.
 *
 * Deliberately absent: `plugins.RepositoryManager` and `plugins.PluginManager`. A plugin has no business
 * changing what else is installed, so those keep failing to link.
 */
class CloudStreamApp {
    companion object {
        val context: Context get() = HostContext.current
        fun setKey(path: String, value: Any?) = context.setKey(path, value)
        fun setKey(folder: String, path: String, value: Any?) = context.setKey(folder, path, value)
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? = context.getKey(path, valueType)
        fun getKeys(folder: String): List<String> = context.getKeys(folder)
        fun removeKey(path: String) = context.removeKey(path)
        fun removeKey(folder: String, path: String) = context.removeKey(folder, path)
        fun removeKeys(folder: String): Int = context.removeKeys(folder)
    }
}

/** The older name of the same thing, which older extensions still call. */
class AcraApplication {
    companion object {
        val context: Context get() = HostContext.current
        fun setKey(path: String, value: Any?) = context.setKey(path, value)
        fun setKey(folder: String, path: String, value: Any?) = context.setKey(folder, path, value)
        fun removeKeys(folder: String): Int = context.removeKeys(folder)
    }
}

/** There is never an activity here, which is what keeps a plugin's dialogs and ads from opening. */
object CommonActivity {
    val activity: Activity? get() = null
    val onDialogDismissedEvent: Event<Any?> = Event()

    /** Reported, not shown. */
    fun showToast(message: String?, duration: Int? = null) {
        System.err.println("TOAST: $message")
    }

    fun showToast(activity: Activity?, message: String?, duration: Int? = null) = showToast(message, duration)
}

class MainActivity {
    companion object {
        val afterPluginsLoadedEvent: Event<Boolean> = Event()
        val mainPluginsLoadedEvent: Event<Boolean> = Event()
        val afterRepositoryLoadedEvent: Event<Boolean> = Event()
        val bookmarksUpdatedEvent: Event<Boolean> = Event()
        val reloadHomeEvent: Event<Boolean> = Event()
        val reloadLibraryEvent: Event<Boolean> = Event()
        val reloadAccountEvent: Event<Boolean> = Event()
    }
}
