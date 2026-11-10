package com.lagradost.cloudstream4.extensions.repositories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.settings.SettingsPane
import com.lagradost.cloudstream4.feature.settings.SettingsValues
import com.lagradost.cloudstream4.feature.settings.desktop.DownloadFolder
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDefaults
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDesktop
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowState
import java.io.File
import java.util.Locale
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue

/**
 * `KMP-110c2` (`#349` review): `DS-27`'s X3, X4, X5a and X8, drawn in the whole Settings window and written as PNGs when
 * `-Pcloudstream.extensions.renders=<directory>` names one. They are for looking at, and guard nothing; skipped
 * otherwise. Every name is made up.
 */
class ExtensionsPaneRenders {
    private val directory: String? = System.getProperty("cloudstream.extensions.renders")

    private fun render(name: String, state: ExtensionsPaneState, height: Int = HEIGHT, locale: Locale = Locale.ENGLISH) {
        assumeTrue("no render directory asked for", directory != null)
        val before = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val scene = ImageComposeScene(WIDTH, height, Density(1f)) {
                CloudStreamTheme {
                    CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                        Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                            SettingsWindowDesktop(
                                values = SettingsValues(),
                                folder = DownloadFolder("Harbor Films", exists = true),
                                onAction = {},
                                state = SettingsWindowState(pane = SettingsPane.Extensions),
                                extensions = { ExtensionsPaneDesktop(state, onAction = {}, installedFrom = { 6 }, focusLink = false) },
                            )
                        }
                    }
                }
            }
            try {
                repeat(SETTLE_FRAMES) { scene.render(it * FRAME_NANOS) }
                val png = checkNotNull(scene.render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
                File(checkNotNull(directory), "$name.png").writeBytes(png)
            } finally {
                scene.close()
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    private val repositories = listOf(
        RepositoryRow("k1", "Harbor Mirror", 24, 6, READ_AT, reachable = true),
        RepositoryRow("k2", "Example Repository", 12, 3, READ_AT - DAY, reachable = true),
        RepositoryRow("k3", "Quiet Coast Collection", 86, 0, READ_AT - 2 * HOUR, reachable = true),
    )

    private fun row(name: String, state: RowState, offered: Boolean = true, version: Int = 7) = ExtensionRow(
        internalName = name, name = name, version = version, language = "en", tvTypes = listOf("Movie", "TvSeries"),
        sizeBytes = 1_200_000, state = state, offered = offered,
    )

    private val rows = listOf(
        row("Lumen", RowState.Available),
        row("Driftwood", RowState.Installing(700_000, 1_200_000)),
        row("Pebble", RowState.Installed(verified = true), version = 4),
        row("Saltmarsh", RowState.UpdateAvailable(4, 9)),
        row("Northlight", RowState.Held),
        row("Copperleaf", RowState.KeptOff),
        row("Fernway", RowState.Blocked),
        row("Juniper", RowState.Installed(verified = false)),
        row("Kestrel", RowState.NotOffered, offered = false),
        row("Marlow", RowState.Failed(InstallFailure.Fetch(RepositoryFailure.Unreachable))),
    )

    @Test
    fun `X3 the repository list`() = render("X3-repositories", ExtensionsPaneState(repositories = repositories))

    @Test
    fun `X4 one repository's extensions`() =
        render("X4-extensions", ExtensionsPaneState(open = OpenRepository("k1", "Harbor Mirror", READ_AT, reachable = true, rows = rows)), height = TALL)

    @Test
    fun `X5a one repository unreachable`() = render(
        "X5a-repository-unreachable",
        ExtensionsPaneState(open = OpenRepository("k2", "Example Repository", READ_AT - DAY, reachable = false, rows = rows.take(5))),
    )

    /** At the minimum, in German. The new keys are English only until they are translated, so those fall back. */
    @Test
    fun `X8 the minimum size in German`() = render(
        "X8-minimum-german",
        ExtensionsPaneState(open = OpenRepository("k1", "Harbor Mirror", READ_AT, reachable = true, rows = rows.take(6))),
        height = SettingsWindowDefaults.MinHeight.value.toInt(),
        locale = Locale.GERMAN,
    )

    private companion object {
        val WIDTH = SettingsWindowDefaults.Width.value.toInt()
        val HEIGHT = SettingsWindowDefaults.Height.value.toInt()
        const val TALL = 960
        const val READ_AT = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
    }
}
