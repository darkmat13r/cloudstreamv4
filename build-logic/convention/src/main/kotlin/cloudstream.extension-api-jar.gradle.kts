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
 * classpaths — direct, transitive or variant-specific. A candidate is **merged**, as its whole `jvm`
 * jar, when it has any `src/<sourceSet>/{kotlin,java}/com/lagradost/cloudstream3` directory.
 *
 * Only the `jvm` variant of a merged module is published. An `androidMain` file in the package must
 * therefore be an `actual` with a `jvmMain` counterpart at the same path, as `:library` does.
 *
 * `makeJar` fails when a candidate that is left out declares the package anyway, when a merged
 * module contributes no class in it, when an `androidMain` file in it has no `jvmMain` counterpart,
 * or when a sentinel class is missing. **When you move an extension-facing class out of `:app`,
 * add it to [sentinels] in the same change.**
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

fun apiJarName(modulePath: String) = modulePath.removePrefix(":").replace(':', '-') + "-jvm.jar"

tasks.register<Copy>("copyJar") {
    dependsOn("build")
    from(appClassesJarDir) { include("classes.jar") }
    extensionApiModules.forEach { modulePath ->
        // The module's `jvm` variant, as :library has always contributed library-jvm.jar.
        val jvmJar = configurations.detachedConfiguration(dependencies.project(modulePath)).apply {
            isTransitive = false
            attributes {
                attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            }
        }
        from(jvmJar) { rename(".*", apiJarName(modulePath)) }
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
    val platformSourceDirs = extensionApiModules.associate { modulePath ->
        project(modulePath).file("src/androidMain") to project(modulePath).file("src/jvmMain")
    }
    inputs.files(leftOutSources).withPropertyName("leftOutModuleSources")
    inputs.files(platformSourceDirs.flatMap { (android, jvm) -> listOf(fileTree(android), fileTree(jvm)) })
        .withPropertyName("mergedModulePlatformSources")

    doLast {
        val packageDecl = Regex("""^\s*package\s+com\.lagradost\.cloudstream3\b""", RegexOption.MULTILINE)
        val platformSuffix = Regex("""\.(android|jvm)$""")
        val problems = mutableListOf<String>()

        leftOutSources.filter { packageDecl.containsMatchIn(it.readText()) }.forEach {
            problems += "$it declares com.lagradost.cloudstream3 but its module is not merged into " +
                "classes.jar. Put it under src/<sourceSet>/kotlin/$packageDir, or move it out of the package."
        }

        mergedModuleJars.forEach { (modulePath, jar) ->
            val contributes = ZipFile(jar).use { zip ->
                zip.entries().asSequence().any { it.name.startsWith("$packageDir/") && it.name.endsWith(".class") }
            }
            if (!contributes) problems += "$modulePath is merged into classes.jar but contributes no $packageDir class."
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
