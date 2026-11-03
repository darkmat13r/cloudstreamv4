package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

/**
 * `:app`'s key-value store, on the host's preferences. So it lives inside this plugin's own sandbox
 * directory; nothing here can see another plugin's keys or the app's. Restates only what an extension
 * links against, with the semantics of `:app`'s `DataStore`: values are JSON strings.
 */
object DataStore {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    fun Context.getSharedPrefs(): SharedPreferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun Context.getDefaultSharedPrefs(): SharedPreferences = getSharedPreferences(DEFAULT_PREFERENCES, Context.MODE_PRIVATE)
    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun Context.containsKey(path: String): Boolean = getSharedPrefs().contains(path)
    fun Context.containsKey(folder: String, path: String): Boolean = containsKey(getFolderName(folder, path))

    fun Context.getKeys(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        return getSharedPrefs().getAll().keys.filter { it.startsWith(prefix) }
    }

    fun Context.removeKey(path: String) {
        getSharedPrefs().edit().remove(path).apply()
    }

    fun Context.removeKey(folder: String, path: String) = removeKey(getFolderName(folder, path))

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys(folder)
        keys.forEach { removeKey(it) }
        return keys.size
    }

    fun <T> Context.setKey(path: String, value: T) {
        getSharedPrefs().edit().putString(path, value?.let(mapper::writeValueAsString)).apply()
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) = setKey(getFolderName(folder, path), value)

    /** Null for a missing key and for one that does not parse as [valueType], as on Android. */
    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        val json = getSharedPrefs().getString(path, null) ?: return null
        return runCatching { mapper.readValue(json, valueType) }.getOrNull()
    }

    fun <T : Any> String.toKotlinObject(valueType: Class<T>): T = mapper.readValue(this, valueType)

    private const val PREFERENCES = "rebuild_preference"
    private const val DEFAULT_PREFERENCES = "default_preferences"
}
