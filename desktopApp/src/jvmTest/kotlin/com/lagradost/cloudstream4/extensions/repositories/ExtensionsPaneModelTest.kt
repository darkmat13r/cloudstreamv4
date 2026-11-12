package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.extensions.ExtensionReview
import com.lagradost.cloudstream4.extensions.ExtensionReviewState
import com.lagradost.cloudstream4.extensions.LoadState
import com.lagradost.cloudstream4.extensions.ReviewStatus
import com.lagradost.cloudstream4.extensions.SandboxedLoad
import com.lagradost.cloudstream4.feature.extensions.ExtensionHash
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `KMP-110c`: the Extensions pane's state and verbs, with the network, the pipeline and the review replaced by fakes.
 * Every repository and plugin here is made up, and nothing is fetched.
 */
class ExtensionsPaneModelTest {
    private lateinit var home: File
    private lateinit var directory: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What each https URL answers. A URL not here is unreachable. */
    private val answers = Collections.synchronizedMap(mutableMapOf<String, Fetched>())
    private val gates = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private val fetchCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private fun reads(url: String) = fetchCounts[url]?.get() ?: 0
    private val pluginBytes = "made-up plugin".toByteArray()

    private val extensions = object : PaneExtensions {
        override suspend fun refresh(fileName: String): SandboxedLoad {
            calls += "refresh:$fileName"
            return SandboxedLoad(fileName, null, null, 0, "HELD", "a".repeat(64), LoadState.Held, fromRepository = true)
        }

        override fun forget(fileName: String) {
            calls += "forget:$fileName"
        }
    }

    private val reviewState = MutableStateFlow(ExtensionReviewState())
    private val reviews = object : PaneReviews {
        override val state = reviewState

        override fun track(load: SandboxedLoad) = reviewState.update {
            it.copy(reviews = it.reviews.filter { r -> r.fileName != load.fileName } +
                ExtensionReview(load.fileName, load.fileName, load.sha256, ReviewStatus.Held, emptyList()))
        }

        override fun forget(fileName: String) = reviewState.update { it.copy(reviews = it.reviews.filter { r -> r.fileName != fileName }) }
    }

    private lateinit var installed: InstalledFromRepositories
    private lateinit var store: RepositoryStore
    private lateinit var model: ExtensionsPaneModel

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("pane").toFile().canonicalFile
        directory = File(home, "extensions").apply { mkdirs() }
        installed = InstalledFromRepositories(InstalledFromRepositories.fileFor(directory))
        store = RepositoryStore(RepositoryStore.fileFor(directory))
        model = model(scope)
    }

    private fun model(scope: CoroutineScope): ExtensionsPaneModel {
        val fetcher = RepositoryReader.Fetcher { url, _ ->
            fetchCounts.computeIfAbsent(url) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            gates[url]?.await()
            answers[url] ?: Fetched.Failed(RepositoryFailure.Unreachable)
        }
        return ExtensionsPaneModel(
            scope = scope,
            store = store,
            cache = RepositoryCache(RepositoryCache.directoryFor(directory)),
            installer = RepositoryInstaller(directory, installed = installed, fetch = { url, _, _ -> answers[url] ?: Fetched.Failed(RepositoryFailure.Unreachable) }),
            installed = installed,
            extensions = extensions,
            reviews = reviews,
            reader = RepositoryReader(fetcher),
            now = { 1_700_000_000_000 },
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        home.deleteRecursively()
    }

    private fun serve(repo: String, name: String, vararg plugins: String) {
        val list = "$repo.list.json"
        answers[repo] = Fetched.Bytes("""{"name":"$name","manifestVersion":1,"pluginLists":["$list"]}""".toByteArray())
        answers[list] = Fetched.Bytes(plugins.joinToString(",", "[", "]").toByteArray())
    }

    private fun plugin(name: String, version: Int = 1, status: Int = 1): String {
        val url = "https://files.example/$name-$version.cs3"
        answers[url] = Fetched.Bytes(pluginBytes)
        return """{"internalName":"$name","name":"$name","url":"$url","version":$version,"status":$status,"apiVersion":1,""" +
            """"authors":[],"fileSize":${pluginBytes.size},"fileHash":"${ExtensionHash.of(pluginBytes)}"}"""
    }

    private fun until(what: String, condition: (ExtensionsPaneState) -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition(model.state.value)) delay(10) }
        model.state.value.also { assertTrue(condition(it), what) }
    }

    @Test
    fun `an add that cannot be a repository says why, without asking anything`() {
        mapOf(
            "harbour" to AddFailure.Shortcode,
            "http://files.example/repo.json" to AddFailure.NotHttps,
            "not a link" to AddFailure.Invalid,
        ).forEach { (text, failure) ->
            model.add(text)
            assertEquals(AddState.Failed(failure), model.state.value.add, text)
        }
        model.editing("harbour")
        assertEquals(AddState.Idle, model.state.value.add)
    }

    @Test
    fun `an add that the repository refuses says why, and nothing is saved`() {
        answers[A] = Fetched.Failed(RepositoryFailure.PrivateAddress)
        model.add(A)
        until("private") { it.add == AddState.Failed(AddFailure.PrivateAddress) }
        answers[A] = Fetched.Bytes("""{"name":"Harbour","manifestVersion":9,"pluginLists":[]}""".toByteArray())
        model.add(A)
        until("newer") { it.add == AddState.Failed(AddFailure.NewerFormat) }
        assertEquals(emptyList(), store.read().repositories)
    }

    @Test
    fun `an added repository is listed by name and count, and no URL is in the state`() {
        serve(A, "Harbour Mirror", plugin("Lumen"), plugin("Pebble"))
        model.add(A)
        val state = until("listed") { it.repositories.isNotEmpty() && it.add == AddState.Idle }
        val row = state.repositories.single()
        assertEquals("Harbour Mirror", row.name)
        assertEquals(2, row.extensions)
        assertTrue(row.reachable)
        assertFalse("files.example" in state.toString(), "the state names a URL")
        model.add(A)
        assertEquals(AddState.Failed(AddFailure.AlreadyAdded("Harbour Mirror")), model.state.value.add)
    }

    @Test
    fun `Cancel ends the read at once, and nothing is added`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        gates[A] = CompletableDeferred()
        model.add(A)
        assertEquals(AddState.Reading, model.state.value.add)
        model.cancelAdd()
        assertEquals(AddState.Idle, model.state.value.add)
        gates.getValue(A).complete(Unit)
        runBlocking { delay(200) }
        assertEquals(emptyList(), store.read().repositories)
    }

    @Test
    fun `one repository that does not answer is unreachable, never offline`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        model.add(A)
        until("added") { it.repositories.isNotEmpty() }
        answers.clear()
        model.refresh()
        val state = until("unreachable") { it.repositories.single().let { r -> !r.reachable } }
        assertFalse(state.offline)
        assertEquals(1, state.repositories.single().extensions, "the cached list stays")
        assertEquals(1_700_000_000_000, state.repositories.single().readAtMillis)
    }

    @Test
    fun `two or more that all fail is offline, and one failing of two is not`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        serve(B, "Quiet Coast", plugin("Pebble"))
        model.add(A)
        until("A") { it.repositories.size == 1 && it.add == AddState.Idle }
        model.add(B)
        until("B") { it.repositories.size == 2 && it.add == AddState.Idle }
        answers.remove(B)
        model.refresh()
        val one = until("one down") { it.repositories.count { r -> !r.reachable } == 1 }
        assertFalse(one.offline)
        answers.clear()
        model.refresh()
        val all = until("offline") { it.offline }
        assertEquals(1_700_000_000_000, all.listsFromMillis)
    }

    @Test
    fun `an install goes through the pipeline, comes back held, and is counted as waiting`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        model.add(A)
        val key = until("added") { it.repositories.isNotEmpty() }.repositories.single().key
        model.open(key)
        model.install(key, "Lumen")
        val state = until("held") { it.open?.rows?.single()?.state == RowState.Held }
        assertEquals(1, state.waiting)
        assertTrue(calls.single().startsWith("refresh:"), "$calls")
    }

    @Test
    fun `while a repository cannot be reached, nothing is installed from its cached list`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        model.add(A)
        val key = until("added") { it.repositories.isNotEmpty() }.repositories.single().key
        answers.remove(A)
        model.refresh()
        until("unreachable") { !it.repositories.single().reachable }
        model.open(key)
        model.install(key, "Lumen")
        runBlocking { delay(200) }
        assertEquals(RowState.Available, model.state.value.open?.rows?.single()?.state)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `an installed one shows an update, and one the repository turns off is not offered`() {
        serve(A, "Harbour Mirror", plugin("Lumen", version = 4), plugin("Kestrel", status = 0))
        model.add(A)
        val key = until("added") { it.repositories.isNotEmpty() }.repositories.single().key
        model.open(key)
        model.install(key, "Lumen")
        until("held") { it.open?.rows?.first()?.state == RowState.Held }
        reviewState.update { ExtensionReviewState() } // allowed and running: no longer on the review
        serve(A, "Harbour Mirror", plugin("Lumen", version = 9), plugin("Kestrel", status = 0))
        model.refresh()
        val rows = until("update") { it.open?.rows?.first()?.state is RowState.UpdateAvailable }.open!!.rows
        assertEquals(RowState.UpdateAvailable(4, 9), rows[0].state)
        assertEquals(RowState.NotOffered, rows[1].state)
        assertFalse(rows[1].offered)
    }

    @Test
    fun `removing a repository stops each of its extensions before deleting it`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        model.add(A)
        val key = until("added") { it.repositories.isNotEmpty() }.repositories.single().key
        model.install(key, "Lumen")
        until("installed") { installed.read().orEmpty().isNotEmpty() }
        val fileName = installed.read()!!.single().fileName
        assertEquals(1, model.installedFrom(key))
        model.removeRepository(key)
        until("removed") { it.repositories.isEmpty() }
        assertTrue(calls.indexOf("forget:$fileName") >= 0, "$calls")
        assertFalse(File(directory, fileName).exists())
        assertEquals(emptyList(), installed.read())
        assertEquals(emptyList(), store.read().repositories)
        assertEquals(0, RepositoryCache.directoryFor(directory).list()?.size ?: 0)
    }

    /** `#345` review: a repository whose installed files cannot be accounted for is not removed. */
    @Test
    fun `with the installed list unreadable, a repository is not removed and says so`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        model.add(A)
        val key = until("added") { it.repositories.isNotEmpty() }.repositories.single().key
        model.open(key)
        model.install(key, "Lumen")
        // BUG-99: the row's own end, not the installed list. The list has the entry before the file is moved into place
        // (`KMP-110b`), so a wait on the list can see it while the move is still to come.
        until("installed") { it.open?.rows?.single()?.state == RowState.Held }
        val fileName = installed.read()!!.single().fileName
        InstalledFromRepositories.fileFor(directory).writeText("garbage")
        model.removeRepository(key)
        until("refused") { it.removeFailed }
        assertEquals(1, model.state.value.repositories.size)
        assertTrue(File(directory, fileName).isFile, "its file was left with nothing to say where it came from")
        assertEquals(1, store.read().repositories.size)
    }

    @Test
    fun `a file that cannot be deleted keeps its review row, and its repository`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        model.add(A)
        val key = until("added") { it.repositories.isNotEmpty() }.repositories.single().key
        model.install(key, "Lumen")
        until("held") { it.waiting == 1 }
        assertTrue(directory.setWritable(false))
        try {
            model.removeRepository(key)
            until("refused") { it.removeFailed }
            assertEquals(1, model.state.value.waiting, "the review row went with a file that did not")
            assertEquals(1, store.read().repositories.size)
        } finally {
            directory.setWritable(true)
        }
    }

    /**
     * `#345` review: an install that finishes at once must not leave Installing behind, which would block a retry. With an
     * unconfined scope, an install that fails without suspending finishes inside `launch`, before the line after it:
     * the race, every time, rather than once in a while.
     */
    @Test
    fun `an install that fails at once can always be tried again`() {
        val immediate = model(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        serve(A, "Harbour Mirror", plugin("Lumen"))
        immediate.add(A)
        val key = immediate.state.value.repositories.single().key
        immediate.open(key)
        answers.remove("https://files.example/Lumen-1.cs3")
        repeat(3) { round ->
            immediate.install(key, "Lumen")
            assertIs<RowState.Failed>(immediate.state.value.open?.rows?.single()?.state, "round $round")
        }
    }

    /**
     * `#349` review: an add that finishes while the pane is not on screen still empties the field, because the field is the
     * model's. A pane restored afterwards has nothing to show. A failure keeps the link, so it can be fixed.
     */
    @Test
    fun `an add that succeeds while the pane is away empties the field, and a failure keeps it`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        gates[A] = CompletableDeferred()
        model.editing(A)
        model.add(A)
        assertEquals(A, model.state.value.link, "the link stays while it is read")
        gates.getValue(A).complete(Unit) // the read finishes with nothing drawing the pane
        val state = until("added") { it.repositories.isNotEmpty() && it.add == AddState.Idle }
        assertEquals("", state.link)
        assertFalse("tok" in state.toString() || "files.example" in state.toString(), state.toString())

        model.editing("http://files.example/x.json")
        model.add("http://files.example/x.json")
        assertEquals("http://files.example/x.json", model.state.value.link, "a failure keeps the link to be fixed")
    }

    /** `#349` review: coming back to the pane does not fetch every repository again. Retry still does. */
    @Test
    fun `load reads each repository once, and refresh reads it again`() {
        serve(A, "Harbour Mirror", plugin("Lumen"))
        model.add(A)
        until("added") { it.repositories.isNotEmpty() && it.add == AddState.Idle }
        val fresh = model(scope)
        val before = reads(A)
        repeat(3) { fresh.load() }
        runBlocking { withTimeout(10_000) { while (reads(A) == before) delay(10) } }
        runBlocking { delay(300) }
        assertEquals(before + 1, reads(A), "three loads, one read")
        fresh.refresh()
        runBlocking { withTimeout(10_000) { while (reads(A) == before + 1) delay(10) } }
        assertEquals(before + 2, reads(A))
    }

    private companion object {
        const val A = "https://files.example/harbour/repo.json?token=made-up"
        const val B = "https://files.example/quiet-coast/repo.json"
    }
}
