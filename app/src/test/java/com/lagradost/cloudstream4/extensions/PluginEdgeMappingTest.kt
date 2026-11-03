package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream3.plugins.PLUGIN_VERSION_NOT_SET
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionRepositoryIndex
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.extensions.InstalledExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PluginEdgeMappingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun online(name: String, path: String) = PluginData(name, "https://x/$name", true, path, 3)
    private fun local(name: String, path: String) = PluginData(name, null, false, path, 3)

    @Test
    fun statusCodesMapBothWaysAndKeepUnknownCodes() {
        val named = mapOf(
            0 to ExtensionStatus.Down,
            1 to ExtensionStatus.Ok,
            2 to ExtensionStatus.Slow,
            3 to ExtensionStatus.BetaOnly,
        )
        named.forEach { (code, status) ->
            assertEquals(status, extensionStatusOf(code))
            assertEquals(code, status.toWireCode())
        }
        for (code in listOf(4, -1, Int.MAX_VALUE)) {
            assertEquals(ExtensionStatus.Unrecognised(code), extensionStatusOf(code))
            assertEquals(code, extensionStatusOf(code).toWireCode())
        }
    }

    @Test
    fun repositoryMapsEveryField() {
        val mapped = Repository("icon", "name", "description", 1, listOf("a", "b"))
            .toExtensionRepositoryIndex()
        assertEquals(
            ExtensionRepositoryIndex(
                name = "name",
                description = "description",
                iconUrl = "icon",
                manifestVersion = 1,
                pluginListUrls = listOf("a", "b"),
            ),
            mapped,
        )
    }

    /** Fourteen positional fields, most of them `String`: every value differs, so a swap shows. */
    @Test
    fun sitePluginMapsEveryFieldToTheRightOne() {
        val mapped = SitePlugin(
            url = "url",
            status = 2,
            version = 7,
            apiVersion = 1,
            name = "name",
            internalName = "internal",
            authors = listOf("author"),
            description = "description",
            repositoryUrl = "page",
            tvTypes = listOf("Movie"),
            language = "en",
            iconUrl = "icon",
            fileSize = 99L,
            fileHash = "sha256-aa",
        ).toExtensionListing()

        assertEquals(
            ExtensionListing(
                internalName = "internal",
                name = "name",
                downloadUrl = "url",
                status = ExtensionStatus.Slow,
                version = 7,
                apiVersion = 1,
                authors = listOf("author"),
                description = "description",
                iconUrl = "icon",
                repositoryUrl = "page",
                tvTypes = listOf("Movie"),
                language = "en",
                fileSizeBytes = 99L,
                fileHash = "sha256-aa",
            ),
            mapped,
        )
    }

    @Test
    fun sitePluginNullablesBecomeTheListingsAbsences() {
        val mapped = SitePlugin(
            "url", 1, 1, 1, "n", "i", emptyList(), null, null, null, null, null, null, null,
        ).toExtensionListing()
        assertEquals(emptyList<String>(), mapped.tvTypes)
        assertEquals(null, mapped.fileHash)
        assertEquals(null, mapped.jarUrl)
    }

    @Test
    fun pluginDataMapsEveryFieldAndKeepsTheUnsetVersion() {
        val mapped = PluginData("internal", "url", true, "/path", PLUGIN_VERSION_NOT_SET)
            .toInstalledExtension()
        assertEquals(
            InstalledExtension("internal", "/path", PLUGIN_VERSION_NOT_SET, true, "url"),
            mapped,
        )
    }

    @Test
    fun loadedKeepsOrderPrefersOnlineAndReportsAPluginWithNoRecord() {
        val result = loadedExtensions(
            loadedPaths = listOf("/b", "/orphan.cs3", "/a"),
            online = listOf(online("A", "/a")),
            local = listOf(local("B", "/b"), local("shadowed", "/a")),
        )
        assertEquals(listOf("B", "orphan.cs3", "A"), result.map { it.internalName })
        assertEquals(
            InstalledExtension("orphan.cs3", "/orphan.cs3", PLUGIN_VERSION_NOT_SET, false, null),
            result[1],
        )
    }

    @Test
    fun apiNameDropsProviderAnywhereIgnoringCase() {
        assertEquals("Foo", apiNameOf("FooProvider"))
        assertEquals("Foo", apiNameOf("providerFooPROVIDER"))
        assertEquals("Foo", apiNameOf("Foo"))
    }

    @Test
    fun aUniqueNameSelectsItsOwnFile() {
        assertEquals("Foo", apiNameForLoad("/foo", listOf(online("FooProvider", "/foo")), emptyList()))
        assertEquals("Bar", apiNameForLoad("/bar", emptyList(), listOf(local("Bar", "/bar"))))
    }

    /** Two repositories with one `internalName`: the second cannot be selected by name. */
    @Test
    fun theLoserOfASharedNameIsRefusedAndTheWinnerIsNot() {
        val saved = listOf(online("Foo", "/repoA/foo"), online("Foo", "/repoB/foo"))
        assertEquals("Foo", apiNameForLoad("/repoA/foo", saved, emptyList()))
        assertThrows(IllegalStateException::class.java) {
            apiNameForLoad("/repoB/foo", saved, emptyList())
        }
    }

    @Test
    fun onlineWinsOverLocalAsInLoadSinglePlugin() {
        val online = listOf(online("Foo", "/online"))
        val local = listOf(local("Foo", "/local"))
        assertEquals("Foo", apiNameForLoad("/online", online, local))
        assertThrows(IllegalStateException::class.java) { apiNameForLoad("/local", online, local) }
    }

    /** `Foo` and `FooProvider` are different plugins with one api name. */
    @Test
    fun namesThatCollapseTogetherAreAlsoAmbiguous() {
        val saved = listOf(online("Foo", "/a"), online("FooProvider", "/b"))
        assertThrows(IllegalStateException::class.java) { apiNameForLoad("/b", saved, emptyList()) }
    }

    @Test
    fun aLocationWithNoRecordIsRefused() {
        assertThrows(IllegalStateException::class.java) {
            apiNameForLoad("/nowhere", emptyList(), emptyList())
        }
    }

    @Test
    fun managedLocationIsStrictlyInsideARoot() {
        val root = temp.newFolder("Extensions")
        val roots = listOf(root)
        assertTrue(isManagedLocation(File(root, "repo/a.cs3").path, roots))
        assertFalse("the root itself", isManagedLocation(root.path, roots))
        assertFalse("traversal", isManagedLocation(File(root, "../x.cs3").path, roots))
        assertFalse("sibling with the same prefix", isManagedLocation("${root.path}2/a.cs3", roots))
        assertFalse("relative", isManagedLocation("a.cs3", roots))
        assertFalse("no roots", isManagedLocation(File(root, "a.cs3").path, emptyList()))
    }

    /** `/data/user/0` is a link on Android, so a root reached through a link has to match. */
    @Test
    fun managedLocationResolvesLinksBothWays() {
        val realRoot = temp.newFolder("real")
        val linkedRoot = File(temp.root, "linked")
        Files.createSymbolicLink(linkedRoot.toPath(), realRoot.toPath())
        assertTrue(isManagedLocation(File(linkedRoot, "a.cs3").path, listOf(realRoot)))
        assertTrue(isManagedLocation(File(realRoot, "a.cs3").path, listOf(linkedRoot)))

        val outside = temp.newFile("outside.cs3")
        val escape = File(realRoot, "escape.cs3")
        Files.createSymbolicLink(escape.toPath(), outside.toPath())
        assertFalse("a link out of the root", isManagedLocation(escape.path, listOf(realRoot)))
    }

    @Test
    fun aHostileNameCannotReachTheLog() {
        assertEquals("evil0m", safeForLog("evil\u001b[0m\n"))
        assertEquals(64, safeForLog("a".repeat(500)).length)
    }

    /**
     * [apiNameForLoad] restates `loadSinglePlugin`'s selection, and `PluginManager` cannot be run
     * here: its initialiser needs `Environment`. So the source is pinned, and a change to the
     * selection fails this rather than silently loading the wrong file.
     */
    @Test
    fun theSelectionAndLocationsRestatedHereStillMatchPluginManager() {
        val source = listOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it, "com/lagradost/cloudstream3/plugins/PluginManager.kt") }
            .firstOrNull { it.isFile }
            ?.readText()
        requireNotNull(source) { "PluginManager.kt not found from ${File(".").absolutePath}" }

        val selection = source.substringAfter("suspend fun loadSinglePlugin").substringBefore("?.let")
        val online = selection.indexOf("getPluginsOnline().firstOrNull")
        val local = selection.indexOf("?: getPluginsLocal().firstOrNull")
        assertTrue("online is searched, then local", online in 0 until local)
        assertEquals(
            2,
            selection.split("""it.internalName.replace("provider", "", ignoreCase = true) == apiName""").size - 1,
        )
        assertTrue(source.contains("""File("${'$'}{context.filesDir}/${'$'}{ONLINE_PLUGINS_FOLDER}/${'$'}{folderName}/${'$'}fileName.cs3")"""))
        assertTrue(source.contains("""File(context.getExternalFilesDir(null), "plugins")"""))
    }
}
