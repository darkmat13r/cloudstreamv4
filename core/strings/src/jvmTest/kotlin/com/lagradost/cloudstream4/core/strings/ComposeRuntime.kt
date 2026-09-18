package com.lagradost.cloudstream4.core.strings

import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.Resource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ScriptQualifier
import org.jetbrains.compose.resources.ThemeQualifier

/**
 * The Compose Multiplatform 1.12.0 runtime pieces the parity tests call, headless on the JVM (no skiko).
 *
 * Reflection reaches internal API: the `ResourceEnvironment` constructor (the public way needs a composition or
 * skiko), `Resource.items` / `ResourceItem.path`, `getStringItem` (the runtime's own `.cvr` record decoder) and
 * `getResourceItemByEnvironment` (the runtime's locale selection, for plurals). A Compose bump can break these;
 * `GeneratedResourcesParityTest.composeVersionIsTheVerifiedOne` fails first, and the `.cvr` check uses public API only.
 */
@OptIn(InternalResourceApi::class)
internal object ComposeRuntime {
    private val environmentConstructor =
        ResourceEnvironment::class.java.declaredConstructors.single().apply { isAccessible = true }
    private val itemsField = Resource::class.java.getDeclaredField("items").apply { isAccessible = true }
    private val itemClass = Class.forName("org.jetbrains.compose.resources.ResourceItem")
    private val pathField = itemClass.getDeclaredField("path").apply { isAccessible = true }
    private val getStringItem = Class.forName("org.jetbrains.compose.resources.StringResourcesUtilsKt")
        .declaredMethods.single { it.name == "getStringItem" }.apply { isAccessible = true }
    private val itemByEnvironment = Class.forName("org.jetbrains.compose.resources.ResourceEnvironmentKt")
        .declaredMethods.single { it.name == "getResourceItemByEnvironment" }.apply { isAccessible = true }
    private val defaultReader = Class.forName("org.jetbrains.compose.resources.ResourceReaderKt")
        .getDeclaredMethod("getDefaultResourceReader").apply { isAccessible = true }.invoke(null)

    fun environment(language: String, script: String, region: String): ResourceEnvironment =
        environmentConstructor.newInstance(
            LanguageQualifier(language),
            ScriptQualifier(script),
            RegionQualifier(region),
            ThemeQualifier.LIGHT,
            DensityQualifier.MDPI,
        ) as ResourceEnvironment

    /** Every packaged item of [resource], by the `values…` directory in its path. */
    fun itemsByDirectory(resource: Resource): Map<String, Any> =
        (itemsField.get(resource) as Set<*>).associate { item ->
            (pathField.get(item) as String).split('/').single { it.startsWith("values") } to requireNotNull(item)
        }

    fun itemFor(resource: Resource, environment: ResourceEnvironment): Any =
        try {
            itemByEnvironment.invoke(null, resource, environment)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }

    /** Decodes one item through the runtime: `"-"` to the text, or plural category (lower case) to text. */
    suspend fun decode(item: Any): Map<String, String> {
        val value = requireNotNull(
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                getStringItem.invoke(null, item, defaultReader, continuation)
            },
        )
        @Suppress("UNCHECKED_CAST")
        return when (value.javaClass.simpleName) {
            "Value" -> mapOf("-" to value.javaClass.getMethod("getText").invoke(value) as String)
            "Plurals" -> (value.javaClass.getMethod("getItems").invoke(value) as Map<Enum<*>, String>)
                .mapKeys { it.key.name.lowercase() }
            else -> error("unexpected ${value.javaClass.name}")
        }
    }
}
