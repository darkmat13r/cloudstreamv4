import com.lagradost.cloudstream.buildlogic.CheckExtensionApiTask
import com.lagradost.cloudstream.buildlogic.DumpExtensionApiTask
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
 *
 * ## ABI dump: `dumpExtensionApi` / `checkExtensionApi`
 *
 * Sentinels prove a handful of classes are present; Kotlin `abiValidation` covers `:library` and the
 * `cloudstream.kmp.extension-api` modules, and dumps nothing for android-only modules. Neither sees
 * `:app`'s own classes. The dump at [extensionApiDumpPath] covers the whole merged jar, whatever
 * module a class came from, in one committed text file:
 *
 * - every public or protected class under [extensionApiDumpPackages], sorted by name, with its kind
 *   (class, interface, enum, annotation), modality (abstract, final, open), superclass and interfaces;
 * - every public or protected field and method with its JVM descriptor and its static, final,
 *   abstract and synthetic flags — so compiler-emitted API is guarded too: `$stable` (lost when a
 *   class moves to a module without the Compose compiler), `access$…$cp` accessors, `write$Self$app`,
 *   and a public data class's `<init>`, `copy`, `copy$default` and `componentN` when a constructor
 *   property is added;
 * - `R` and `BuildConfig` field **names** (extensions `getstatic` `R$string` fields by name), so
 *   adding a string or resource to `:app` also means re-running the dump (it shows as an addition);
 * - Kotlin `internal` declarations, which are public in bytecode (`…$app`-mangled members such as
 *   `write$Self$app`), and public anonymous classes such as inline functions' `$$inlined$` copies:
 *   renaming or renumbering one shows as a removal plus an addition.
 *
 * It does **not** guard:
 * - constant **values** — deliberately left out, because `BuildConfig.BUILD_DATE` / `VERSION_CODE`
 *   change every build and `R` ids shift whenever a resource is added. A changed compile-time
 *   constant is still inlined into extensions compiled against the old value;
 * - inline function bodies, which are copied into extensions (see plan §3.2 — `parseJson`,
 *   `Context.getKey`), and behaviour of any kind;
 * - generic signatures, annotations (`@StabilityInferred` included) and Kotlin metadata — only
 *   what the JVM links against;
 * - classes outside [extensionApiDumpPackages] — third-party `R` classes and the like, see there.
 *
 * `checkExtensionApi` fails with a unified diff (the full diff lands in
 * `build/reports/extension-api/`). **To change the API on purpose**, run
 * `./gradlew :app:dumpExtensionApi`, review the `api/` diff as an ABI change — a removed or changed
 * line can break published extensions; an added one normally does not, unless it is an abstract
 * member of a type extensions implement — and commit it with the change.
 *
 * Both tasks read `makeJar`'s output and are **not** wired into `check` or `build`: `copyJar`
 * depends on `build`, which depends on `check`, so doing so would be a task cycle. CI runs
 * `:app:checkExtensionApi` as its own step instead.
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
    "$apiPackageDir/subtitles/AbstractSubtitleEntities.class" to ":core:model - outer of \$SubtitleEntity / \$SubtitleSearch",
    "$apiPackageDir/subtitles/AbstractSubtitleEntities\$SubtitleEntity.class" to ":core:model - SubtitleAPI search results",
    "$apiPackageDir/subtitles/AbstractSubtitleEntities\$SubtitleSearch.class" to ":core:model - SubtitleAPI search query",
    "$apiPackageDir/ui/search/SyncSearchViewModel.class" to ":core:model - no in-repo users; only this sees its loss",
    "$apiPackageDir/ui/search/SyncSearchViewModel\$SyncSearchResultSearchResponse.class" to ":core:model - no in-repo users",
    "$apiPackageDir/ui/player/IGeneratorKt.class" to ":core:model - LOADTYPE_* facade; file must stay IGenerator.kt",
    "$apiPackageDir/syncproviders/AuthToken.class" to ":core:model - AuthAPI.login/refreshToken return type",
    "$apiPackageDir/syncproviders/AuthUser.class" to ":core:model - AuthAPI.user return type",
    "$apiPackageDir/syncproviders/AuthData.class" to ":core:model - the persisted 'auth_tokens' element",
    "$apiPackageDir/syncproviders/AuthLoginResponse.class" to ":core:model - AuthAPI.login(form) parameter",
    // All eight declared nested classes, matching the precedent set by AbstractSubtitleEntities (3/3),
    // SyncSearchViewModel (2/2) and the Auth DTOs (4/4). The ninth binary name in the dump,
    // TestingUtils${'$'}Logger${'$'}Message${'$'}WhenMappings, is a compiler-synthesised switch map: the dump guards it,
    // but it is an artefact of `when` over an enum, not a name an extension can reference.
    "$apiPackageDir/utils/TestingUtils.class" to ":core:model - outer of \$Logger / \$TestResult*",
    "$apiPackageDir/utils/TestingUtils\$Logger.class" to ":core:model - testHomepage parameter type",
    "$apiPackageDir/utils/TestingUtils\$Logger\$LogLevel.class" to ":core:model - Logger.log level parameter",
    "$apiPackageDir/utils/TestingUtils\$Logger\$Message.class" to ":core:model - Logger message element",
    "$apiPackageDir/utils/TestingUtils\$TestResult.class" to ":core:model - superclass of the three result types",
    "$apiPackageDir/utils/TestingUtils\$TestResult\$Companion.class" to ":core:model - TestResult.Companion",
    "$apiPackageDir/utils/TestingUtils\$TestResultList.class" to ":core:model - TestResult subtype",
    "$apiPackageDir/utils/TestingUtils\$TestResultLoad.class" to ":core:model - TestResult subtype",
    "$apiPackageDir/utils/TestingUtils\$TestResultProvider.class" to ":core:model - getDeferredProviderTests callback type",
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

/**
 * The committed dump, next to the `api/` dumps `abiValidation` writes in the modules it covers, so
 * every ABI file a reviewer has to read lives under a module's `api/` directory.
 */
val extensionApiDumpPath = "api/classes-jar.api"

/**
 * Entry prefixes of the classes dumped: the packages extensions compile against, whichever module
 * they come from (`com.lagradost.cloudstream3`, `com.lagradost.api`, `com.lagradost.cloudstream4`).
 * Everything else in the jar is left out — mostly the non-transitive `R` classes of every library
 * `:app` depends on (`androidx.*`, `com.google.*`, …), which extensions do not use through this
 * jar and which change on every dependency bump.
 */
val extensionApiDumpPackages = listOf("com/lagradost/")

val extensionApiUpdateCommand = "./gradlew $path:dumpExtensionApi"
val extensionApiJar = tasks.named<Jar>("makeJar").flatMap { it.archiveFile }

tasks.register<DumpExtensionApiTask>("dumpExtensionApi") {
    group = "extension api"
    description = "Writes $extensionApiDumpPath, the ABI dump of classes.jar. Review the diff before committing it."
    jar.set(extensionApiJar)
    packagePrefixes.set(extensionApiDumpPackages)
    apiDump.set(layout.projectDirectory.file(extensionApiDumpPath))
}

tasks.register<CheckExtensionApiTask>("checkExtensionApi") {
    group = "extension api"
    description = "Fails when classes.jar's ABI differs from $extensionApiDumpPath. Not part of check: see the plugin KDoc."
    jar.set(extensionApiJar)
    packagePrefixes.set(extensionApiDumpPackages)
    apiDump.set(layout.projectDirectory.file(extensionApiDumpPath))
    updateCommand.set(extensionApiUpdateCommand)
    diffReport.set(layout.buildDirectory.file("reports/extension-api/classes-jar.api.diff"))
}
