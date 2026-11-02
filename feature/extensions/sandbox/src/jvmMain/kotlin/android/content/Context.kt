package android.content

import android.content.res.Resources
import java.io.File

/**
 * What extensions actually call on a `Context`, by the `dexdump` count over 89 sampled plugins.
 *
 * Deliberately not the whole class. An extension that calls something else gets a
 * `NoSuchMethodError`, reported as that call failing, which is more honest than a stub returning
 * something plausible. `startActivity` and the like have no meaning in a plugin host.
 */
abstract class Context {
    abstract fun getSharedPreferences(name: String?, mode: Int): SharedPreferences
    abstract fun getApplicationContext(): Context
    abstract fun getPackageName(): String
    abstract fun getFilesDir(): File
    abstract fun getCacheDir(): File
    abstract fun getClassLoader(): ClassLoader

    /** Null, as in `Plugin.resources`: there is no resource table here. */
    open fun getResources(): Resources? = null

    companion object {
        const val MODE_PRIVATE = 0
    }
}
