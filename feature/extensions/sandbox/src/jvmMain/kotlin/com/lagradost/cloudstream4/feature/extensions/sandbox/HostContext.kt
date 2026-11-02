package com.lagradost.cloudstream4.feature.extensions.sandbox

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * The `Context` a plugin host gives an Android-style extension: its own sandbox directory, and
 * preferences persisted inside it. Nothing here reaches further than the Seatbelt policy allows.
 */
internal class HostContext(private val directory: File) : Context() {
    private val preferences = ConcurrentHashMap<String, HostPreferences>()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        // A preferences name comes from the plugin, so it cannot be allowed to steer the path.
        val safe = name.orEmpty().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "default" }
        return preferences.computeIfAbsent(safe) { HostPreferences(File(directory, "prefs-$it.properties")) }
    }

    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.lagradost.cloudstream3"
    override fun getFilesDir(): File = directory
    override fun getCacheDir(): File = directory
    override fun getClassLoader(): ClassLoader = HostContext::class.java.classLoader

    companion object {
        /** Set by the host before any plugin loads. The fallback only ever serves a test. */
        @Volatile
        var current: Context = HostContext(File(System.getProperty("java.io.tmpdir")))
            internal set
    }
}

/** Each value is stored with a one-letter type prefix, because `Properties` holds only strings. */
private class HostPreferences(private val file: File) : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()

    init {
        runCatching {
            val stored = Properties()
            file.inputStream().use(stored::load)
            stored.stringPropertyNames().forEach { key ->
                decode(stored.getProperty(key))?.let { values[key] = it }
            }
        }
    }

    private fun decode(raw: String): Any? {
        val body = raw.drop(1)
        return when (raw.firstOrNull()) {
            's' -> body
            'i' -> body.toIntOrNull()
            'l' -> body.toLongOrNull()
            'f' -> body.toFloatOrNull()
            'b' -> body.toBooleanStrictOrNull()
            else -> null
        }
    }

    private fun encode(value: Any): String? = when (value) {
        is String -> "s$value"
        is Int -> "i$value"
        is Long -> "l$value"
        is Float -> "f$value"
        is Boolean -> "b$value"
        else -> null // A string set lives for this run only; no sampled plugin was seen relying on more.
    }

    private fun save() {
        val stored = Properties()
        values.forEach { (key, value) -> encode(value)?.let { stored.setProperty(key, it) } }
        runCatching { file.outputStream().use { stored.store(it, null) } }
    }

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        values[key] as? Set<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearFirst = false

        private fun set(key: String?, value: Any?) = apply { if (key != null) pending[key] = value }
        override fun putString(key: String?, value: String?) = set(key, value)
        override fun putStringSet(key: String?, values: Set<String>?) = set(key, values)
        override fun putInt(key: String?, value: Int) = set(key, value)
        override fun putLong(key: String?, value: Long) = set(key, value)
        override fun putFloat(key: String?, value: Float) = set(key, value)
        override fun putBoolean(key: String?, value: Boolean) = set(key, value)
        override fun remove(key: String?) = set(key, null)
        override fun clear() = apply { clearFirst = true }

        override fun commit(): Boolean {
            if (clearFirst) values.clear()
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            save()
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
