package com.lagradost.cloudstream4.feature.extensions

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.io.File
import java.util.zip.ZipFile

/**
 * Finding the one `@CloudstreamPlugin` class inside a cross-platform extension jar.
 *
 * ## Why the jar and not the `.cs3`, and why there is no `manifest.json` to read
 *
 * A `.cs3` is dex-only — `manifest.json` + `classes.dex`, measured two ways in `KMP-13` §1 — so no
 * JVM `ClassLoader` can load one, and the `pluginClassName` that `PluginManager.kt:632` reads out of
 * that manifest is in the artefact this runtime cannot use. The cross-platform `.jar` published
 * beside it has no `manifest.json` at all: `unzip -l YoutubeProvider.jar` is 7 `.class` entries, one
 * `META-INF/<name>.kotlin_module` and two directory entries, and **no `META-INF/MANIFEST.MF`**.
 *
 * So the entry point is found from the bytecode instead, which the annotation makes possible:
 * `@CloudstreamPlugin` declares no `@Retention` (`library/.../plugins/CloudstreamPlugin.kt`), so
 * Kotlin's `RUNTIME` default applies. Verified rather than inferred —
 * `javap -v recloudstream/YoutubePlugin.class` lists it under `RuntimeVisibleAnnotations`, on a
 * class whose `super_class` is `com/lagradost/cloudstream3/plugins/BasePlugin`, major version 52.
 *
 * Do not write the literal two-character sequence slash-star inside this KDoc — **Kotlin block
 * comments nest**, so a `META-INF/` glob written out in full opens a nested comment that swallows
 * the closing delimiter and the file stops compiling with `Unclosed comment` pointing at EOF, a
 * long way from the cause. Two files in this PR hit it.
 *
 * Fetching the `.cs3` purely for its manifest would also work (`java.util.zip` reads it even though
 * the dex beside it is unloadable) and costs a second download for a fact the jar already carries.
 */
internal object ExtensionJar {

    /**
     * Binary names of the classes in [jar], in zip order.
     *
     * `META-INF/` is skipped: it holds the `.kotlin_module` and, in a multi-release jar, versioned
     * copies under `META-INF/versions/<n>/` whose binary names are not what the path suggests.
     */
    fun classNames(jar: File): List<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { entry -> !entry.isDirectory }
            .map { entry -> entry.name }
            .filter { name -> name.endsWith(CLASS_SUFFIX) && !name.startsWith(META_INF) }
            .map { name -> name.removeSuffix(CLASS_SUFFIX).replace('/', '.') }
            .toList()
    }

    /**
     * The jar's plugin class, loaded through [loader] **without running static initialisers**.
     *
     * `Class.forName(name, false, loader)` is the `false` that matters: a plugin's `companion
     * object` or a top-level property initialiser is third-party code, and discovery must not be
     * able to run it. It runs at `getDeclaredConstructor().newInstance()`, which is the caller's
     * deliberate step.
     *
     * ## A class that will not resolve is expected, not exceptional
     *
     * `EnsureJarCompatibilityTask` is the producer's guarantee that a cross-platform jar references
     * nothing Android, and it is thinner than it reads: it greps `jdeps --print-module-deps` output
     * for `"android."` with `isIgnoreExitValue = true`, and when `jdeps` prints nothing it only
     * warns (`EnsureJarCompatibilityTask.kt:35-52`). A jar can therefore reference a class this host
     * does not have — anything from `:app` rather than `:library`, for one, which `jdeps` would not
     * flag because `com.lagradost.cloudstream3.actions` is not an `android.` package. That surfaces
     * as `NoClassDefFoundError` (a [LinkageError], not an [Exception]) while *resolving a candidate*,
     * so each candidate is skipped individually and the scan continues; only an empty result is a
     * failure. `KMP-13` §6 records this as an expected outcome.
     *
     * @throws IllegalStateException when no class carries the annotation, when more than one does,
     *   or when the one that does is not a [BasePlugin].
     */
    fun findPluginClass(jar: File, loader: ClassLoader): Class<out BasePlugin> {
        val annotated = classNames(jar).mapNotNull { name ->
            val candidate = try {
                Class.forName(name, false, loader)
            } catch (missing: Exception) {
                null
            } catch (unlinkable: LinkageError) {
                null
            }
            candidate?.takeIf { it.isAnnotationPresent(CloudstreamPlugin::class.java) }
        }

        // Refusing to pick is deliberate, and it is a divergence from `:app`: there, the single
        // `manifest.json` names one class and the question cannot arise. Here it can, and guessing
        // which of two annotated classes a third-party jar meant is a guess about which arbitrary
        // code to run.
        check(annotated.size == 1) {
            when {
                annotated.isEmpty() -> "no @CloudstreamPlugin class in ${jar.name}"
                else -> "${annotated.size} @CloudstreamPlugin classes in ${jar.name}: " +
                    annotated.joinToString { it.name }
            }
        }

        val pluginClass = annotated.single()
        check(BasePlugin::class.java.isAssignableFrom(pluginClass)) {
            "${pluginClass.name} is annotated @CloudstreamPlugin but does not extend BasePlugin"
        }
        @Suppress("UNCHECKED_CAST")
        return pluginClass as Class<out BasePlugin>
    }

    private const val CLASS_SUFFIX = ".class"
    private const val META_INF = "META-INF/"
}
