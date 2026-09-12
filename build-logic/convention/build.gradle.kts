import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `kotlin-dsl`
}

group = "com.lagradost.cloudstream.buildlogic"

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Resolves a `[plugins]` alias from `gradle/libs.versions.toml` to its plugin *marker*
 * coordinate, so the convention plugins can apply it from their own `plugins { }` block
 * without the version being restated here.
 */
fun pluginMarker(alias: String): String {
    val plugin = catalog.findPlugin(alias).get().get()
    return "${plugin.pluginId}:${plugin.pluginId}.gradle.plugin:${plugin.version.requiredVersion}"
}

dependencies {
    implementation(pluginMarker("kotlin-multiplatform"))
    implementation(pluginMarker("android-multiplatform-library"))
    implementation(pluginMarker("android-lint"))
    implementation(pluginMarker("compose-multiplatform"))
    implementation(pluginMarker("compose-compiler"))
}
