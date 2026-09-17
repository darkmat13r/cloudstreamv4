import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.util.zip.ZipFile

/**
 * `copyJar` + `makeJar` for `:app`: builds `app/build/classes.jar`, the jar third-party extensions
 * compile against.
 *
 * ## classes.jar is a published API contract
 *
 * Every class in it keeps its fully-qualified name and appears exactly once
 * (`DuplicatesStrategy.FAIL`). Moving a `com.lagradost.cloudstream3.*` class out of `:app` must not
 * move it out of this jar, so the modules merged into it are derived, never hand-listed.
 *
 * **Candidates** are every project on `:app`'s resolved `prereleaseDebug` compile and runtime
 * classpaths — direct, transitive or variant-specific. A candidate is **merged**, whole, when it has
 * any `src/<sourceSet>/{kotlin,java}/com/lagradost/cloudstream3` directory. What is merged depends on
 * the module's targets:
 *
 * - **jvm-capable** (has a `jvm` variant: `cloudstream.kmp.library`, `cloudstream.kmp.extension-api`,
 *   `:library`) — its `jvm` jar. Only that variant is published, so an `androidMain` file in the
 *   package must be an `actual` with a `jvmMain` counterpart at the same path, as `:library` does.
 * - **android-only** (no `jvm` variant: `cloudstream.kmp.android-only`) — its compiled Android
 *   classes, exactly as `:app`'s runtime classpath consumes them: the `android-classes-jar` artifact
 *   of the module's variant on `prereleaseDebugRuntimeClasspath`, so it must not be `compileOnly`
 *   there. That jar holds the module's Kotlin/Java classes only — AGP keeps `R` in a separate
 *   artifact, and the KMP Android library plugin generates no `BuildConfig` — and `makeJar` fails if
 *   either turns up in it. The counterpart check does not apply. **`R` still reaches classes.jar
 *   another way:** a module with `androidResources` enabled gets its `R` classes compiled into
 *   `:app`'s own classes jar, as `:shared`'s `R` is today. Keep resources out of android-only
 *   modules that hold extension API, or accept that their `R` becomes part of the jar.
 *
 * A module is android-only when its `jvm` variant request does not resolve. That is the same
 * request the merge makes, so the classification cannot disagree with what would be merged.
 *
 * `makeJar` fails when a candidate that is left out declares the package anyway, when a merged
 * module contributes no class in it, when an `androidMain` file in a jvm-capable merged module has
 * no `jvmMain` counterpart, when an android-only module brings `R` or `BuildConfig`, or when a
 * sentinel class is missing; `DuplicatesStrategy.FAIL` fails it when two sources bring the same
 * entry. **When you move an extension-facing class out of `:app`, add it to [sentinels] in the same
 * change.**
 */

val apiPackageDir = "com/lagradost/cloudstream3"
val jarVariant = "prereleaseDebug"
val appClassesJarDir =
    "build/intermediates/compile_app_classes_jar/$jarVariant/bundlePrereleaseDebugClassesToCompileJar"

/** Entry -> the module that owns it and why it matters. Missing any of these fails `makeJar`. */
val sentinels = mapOf(
    "$apiPackageDir/plugins/Plugin.class" to ":app - the base class every extension extends",
    "$apiPackageDir/MainAPI.class" to ":library - the provider API every extension implements",
    "com/lagradost/api/BuildConfig.class" to ":library - outside cloudstream3; module jars merge whole",
    "$apiPackageDir/utils/Event.class" to ":core:common - type of MainActivity.afterPluginsLoadedEvent",
    "$apiPackageDir/utils/EmptyEvent.class" to ":core:common",
    "$apiPackageDir/utils/IDisposable.class" to ":core:common",
    "$apiPackageDir/utils/IDisposableHelper.class" to ":core:common - no in-repo users; only this sees its loss",
    "$apiPackageDir/utils/Vector2.class" to ":core:common",
    "$apiPackageDir/network/DohProvidersKt.class" to ":core:network - OkHttpClient.Builder DoH extensions",
    "$apiPackageDir/utils/DataStoreKt.class" to ":core:data - storage key consts; :app's DataStore.kt must declare no top-level members",
    "$apiPackageDir/utils/DataStoreHelperKt.class" to ":core:data - storage key consts; :app's DataStoreHelper.kt must declare no top-level members",
)

/** Every project dependency `:app` is built with for [jarVariant], direct or transitive. */
val appProjectDependencies: List<String> by lazy {
    listOf("${jarVariant}CompileClasspath", "${jarVariant}RuntimeClasspath").flatMap { name ->
        configurations.getByName(name).incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
    }.filter { it != path && rootProject.findProject(it) != null }.distinct().sorted()
}

/** Project paths merged into classes.jar next to `:app`'s own classes. */
val extensionApiModules: List<String> by lazy {
    appProjectDependencies.filter { modulePath ->
        project(modulePath).file("src").listFiles().orEmpty().any { sourceSet ->
            listOf("kotlin", "java").any { sourceSet.resolve("$it/$apiPackageDir").isDirectory }
        }
    }
}

/** The module's `jvm` variant, as :library has always contributed library-jvm.jar. */
fun jvmVariantOf(modulePath: String) = configurations.detachedConfiguration(dependencies.project(modulePath)).apply {
    isTransitive = false
    attributes {
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}

/**
 * Merged modules with no `jvm` variant, whose Android classes are merged instead. A module whose
 * `jvm` request fails for any reason other than having no such variant is not android-only, and
 * fails configuration here rather than being merged the wrong way. Gradle exposes that reason only
 * as internal exception types, so it is recognised by its message: if an upgrade rewords it, this
 * fails loudly instead of misclassifying.
 */
val androidOnlyModules: List<String> by lazy {
    extensionApiModules.filter { modulePath ->
        val failure = jvmVariantOf(modulePath).incoming.resolutionResult.root.dependencies
            .filterIsInstance<UnresolvedDependencyResult>().firstOrNull()?.failure
            ?: return@filter false
        val causes = generateSequence(failure) { it.cause }.mapNotNull { it.message }
        if (causes.none { it.contains("No matching variant of project $modulePath") }) {
            throw GradleException("Cannot tell whether $modulePath has a jvm variant", failure)
        }
        true
    }
}

/**
 * AGP's artifact type for a library's compiled classes jar (`AndroidArtifacts.ArtifactType.CLASSES_JAR`
 * is internal API; the string is what its variants publish). No `R`: that is a separate artifact.
 */
val androidClassesJarType = "android-classes-jar"

fun apiJarName(modulePath: String) = modulePath.removePrefix(":").replace(':', '-') +
    if (modulePath in androidOnlyModules) "-android.jar" else "-jvm.jar"

tasks.register<Copy>("copyJar") {
    dependsOn("build")
    from(appClassesJarDir) { include("classes.jar") }
    extensionApiModules.forEach { modulePath ->
        val moduleJar = if (modulePath in androidOnlyModules) {
            configurations.getByName("${jarVariant}RuntimeClasspath").incoming.artifactView {
                componentFilter { it is ProjectComponentIdentifier && it.projectPath == modulePath }
                attributes { attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, androidClassesJarType) }
            }.files
        } else {
            jvmVariantOf(modulePath)
        }
        from(moduleJar) { rename(".*", apiJarName(modulePath)) }
    }
    into("build/app-classes")
}

// Merge the app classes and every extension API module's classes into classes.jar
tasks.register<Jar>("makeJar") {
    // Duplicates cause hard to catch errors, better to fail at compile time.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn("copyJar")
    from(zipTree("build/app-classes/classes.jar"))
    extensionApiModules.forEach { from(zipTree("build/app-classes/${apiJarName(it)}")) }
    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName = "classes"

    // Everything doLast reads is captured here: no Project access at execution time.
    val packageDir = apiPackageDir
    val requiredEntries = sentinels
    val mergedJar = archiveFile
    val mergedModuleJars = extensionApiModules.associateWith {
        layout.buildDirectory.file("app-classes/${apiJarName(it)}").get().asFile
    }
    val leftOutSources = files((appProjectDependencies - extensionApiModules).map { modulePath ->
        fileTree(project(modulePath).file("src")) { include("**/*.kt", "**/*.java") }
    })
    val androidOnlyModuleJars = mergedModuleJars.filterKeys { it in androidOnlyModules }
    val platformSourceDirs = (extensionApiModules - androidOnlyModules).associate { modulePath ->
        project(modulePath).file("src/androidMain") to project(modulePath).file("src/jvmMain")
    }
    inputs.files(leftOutSources).withPropertyName("leftOutModuleSources")
    inputs.files(platformSourceDirs.flatMap { (android, jvm) -> listOf(fileTree(android), fileTree(jvm)) })
        .withPropertyName("mergedModulePlatformSources")

    // doFirst: the jar's own copy action expands every module jar before doLast would run.
    doFirst {
        val missing = mergedModuleJars.filterValues { !it.isFile }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "classes.jar API check failed: no jar was copied for ${missing.joinToString()}. An android-only " +
                    "module is taken from :app's runtime classpath, so it must not be compileOnly there."
            )
        }
    }

    doLast {
        val packageDecl = Regex("""^\s*package\s+com\.lagradost\.cloudstream3\b""", RegexOption.MULTILINE)
        val platformSuffix = Regex("""\.(android|jvm)$""")
        val generatedAndroidClass = Regex("""(^|/)(R|R\$[^/]*|BuildConfig)\.class$""")
        val problems = mutableListOf<String>()

        leftOutSources.filter { packageDecl.containsMatchIn(it.readText()) }.forEach {
            problems += "$it declares com.lagradost.cloudstream3 but its module is not merged into " +
                "classes.jar. Put it under src/<sourceSet>/kotlin/$packageDir, or move it out of the package."
        }

        mergedModuleJars.filterValues { it.isFile }.forEach { (modulePath, jar) ->
            val contributes = ZipFile(jar).use { zip ->
                zip.entries().asSequence().any { it.name.startsWith("$packageDir/") && it.name.endsWith(".class") }
            }
            if (!contributes) problems += "$modulePath is merged into classes.jar but contributes no $packageDir class."
        }

        androidOnlyModuleJars.filterValues { it.isFile }.forEach { (modulePath, jar) ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().map { it.name }.filter { generatedAndroidClass.containsMatchIn(it) }.toList()
            }.forEach { entry ->
                problems += "$modulePath brings $entry into classes.jar. R and BuildConfig are generated per " +
                    "module and would become extension API there; rename the class or keep it out of the module."
            }
        }

        platformSourceDirs.filterKeys { it.isDirectory }.forEach { (androidDir, jvmDir) ->
            androidDir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { packageDecl.containsMatchIn(it.readText()) }
                .forEach { file ->
                    val base = file.nameWithoutExtension.replace(platformSuffix, "")
                    val hasCounterpart = jvmDir.resolve(file.parentFile.relativeTo(androidDir)).listFiles().orEmpty()
                        .any { it.nameWithoutExtension.replace(platformSuffix, "") == base }
                    if (!hasCounterpart) problems += "$file declares com.lagradost.cloudstream3 in androidMain " +
                        "with no jvmMain counterpart. Only the jvm variant is merged into classes.jar, so " +
                        "extensions cannot see it. Put it in commonMain/jvmCommonMain, or add the jvmMain actual."
                }
        }

        val entries = ZipFile(mergedJar.get().asFile).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        requiredEntries.filterKeys { it !in entries }.forEach { (entry, owner) ->
            problems += "Sentinel $entry ($owner) is missing from classes.jar. Extensions compile against " +
                "this jar, so every extension using it would break. Was its module dropped from the merge, " +
                "or its package renamed?"
        }

        if (problems.isNotEmpty()) {
            throw GradleException("classes.jar API check failed:\n- " + problems.joinToString("\n- "))
        }
    }
}
