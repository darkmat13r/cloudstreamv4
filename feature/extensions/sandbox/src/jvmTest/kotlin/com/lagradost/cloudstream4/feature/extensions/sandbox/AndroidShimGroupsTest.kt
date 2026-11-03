package com.lagradost.cloudstream4.feature.extensions.sandbox

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.text.SpannableStringBuilder
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.DataStore.containsKey
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.Event
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** `KMP-18`: one test per shim group, then the rule all of them answer to. */
class AndroidShimGroupsTest {

    @Test
    fun `os - a handler runs tasks in order, on one thread, honouring delay and removal`() {
        val handler = Handler(Looper.getMainLooper())
        val seen = mutableListOf<String>()
        val threads = mutableSetOf<Thread>()
        val done = CountDownLatch(1)
        handler.post(Runnable { seen += "first"; threads += Thread.currentThread() })
        handler.post(Runnable { throw IllegalStateException("a plugin's bug") })
        val removed = Runnable { seen += "removed" }
        handler.postDelayed(removed, 200)
        handler.removeCallbacks(removed)
        handler.postDelayed(Runnable {
            seen += "late"
            threads += Thread.currentThread()
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            done.countDown()
        }, 400)
        assertTrue(done.await(5, TimeUnit.SECONDS), "a plugin's exception must not stop later tasks")
        assertEquals(listOf("first", "late"), seen)
        assertEquals(1, threads.size)
        assertNull(Looper.myLooper(), "this test's thread is not the main thread")
    }

    @Test
    fun `os - bundle, version and clock are plain values`() {
        val bundle = Bundle().apply { putString("a", "b"); putInt("n", 3); putBoolean("t", true); putDouble("d", 1.5) }
        assertEquals("b", bundle.getString("a"))
        assertEquals("fallback", bundle.getString("missing", "fallback"))
        assertEquals(3, bundle.getInt("n", 0))
        assertEquals(1.5, bundle.getDouble("d", 0.0))
        assertTrue(bundle.getBoolean("t", false))
        assertTrue(Build.VERSION.SDK_INT >= 21)
        assertTrue(SystemClock.uptimeMillis() > 0)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    @Test
    fun `main dispatcher - only a plugin host has one, so the app stays as it was`() {
        val failure = assertFailsWith<IllegalStateException> { HostMainDispatcherFactory().createDispatcher(emptyList()) }
        assertTrue("plugin host" in failure.message.orEmpty())
        assertEquals(Int.MIN_VALUE, HostMainDispatcherFactory().loadPriority, "anything real must outrank it")
    }

    @Test
    fun `graphics - colours are Android's arithmetic, and a bad one throws as on Android`() {
        assertEquals(0xFF112233.toInt(), Color.parseColor("#112233"))
        assertEquals(0x80112233.toInt(), Color.parseColor("#80112233"))
        assertEquals(0x80112233.toInt(), Color.argb(0x80, 0x11, 0x22, 0x33))
        assertEquals(0xFF112233.toInt(), Color.rgb(0x11, 0x22, 0x33))
        assertFailsWith<IllegalArgumentException> { Color.parseColor("red") }
    }

    @Test
    fun `text - spans are dropped and the text survives`() {
        val text = SpannableStringBuilder("ab").append("cd")
        text.setSpan(Any(), 0, 1, 0)
        assertEquals("abcd", text.toString())
        assertEquals(4, text.length)
        assertEquals("bold plain", Html.fromHtml("<b>bold</b> plain", 0).toString())
    }

    @Test
    fun `webkit - no web view can exist, and cookies hold nothing`() {
        val failure = assertFailsWith<UnsupportedOperationException> { WebView(null) }
        assertTrue("sandbox" in failure.message.orEmpty())
        val cookies = CookieManager.getInstance()
        cookies.setCookie("https://example.invalid", "a=b")
        assertNull(cookies.getCookie("https://example.invalid"))
    }

    @Test
    fun `hierarchy - types are what the verifier expects`() {
        assertTrue(Context::class.java.isAssignableFrom(AppCompatActivity::class.java))
        assertTrue(android.view.View::class.java.isAssignableFrom(android.widget.Switch::class.java))
        assertTrue(android.view.ViewGroup::class.java.isAssignableFrom(android.widget.LinearLayout::class.java))
        assertTrue(android.app.Dialog::class.java.isAssignableFrom(androidx.appcompat.app.AlertDialog::class.java))
        assertTrue(androidx.fragment.app.DialogFragment::class.java.isAssignableFrom(BottomSheetDialogFragment::class.java))
        assertTrue(org.xmlpull.v1.XmlPullParser::class.java.isAssignableFrom(android.content.res.XmlResourceParser::class.java))
        // An interface where Android has an interface, or `invokeinterface` fails to link.
        listOf(
            android.view.View.OnClickListener::class.java,
            android.content.DialogInterface::class.java,
            android.content.DialogInterface.OnClickListener::class.java,
            android.text.TextWatcher::class.java,
            android.text.Editable::class.java,
            android.webkit.ValueCallback::class.java,
            android.util.AttributeSet::class.java,
        ).forEach { assertTrue(it.isInterface, it.name) }
    }

    @Test
    fun `app - there is no activity, and events reach their observers`() {
        assertNull(CommonActivity.activity)
        val event: Event<Boolean> = MainActivity.reloadHomeEvent
        var seen: Boolean? = null
        val observer: (Boolean) -> Unit = { seen = it }
        event += observer
        event(true)
        assertEquals(true, seen)
        event -= observer
        assertEquals(0, event.size)
        assertTrue(Globals.isLayout(Globals.PHONE))
        assertFalse(Globals.isLayout(Globals.TV or Globals.EMULATOR))
    }

    @Test
    fun `data store - values round trip as JSON, inside the given context's directory`() {
        val directory = File.createTempFile("datastore", "").apply { delete(); mkdirs() }
        try {
            val context: Context = HostContext(directory)
            context.setKey("folder/a", listOf("x", "y"))
            context.setKey("folder", "b", "text")
            assertEquals("text", context.getKey("folder/b", String::class.java))
            assertEquals(listOf("x", "y"), context.getKey("folder/a", List::class.java))
            assertEquals(setOf("folder/a", "folder/b"), context.getKeys("folder").toSet())
            assertNull(context.getKey("missing", String::class.java))
            assertNull(context.getKey("folder/b", Int::class.java), "does not parse, so null, as on Android")
            context.removeKey("folder/a")
            assertFalse(context.containsKey("folder/a"))
            // Stored only there: a second context over the same directory reads it back.
            assertEquals("text", HostContext(directory).getKey("folder/b", String::class.java))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cloudflare killer - passes through, with no solver and no cookies`() {
        val request = Request.Builder().url("https://example.invalid/").build()
        val proceeded = AtomicBoolean(false)
        val chain = java.lang.reflect.Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "request" -> request
                "proceed" -> {
                    proceeded.set(true)
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("").build()
                }
                else -> error(method.name)
            }
        } as Interceptor.Chain
        val killer = CloudflareKiller()
        assertEquals(503, killer.intercept(chain).code, "a challenge stays a failure the plugin can see")
        assertTrue(proceeded.get())
        assertEquals(0, killer.getCookieHeaders("https://example.invalid/").size)
    }

    /**
     * The rule: a shim grants no capability. Read from the shims' own constant pools, so a later edit
     * that reaches for a file, a socket, a process or a loader fails here, whatever it was called.
     * `ContextWrapper` and `Context` name `File` and `ClassLoader` because the host's context does.
     */
    @Test
    fun `no shim names a file, a socket, a process, a loader or reflection`() {
        val forbidden = listOf(
            "java/io/File", "java/nio/file/", "java/net/Socket", "java/net/ServerSocket", "java/net/URL",
            "java/net/HttpURLConnection", "java/nio/channels/", "java/lang/Process", "java/lang/Runtime",
            "java/lang/ClassLoader", "java/lang/reflect/", "okhttp3/OkHttp", "okhttp3/Call",
        )
        // Every Kotlin lambda's bootstrap descriptor names `MethodHandles.Lookup`, so calls are what count.
        val forbiddenCalls = listOf("java/lang/invoke/MethodHandles", "java/lang/Class")
        val shims = SHIMS.map { Class.forName(it, false, javaClass.classLoader) }
        shims.forEach { type ->
            val path = type.name.replace('.', '/') + ".class"
            val bytes = type.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }
            val facts = ClassFacts.read(bytes)
            val reached = facts.classes.filter { used -> forbidden.any(used::startsWith) }
            assertTrue(reached.isEmpty(), "${type.name} reaches $reached")
            val called = facts.methods.filter { (owner, _) -> forbiddenCalls.any(owner::startsWith) }
            assertTrue(called.isEmpty(), "${type.name} calls $called")
            assertFalse(facts.declaresNative, type.name)
        }
        assertTrue(SHIMS.size > 60, "the list must keep covering the shims")
    }

    @Test
    fun `nothing grants what the sandbox withholds, which the host's Context shows by what it lacks`() {
        val methods = Context::class.java.methods.map { it.name }.toSet()
        listOf("getPackageManager", "startActivity", "sendBroadcast", "getSystemService", "getContentResolver", "openFileOutput").forEach {
            assertFalse(it in methods, it)
        }
    }

    @Test
    fun `a plugin using every group loads and answers inside the real sandbox`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val root = File.createTempFile("shim-groups", "").apply { delete(); mkdirs() }.canonicalFile
        try {
            val jar = File(root, "store/shim.jar").apply { parentFile.mkdirs() }
            JarOutputStream(jar.outputStream()).use { out ->
                listOf(ShimPlugin::class.java, ShimApi::class.java).forEach { type ->
                    val path = type.name.replace('.', '/') + ".class"
                    out.putNextEntry(JarEntry(path))
                    type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            runBlocking {
                val (host, providers) = PluginHostClient.start(launcher, jar, File(root, "data"), "shim")
                try {
                    providers.getOrElse { throw AssertionError(host.hostOutput, it) }
                    val seen = host.search("Shim", "query", 1).items.single().name
                    assertEquals("true|stored|true|0|UnsupportedOperationException|plugin-main", seen)
                } finally {
                    host.close()
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val SHIMS = listOf(
            "android.os.Build", "android.os.Build\$VERSION", "android.os.Looper", "android.os.Handler", "android.os.SystemClock", "android.os.Bundle",
            "android.graphics.Color", "android.graphics.Typeface", "android.graphics.Paint",
            "android.graphics.drawable.Drawable", "android.graphics.drawable.ColorDrawable", "android.graphics.drawable.ClipDrawable",
            "android.graphics.drawable.LayerDrawable", "android.graphics.drawable.GradientDrawable",
            "android.text.Editable", "android.text.Spanned", "android.text.TextWatcher", "android.text.InputFilter", "android.text.SpannableStringBuilder", "android.text.Html",
            "android.text.method.TransformationMethod", "android.text.style.ForegroundColorSpan", "android.text.style.StyleSpan",
            "android.view.View", "android.view.ViewGroup", "android.view.ViewParent", "android.view.ViewPropertyAnimator", "android.view.ViewTreeObserver",
            "android.view.Window", "android.view.LayoutInflater", "android.view.KeyEvent", "android.view.MotionEvent", "android.view.WindowManager",
            "android.view.animation.DecelerateInterpolator", "android.animation.TimeInterpolator", "android.animation.StateListAnimator", "android.animation.ValueAnimator",
            "android.widget.TextView", "android.widget.EditText", "android.widget.AutoCompleteTextView", "android.widget.Button", "android.widget.CompoundButton",
            "android.widget.CheckBox", "android.widget.Switch", "android.widget.ImageView", "android.widget.ImageButton", "android.widget.ProgressBar",
            "android.widget.LinearLayout", "android.widget.FrameLayout", "android.widget.ScrollView", "android.widget.HorizontalScrollView",
            "android.widget.ListAdapter", "android.widget.SpinnerAdapter", "android.widget.BaseAdapter", "android.widget.ArrayAdapter", "android.widget.AdapterView", "android.widget.Spinner", "android.widget.Toast",
            "android.webkit.WebView", "android.webkit.WebViewClient", "android.webkit.WebChromeClient", "android.webkit.WebSettings", "android.webkit.ValueCallback",
            "android.webkit.CookieManager", "android.webkit.WebStorage", "android.webkit.WebResourceError", "android.webkit.WebResourceRequest",
            "android.app.Activity", "android.app.Application", "android.app.Dialog", "android.app.AlertDialog", "android.app.ActivityManager", "android.app.UiModeManager",
            "android.content.DialogInterface", "android.content.Intent", "android.content.ComponentName", "android.content.pm.PackageManager",
            "android.content.res.ColorStateList", "android.content.res.Configuration", "android.content.res.XmlResourceParser", "android.util.AttributeSet", "android.util.DisplayMetrics",
            "org.xmlpull.v1.XmlPullParser",
            "androidx.fragment.app.Fragment", "androidx.fragment.app.DialogFragment", "androidx.fragment.app.FragmentManager", "androidx.fragment.app.FragmentTransaction", "androidx.fragment.app.FragmentActivity",
            "androidx.appcompat.app.AppCompatActivity", "androidx.appcompat.app.AlertDialog", "androidx.core.content.res.ResourcesCompat",
            "com.google.android.material.bottomsheet.BottomSheetDialogFragment", "com.google.android.material.bottomsheet.BottomSheetDialog", "com.google.android.material.bottomsheet.BottomSheetBehavior",
            "com.lagradost.cloudstream3.network.CloudflareKiller", "com.lagradost.cloudstream3.utils.Event", "com.lagradost.cloudstream3.ui.settings.Globals",
            "com.lagradost.cloudstream3.CloudStreamApp", "com.lagradost.cloudstream3.AcraApplication", "com.lagradost.cloudstream3.CommonActivity", "com.lagradost.cloudstream3.MainActivity",
            "com.lagradost.cloudstream3.syncproviders.AccountManager", "com.lagradost.cloudstream3.syncproviders.AccountManager\$Companion",
            "com.lagradost.cloudstream3.syncproviders.SyncAPI", "com.lagradost.cloudstream3.syncproviders.SyncAPI\$LibraryList",
            "com.lagradost.cloudstream3.syncproviders.SyncAPI\$LibraryMetadata", "com.lagradost.cloudstream3.syncproviders.AuthUser",
            "com.lagradost.cloudstream3.syncproviders.SyncRepo", "com.lagradost.cloudstream3.syncproviders.providers.SimklApi",
            "com.lagradost.cloudstream3.utils.UiText",
        )
    }
}
