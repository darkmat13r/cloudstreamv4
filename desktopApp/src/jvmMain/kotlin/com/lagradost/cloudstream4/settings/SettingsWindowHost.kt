package com.lagradost.cloudstream4.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.title_settings
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.downloads.DesktopDownloads
import com.lagradost.cloudstream4.feature.settings.SettingsRepository
import com.lagradost.cloudstream4.feature.settings.desktop.DownloadFolder
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsAction
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDefaults
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowDesktop
import com.lagradost.cloudstream4.feature.settings.desktop.SettingsWindowState
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import kotlin.concurrent.thread

/**
 * Asks macOS to put Settings… in the app menu, on ⌘, as every Mac app has it (`DS-24`), and calls [open] when it is
 * chosen. Returns false where the platform has no such item, as off a Mac; the window is then not reachable yet.
 */
internal fun installSettingsOpener(open: () -> Unit): Boolean {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_PREFERENCES)) return false
    desktop.setPreferencesHandler { open() }
    return true
}

/**
 * The Settings window: [SettingsWindowDefaults.Width] wide at every height, between [SettingsWindowDefaults.MinHeight]
 * and as tall as the screen allows. Its title bar is transparent over the content, which leaves room for it.
 */
@Composable
internal fun SettingsWindow(
    settings: SettingsRepository,
    onClose: () -> Unit,
    downloadRoot: File = remember { DesktopDownloads.defaultDownloadRoot() },
) {
    val state = rememberWindowState(size = DpSize(SettingsWindowDefaults.Width, SettingsWindowDefaults.Height))
    Window(
        onCloseRequest = onClose,
        title = stringResource(Res.string.title_settings),
        state = state,
    ) {
        LaunchedEffect(window) {
            val width = SettingsWindowDefaults.Width.value.toInt()
            window.minimumSize = Dimension(width, SettingsWindowDefaults.MinHeight.value.toInt())
            window.maximumSize = Dimension(width, Int.MAX_VALUE)
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    SettingsContent(settings, downloadRoot)
                }
            }
        }
    }
}

/** The window's content over a real [SettingsRepository]: what a test draws without a window. */
@Composable
internal fun SettingsContent(
    settings: SettingsRepository,
    downloadRoot: File,
    state: SettingsWindowState = remember { SettingsWindowState() },
) {
    val values by settings.values.collectAsState()
    SettingsWindowDesktop(
        state = state,
        values = values,
        folder = downloadFolder(downloadRoot),
        onAction = { action ->
            when (action) {
                is SettingsAction.SetDownloadsAtOnce -> settings.setDownloadsAtOnce(action.count)
                SettingsAction.RevealDownloadFolder -> reveal(downloadRoot)
            }
        },
    )
}

/** The folder's name only, never its path (`DS-24` ST6), and whether it is there now. */
internal fun downloadFolder(root: File): DownloadFolder = DownloadFolder(root.name, root.isDirectory)

/** Opens the folder in Finder, off the UI thread: Finder may take a moment to answer. */
private fun reveal(directory: File) {
    if (!directory.isDirectory || !Desktop.isDesktopSupported()) return
    thread(isDaemon = true, name = "settings-reveal") {
        runCatching { Desktop.getDesktop().open(directory) }
    }
}
