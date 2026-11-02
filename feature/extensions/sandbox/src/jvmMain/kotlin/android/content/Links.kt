package android.content

import java.io.File

/** Delegates, as on Android, so that what a subclass reaches is still only the host's own `Context`. */
open class ContextWrapper(private val base: Context) : Context() {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
    override fun getApplicationContext(): Context = base.getApplicationContext()
    override fun getPackageName(): String = base.getPackageName()
    override fun getFilesDir(): File = base.getFilesDir()
    override fun getCacheDir(): File = base.getCacheDir()
    override fun getClassLoader(): ClassLoader = base.getClassLoader()
}

interface DialogInterface {
    interface OnClickListener
    interface OnDismissListener
    interface OnKeyListener
}

/**
 * Inert. There is no `startActivity` on the host's `Context`, so no `Intent` can ever be delivered:
 * this cannot open a browser, a file or another app.
 */
open class Intent
open class ComponentName
