package com.lagradost.cloudstream4.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeRowPage
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceOption
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.feature.settings.SettingsPane
import com.lagradost.cloudstream4.feature.settings.SettingsRepository
import com.lagradost.cloudstream4.feature.settings.SettingsStorage
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDefaults
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowState
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import com.lagradost.cloudstream4.settings.SettingsContent
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** KMP-111 before/after renders. Writes PNGs only when `cloudstream.shell.renders` names a directory; guards nothing. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SidebarToneRenders {
    private val out = System.getProperty("cloudstream.shell.renders")?.let(::File)

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)
        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    private object OneSource : HomeSource {
        private val id = HomeSourceId("Harbor Films")
        override suspend fun sources() = HomeSources(listOf(HomeSourceOption(id, "Harbor Films")), installed = 1)
        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Loaded(
            listOf(
                HomeRowPage("Trending", false, (1..8).map { HomeItem("t$it", "Lantern Bay $it") }),
                HomeRowPage("New episodes", true, (1..6).map { HomeItem("e$it", "Paper Harbour $it") }),
            ),
            hasNext = false,
        )
    }

    private fun themed(width: Int, height: Int, content: @Composable () -> Unit) = ImageComposeScene(width, height, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
            }
        }
    }

    private fun shell(home: HomeSource) = themed(1280, 800) {
        DesktopApp(EscapeHandler(), home, library = InMemoryLibraryStore(), onOpenSettings = {})
    }

    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
        try { block(this) } finally { close() }
    }

    private fun ImageComposeScene.settle() = repeat(30) { render(it * 50_000_000L) }

    private fun ImageComposeScene.save(name: String) {
        val dir = out ?: return
        settle()
        File(dir, "$name.png").writeBytes(checkNotNull(render(30 * 50_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes)
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }
    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.words() }

    private fun ImageComposeScene.row(word: String): Offset = semanticsOwners.flatMap { it.rootSemanticsNode.all() }
        .filter { it.boundsInRoot.right <= 220f && it.config.getOrNull(SemanticsProperties.Role) != null && word in it.words() }
        .minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot.center

    @Test
    fun renders() {
        if (out == null) return
        out.mkdirs()
        shell(EmptyHome).use { scene ->
            scene.settle()
            scene.save("shell-home-no-extensions")
            scene.sendPointerEvent(PointerEventType.Move, scene.row("Search"))
            scene.save("shell-hover-search")
            scene.sendPointerEvent(PointerEventType.Move, scene.row("Settings"))
            scene.save("shell-hover-settings")
            val library = scene.row("Library")
            scene.sendPointerEvent(PointerEventType.Move, library)
            scene.sendPointerEvent(PointerEventType.Press, library)
            scene.sendPointerEvent(PointerEventType.Release, library)
            scene.sendPointerEvent(PointerEventType.Move, Offset(700f, 400f))
            scene.save("shell-library-selected")
        }
        shell(OneSource).use { scene -> scene.save("shell-home-extensions") }
        val root = Files.createTempDirectory("kmp111").toFile()
        try {
            themed(SettingsWindowDefaults.Width.value.toInt(), SettingsWindowDefaults.Height.value.toInt()) {
                SettingsContent(SettingsRepository(SettingsStorage.InMemory()), root, SettingsWindowState(pane = SettingsPane.Appearance))
            }.use { it.save("settings-appearance") }
        } finally {
            root.deleteRecursively()
        }
    }
}
