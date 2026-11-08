package com.lagradost.cloudstream4.account

import com.lagradost.cloudstream4.account.DesktopProfileFile.Companion.cleaned
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.library.DesktopLibraryStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `KMP-95a`: the Mac's profiles file, and the library each profile reads. Everything is in a temporary directory. */
class DesktopProfilesTest {
    private val directory: File = Files.createTempDirectory("profiles-test").toFile()
    private val file = File(directory, DesktopProfileFile.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun profiles(onSaveFailed: (Throwable) -> Unit = { throw it }) =
        DesktopProfiles(DesktopProfileFile(file), libraryDirectory = directory, onSaveFailed = onSaveFailed)

    @Test
    fun `with no file there is the first profile alone, current, and no picker`() {
        val loaded = DesktopProfileFile(file).load()
        assertEquals(StoredProfiles(current = 0, profiles = listOf(StoredProfile(0))), loaded)
        assertFalse(profiles().picksAtLaunch)
        assertEquals(listOf("Default"), profiles().cards("Default").map { it.name }, "the first profile's name is the language's")
        assertFalse(file.exists(), "reading writes nothing")
    }

    @Test
    fun `a choice is written owner-only, read back at the next launch, and leaves no temporary file`() {
        DesktopProfileFile(file).save(StoredProfiles(profiles = listOf(StoredProfile(0), StoredProfile(3, "Robin"))))
        val first = profiles()
        assertTrue(first.picksAtLaunch, "two profiles, the default counted, open the picker")
        first.choose(3)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        val next = profiles()
        assertEquals(3, next.stored.current)
        assertEquals(listOf(false, true), next.cards("Default").map { it.current })
    }

    @Test
    fun `choosing a profile that is not there, or the current one, changes nothing and writes nothing`() {
        val profiles = profiles()
        profiles.choose(7)
        profiles.choose(0)
        assertEquals(0, profiles.stored.current)
        assertFalse(file.exists())
    }

    @Test
    fun `a choice that cannot be written is reported and still holds until the app quits`() {
        DesktopProfileFile(file).save(StoredProfiles(profiles = listOf(StoredProfile(0), StoredProfile(1, "Sam"))))
        var failed: Throwable? = null
        val profiles = profiles(onSaveFailed = { failed = it })
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("r-x------"))
        try {
            profiles.choose(1)
        } finally {
            Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(failed != null, "the failure is reported")
        assertEquals(1, profiles.stored.current, "the choice holds for this launch")
        assertEquals(0, profiles().stored.current, "the file still says what it said")
    }

    @Test
    fun `a file that is missing its first profile, repeats an id or names one badly is cleaned on the way in`() {
        val dirty = StoredProfiles(
            current = 9,
            profiles = listOf(
                StoredProfile(2, "Sam‮\u0007 "),
                StoredProfile(2, "Again"),
                StoredProfile(-1, "Negative"),
                StoredProfile(4, "x".repeat(100)),
                StoredProfile(5, "‏"),
            ),
        ).cleaned()
        assertEquals(listOf(0, 2, 4, 5), dirty.profiles.map { it.id }, "the first is put back, the repeat and the negative dropped")
        assertEquals("Sam", dirty.profiles[1].name)
        assertEquals(DesktopProfileFile.MAX_NAME_CHARS, dirty.profiles[2].name?.length)
        assertEquals(null, dirty.profiles[3].name, "a name with nothing left is no name")
        assertEquals(0, dirty.current, "a current that is not a profile is the first")
    }

    @Test
    fun `no more than the cap is kept`() {
        val many = StoredProfiles(profiles = (0 until 100).map { StoredProfile(it, "P$it") }).cleaned()
        assertEquals(DesktopProfileFile.MAX_PROFILES, many.profiles.size)
    }

    @Test
    fun `a file too large, or not this format, reads as the first profile alone`() {
        file.writeText("{\"profiles\":[]}" + " ".repeat(DesktopProfileFile.MAX_FILE_BYTES.toInt()))
        assertEquals(StoredProfiles(), DesktopProfileFile(file).load())
        file.writeText("not json")
        assertEquals(StoredProfiles(), DesktopProfileFile(file).load())
        file.writeText("{\"current\":\"one\"}")
        assertEquals(StoredProfiles(), DesktopProfileFile(file).load())
    }

    @Test
    fun `the first profile keeps the library files the Mac had, and every other profile has its own`() {
        val profiles = profiles()
        assertEquals(File(directory, DesktopLibraryStore.defaultTitlesFile().name), profiles.titlesFile(0))
        assertEquals(File(directory, DesktopLibraryStore.defaultPositionsFile().name), profiles.positionsFile(0))
        assertEquals("library.tsv", profiles.titlesFile(0).name)
        assertEquals("watch-positions.tsv", profiles.positionsFile(0).name)
        assertEquals("library-2.tsv", profiles.titlesFile(2).name)
        assertEquals("watch-positions-2.tsv", profiles.positionsFile(2).name)
    }

    @Test
    fun `a title kept by one profile is not in another's library`() = runBlocking {
        val profiles = profiles()
        val title = LibraryTitle(LibraryTitleId("harbor"), "Harbor Lights", bucket = null, keptAt = 0L)
        profiles.libraryFor(0).remember(title)
        assertEquals(listOf("Harbor Lights"), profiles.libraryFor(0).titles().map { it.title })
        assertEquals(emptyList(), profiles.libraryFor(2).titles())
        assertTrue(profiles.titlesFile(0).exists())
        assertFalse(profiles.titlesFile(2).exists())
    }
}
