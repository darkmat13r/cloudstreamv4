package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.extensions.ExtensionChoice
import com.lagradost.cloudstream4.extensions.ExtensionDecisions
import com.lagradost.cloudstream4.feature.extensions.ExtensionHash
import com.lagradost.cloudstream4.feature.extensions.ExtensionListing
import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import com.lagradost.cloudstream4.feature.extensions.ExtensionVerification
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-110b`: a repository's file put in the extensions directory, and nothing else. The bytes here are made up and
 * never a plugin: this class tests where they land and what is recorded, and `RepositoryInstallHoldTest` what the
 * pipeline then does with a real fixture.
 */
class RepositoryInstallerTest {
    private lateinit var home: File
    private lateinit var directory: File
    private val files = mutableMapOf<String, Fetched>()
    private val fetches = AtomicInteger()
    private val bytes = "made-up plugin bytes".toByteArray()
    private val hash = ExtensionHash.of(bytes)

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("install").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun installer(beforeMove: () -> Unit = {}) = RepositoryInstaller(
        directory,
        fetch = { url, _ ->
            fetches.incrementAndGet()
            files[url] ?: Fetched.Failed(RepositoryFailure.NotFound)
        },
        beforeMove = beforeMove,
    )

    private fun listing(
        internalName: String = "Lanternfish",
        status: ExtensionStatus = ExtensionStatus.Ok,
        version: Int = 3,
        size: Long? = bytes.size.toLong(),
        fileHash: String? = hash,
        jar: Boolean = false,
    ) = ExtensionListing(
        internalName = internalName,
        name = internalName,
        downloadUrl = "$FILES/$version.cs3",
        status = status,
        version = version,
        apiVersion = 1,
        fileSizeBytes = size,
        fileHash = fileHash,
        jarUrl = if (jar) "$FILES/$version.jar" else null,
        jarHash = if (jar) fileHash else null,
        jarFileSizeBytes = if (jar) size else null,
    ).also {
        files[it.downloadUrl] = Fetched.Bytes(bytes)
        it.jarUrl?.let { url -> files[url] = Fetched.Bytes(bytes) }
    }

    private fun install(listing: ExtensionListing, installer: RepositoryInstaller = installer()) =
        runBlocking { installer.install(REPO, listing) }

    private fun decisions() = ExtensionDecisions(ExtensionDecisions.fileFor(directory)).read()

    private fun installed() = InstalledFromRepositories(InstalledFromRepositories.fileFor(directory)).read()

    /** What is in the directory, leaving out the empty staging directory. */
    private fun listed() = directory.list().orEmpty().filter { it != ".incoming" }.sorted()

    @Test
    fun `a verified file lands whole, with its published hash beside it, held`() {
        val result = assertIs<InstallResult.Installed>(install(listing()))
        val file = File(directory, result.fileName)
        assertContentEquals(bytes, file.readBytes())
        assertEquals(hash, File(directory, result.fileName + ".sha256").readText())
        assertEquals(ExtensionVerification.Verified, result.verification)
        val sha = hash.removePrefix(ExtensionHash.PREFIX)
        assertEquals(ExtensionChoice.Pending, decisions().choiceFor(result.fileName, sha), "installing is not consent")
        assertEquals(InstalledFromRepository(result.fileName, REPO, "Lanternfish", 3, sha, verified = true), installed()?.single())
        assertEquals(listOf(result.fileName, result.fileName + ".sha256"), listed())
        assertEquals(emptyList(), File(directory, ".incoming").list()?.toList().orEmpty(), "nothing left staged")
    }

    @Test
    fun `the jar is taken when there is one, and it replaces the cs3 it supersedes`() {
        val first = assertIs<InstallResult.Installed>(install(listing()))
        assertTrue(first.fileName.endsWith(".cs3"))
        val second = assertIs<InstallResult.Installed>(install(listing(version = 4, jar = true)))
        assertTrue(second.fileName.endsWith(".jar"))
        assertEquals(listOf(first.fileName), second.replaced)
        assertEquals(listOf(second.fileName, second.fileName + ".sha256"), listed())
        assertEquals(listOf(second.fileName), installed()?.map { it.fileName })
    }

    @Test
    fun `a file that is not what its repository describes writes nothing`() {
        files.clear()
        val wrongHash = listing(fileHash = "sha256-" + "0".repeat(64))
        assertEquals(InstallResult.Failed(InstallFailure.HashMismatch), install(wrongHash))
        val wrongSize = listing(size = bytes.size + 1L)
        assertEquals(InstallResult.Failed(InstallFailure.SizeMismatch), install(wrongSize))
        assertEquals(emptyList(), listed())
        assertTrue(decisions().all.isEmpty(), "nothing recorded")
    }

    @Test
    fun `a stated size past the cap, or a status not offered, is refused before anything is fetched`() {
        assertEquals(
            InstallResult.Failed(InstallFailure.Fetch(RepositoryFailure.TooLarge)),
            install(listing(size = RepositoryLimits.MAX_PLUGIN_BYTES + 1)),
        )
        listOf(ExtensionStatus.Down, ExtensionStatus.BetaOnly, ExtensionStatus.Unrecognised(9)).forEach { status ->
            assertEquals(InstallResult.Failed(InstallFailure.NotOffered), install(listing(status = status)), "$status")
        }
        assertEquals(0, fetches.get())
        assertEquals(emptyList(), listed())
    }

    @Test
    fun `with no published hash it installs unverified, and is held all the same`() {
        val result = assertIs<InstallResult.Installed>(install(listing(fileHash = null)))
        assertIs<ExtensionVerification.Skipped>(result.verification)
        assertEquals(listOf(result.fileName), listed(), "no sidecar: it would claim a check that was not made")
        assertEquals(false, installed()?.single()?.verified)
        assertEquals(ExtensionChoice.Pending, decisions().choiceFor(result.fileName, hash.removePrefix(ExtensionHash.PREFIX)))
    }

    @Test
    fun `an unverified update removes the sidecar the verified one left`() {
        val verified = assertIs<InstallResult.Installed>(install(listing()))
        assertTrue(File(directory, verified.fileName + ".sha256").isFile)
        val unverified = assertIs<InstallResult.Installed>(install(listing(version = 4, fileHash = null)))
        assertIs<ExtensionVerification.Skipped>(unverified.verification)
        assertEquals(listOf(unverified.fileName), listed(), "an old sidecar would refuse the new file")
    }

    @Test
    fun `a failed download writes nothing and says why`() {
        val gone = listing()
        files[gone.downloadUrl] = Fetched.Failed(RepositoryFailure.PrivateAddress)
        assertEquals(InstallResult.Failed(InstallFailure.Fetch(RepositoryFailure.PrivateAddress)), install(gone))
        assertEquals(emptyList(), listed())
    }

    @Test
    fun `a name that tries to leave the directory stays in it`() {
        val hostile = listOf("../../outside", "a/b", "..", "x${Char(0x202E)}gpj.cs3", "\\..\\win", " lead", "-dash", "....")
        hostile.forEach { name ->
            val result = assertIs<InstallResult.Installed>(install(listing(internalName = name)), name)
            val file = File(directory, result.fileName)
            assertEquals(directory, file.canonicalFile.parentFile, name)
            assertTrue(SAFE_NAME.matches(result.fileName), "${result.fileName} from $name")
        }
        assertFalse(File(home, "outside.cs3").exists())
    }

    @Test
    fun `names that read the same, or one name from two repositories, are different files`() {
        val plus = assertIs<InstallResult.Installed>(install(listing(internalName = "18+"))).fileName
        val underscore = assertIs<InstallResult.Installed>(install(listing(internalName = "18_"))).fileName
        val elsewhere = RepositoryInstaller.fileStem("$FILES/other-repo.json", "18+")
        assertEquals(3, setOf(plus, underscore, "$elsewhere.cs3").size)
    }

    @Test
    fun `removing takes the file and its sidecar, keeps the choice, and leaves side-loaded files alone`() = runBlocking {
        val result = assertIs<InstallResult.Installed>(install(listing()))
        File(directory, "SideLoaded.jar").writeText("not ours")
        assertFalse(installer().remove("SideLoaded.jar"))
        assertTrue(File(directory, "SideLoaded.jar").isFile)
        assertTrue(installer().remove(result.fileName))
        assertEquals(listOf("SideLoaded.jar"), listed())
        assertEquals(emptyList(), installed())
        assertEquals(ExtensionChoice.Pending, decisions().choiceFor(result.fileName, hash.removePrefix(ExtensionHash.PREFIX)))
    }

    @Test
    fun `an update that fails before its move leaves the earlier file`() {
        val first = assertIs<InstallResult.Installed>(install(listing()))
        val before = File(directory, first.fileName).readBytes()
        val update = listing(version = 4)
        files[update.downloadUrl] = Fetched.Bytes(bytes)
        val failed = install(update, installer(beforeMove = { throw IOException("stopped") }))
        assertEquals(InstallResult.Failed(InstallFailure.NotSaved), failed)
        assertContentEquals(before, File(directory, first.fileName).readBytes())
        assertEquals(3, installed()?.single()?.version)
    }

    @Test
    fun `an update is offered for a higher version, or always-update, and never for a status not offered`() {
        val entry = InstalledFromRepository("f.cs3", REPO, "Lanternfish", 3, "a".repeat(64), verified = true)
        val installer = installer()
        assertEquals(4, installer.updateFor(entry, listing(version = 4)))
        assertNull(installer.updateFor(entry, listing(version = 3)))
        assertNull(installer.updateFor(entry, listing(version = 2)))
        assertEquals(RepositoryIndexParser.ALWAYS_UPDATE, installer.updateFor(entry, listing(version = -1)))
        assertNull(installer.updateFor(entry, listing(version = 4, status = ExtensionStatus.Down)))
        assertNull(installer.updateFor(entry, listing(internalName = "Mooring", version = 4)))
    }

    @Test
    fun `the installed list is the person's alone`() {
        install(listing())
        val mode = Files.getPosixFilePermissions(InstalledFromRepositories.fileFor(directory).toPath())
        assertEquals(PosixFilePermissions.fromString("rw-------"), mode)
    }

    private companion object {
        const val REPO = "https://files.example/harbour/repo.json?token=made-up"
        const val FILES = "https://files.example/harbour"
        val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,39}-[0-9a-f]{12}\\.(cs3|jar)")
    }
}
