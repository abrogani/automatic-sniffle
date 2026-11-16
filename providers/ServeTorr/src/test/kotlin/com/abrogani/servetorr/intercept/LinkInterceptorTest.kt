package com.abrogani.servetorr.intercept

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import com.abrogani.servetorr.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.MediaLinkProviderApi
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.all
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isLessThanOrEqualTo
import strikt.assertions.isNotSameInstanceAs
import strikt.assertions.isNull
import strikt.assertions.isSameInstanceAs
import strikt.assertions.isTrue

private class OtherPlugin : ProviderPlugin() {
    var linkApi: MediaLinkProviderApi? = mockk(relaxed = true)

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi? = linkApi
}

private class PluginWithSettings : ProviderPlugin() {
    @Composable
    override fun SettingsScreen() {
    }
}

private class SelfPlugin : ProviderPlugin()

private typealias Registry = MutableStateFlow<Map<String, ProviderPlugin>>

private class CopyClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val prefixes = listOf(LinkInterceptor::class, InterceptingPlugin::class, InterceptingLinkApi::class)
        .map { it.java.name }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (prefixes.none { name == it || name.startsWith("$it$") }) return super.loadClass(name, resolve)
        synchronized(this) {
            findLoadedClass(name)?.let { return it }
            val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}

private class TorrentCopy(rewriter: TorrentLinkRewriter, dispatcher: CoroutineDispatcher) {
    val loader = CopyClassLoader(LinkInterceptorTest::class.java.classLoader!!)
    val plugin = SelfPlugin()
    private val type = loader.loadClass(LinkInterceptor::class.java.name)
    private val interceptor: Any = type.declaredConstructors.single { it.parameterCount == 5 }.let {
        it.isAccessible = true
        it.newInstance(plugin, rewriter, { _: Any? -> true }, dispatcher, { _: Context -> null })
    }

    val isInstalled: Boolean get() = type.getMethod("isInstalled").invoke(interceptor) as Boolean

    fun install(registry: Registry): Boolean =
        type.methods.single { it.name.startsWith("install") && it.parameterTypes.contentEquals(arrayOf(MutableStateFlow::class.java)) }
            .invoke(interceptor, registry) as Boolean

    fun uninstall() {
        type.getMethod("uninstall").invoke(interceptor)
    }

    fun owns(plugin: ProviderPlugin?): Boolean = plugin != null && plugin.javaClass.classLoader === loader
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class CountingRegistry(
    private val delegate: Registry = MutableStateFlow(emptyMap()),
) : Registry by delegate {
    var writes = 0

    override fun compareAndSet(expect: Map<String, ProviderPlugin>, update: Map<String, ProviderPlugin>): Boolean {
        check(writes < MAX_WRITES) { "Registry ping-pong: more than $MAX_WRITES writes" }
        return delegate.compareAndSet(expect, update).also { if (it && expect != update) writes++ }
    }

    companion object {
        const val MAX_WRITES = 50
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LinkInterceptorTest {
    @get:Rule
    val logRule = LogRule()

    private val rewriter = mockk<TorrentLinkRewriter>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val self = SelfPlugin()
    private val interceptors = mutableListOf<LinkInterceptor>()
    private val copies = mutableListOf<TorrentCopy>()

    private fun interceptor(owner: ProviderPlugin = self) =
        LinkInterceptor(owner, rewriter, { true }, dispatcher).also { interceptors += it }

    private fun copy() = TorrentCopy(rewriter, dispatcher).also { copies += it }

    @After
    fun tearDown() {
        interceptors.forEach { it.uninstall() }
        copies.forEach { it.uninstall() }
    }

    private fun foreignWrapper(inner: ProviderPlugin): ProviderPlugin {
        val type = CopyClassLoader(javaClass.classLoader!!).loadClass(InterceptingPlugin::class.java.name)
        val constructor = type.declaredConstructors.single { it.parameterCount == 4 }
        constructor.isAccessible = true
        return constructor.newInstance(inner, rewriter, mockk<suspend () -> Boolean>(), null) as ProviderPlugin
    }

    private fun Registry.register(id: String, plugin: ProviderPlugin) = update { it + (id to plugin) }

    @Test
    fun `wrapAll wraps other providers but never itself or another copy of itself`() {
        val a = OtherPlugin()
        val copy = SelfPlugin()
        val map = mapOf("a" to a, "self" to self, "copy" to copy)

        val wrapped = interceptor().wrapAll(map)

        expectThat(wrapped["a"]).isA<InterceptingPlugin>().get { inner }.isSameInstanceAs(a)
        expectThat(wrapped["self"]).isSameInstanceAs(self)
        expectThat(wrapped["copy"]).isSameInstanceAs(copy)
    }

    @Test
    fun `wrapAll leaves the map alone while self isn't registered`() {
        val map = mapOf("a" to OtherPlugin())

        expectThat(interceptor().wrapAll(map)).isSameInstanceAs(map)
    }

    @Test
    fun `wrapAll returns the same map when everything is already wrapped`() {
        val subject = interceptor()
        val wrapped = subject.wrapAll(mapOf("a" to OtherPlugin(), "self" to self))

        expectThat(subject.wrapAll(wrapped)).isSameInstanceAs(wrapped)
    }

    @Test
    fun `restoreAll puts back only its own wrappers`() {
        val a = OtherPlugin()
        val b = OtherPlugin()
        val subject = interceptor()
        val notOurs = InterceptingPlugin(b, rewriter, { true }, owner = Any())

        val restored = subject.restoreAll(subject.wrapAll(mapOf("a" to a, "self" to self)) + ("b" to notOurs))

        expectThat(restored).isEqualTo(mapOf("a" to a, "self" to self, "b" to notOurs))
    }

    @Test
    fun `install waits for self, wraps current and later providers, and uninstall restores them`() {
        val a = OtherPlugin()
        val b = OtherPlugin()
        val registry: Registry = MutableStateFlow(mapOf("a" to a))
        val subject = interceptor()

        expectThat(subject.install(registry)).isTrue()
        expectThat(subject.install(registry)).isTrue()
        expectThat(subject.isInstalled).isTrue()
        expectThat(registry.value["a"]).isSameInstanceAs(a)

        registry.register("self", self)
        expectThat(registry.value["a"]).isA<InterceptingPlugin>().get { inner }.isSameInstanceAs(a)

        registry.register("b", b)
        expectThat(registry.value["b"]).isA<InterceptingPlugin>().get { inner }.isSameInstanceAs(b)
        expectThat(registry.value["self"]).isSameInstanceAs(self)

        registry.update { it - "a" }
        expectThat(registry.value.keys).isEqualTo(setOf("self", "b"))

        subject.uninstall()
        expectThat(subject.isInstalled).isFalse()
        expectThat(registry.value).isEqualTo(mapOf("self" to self, "b" to b))

        registry.register("c", OtherPlugin())
        expectThat(registry.value["c"]).isA<OtherPlugin>()
    }

    @Test
    fun `install fails cleanly when the registry isn't found`() {
        val subject = LinkInterceptor(self, rewriter, { true }, dispatcher) { null }

        expectThat(subject.install(mockk<Context>(relaxed = true))).isFalse()
        expectThat(subject.isInstalled).isFalse()
    }

    @Test
    fun `uninstall before install is authoritative`() {
        val a = OtherPlugin()
        val registry: Registry = MutableStateFlow(mapOf("a" to a, "self" to self))
        val subject = interceptor()

        subject.uninstall()

        expectThat(subject.install(registry)).isFalse()
        expectThat(subject.install(mockk<Context>(relaxed = true))).isFalse()
        expectThat(subject.isInstalled).isFalse()
        expectThat(registry.value["a"]).isSameInstanceAs(a)
    }

    @Test
    fun `an unregistered copy never touches the registry and never retires`() {
        val a = OtherPlugin()
        val registry = CountingRegistry(MutableStateFlow(mapOf("a" to a)))
        val subject = interceptor()

        subject.install(registry)
        registry.register("b", OtherPlugin())
        registry.update { it - "b" }

        expectThat(subject.isInstalled).isTrue()
        expectThat(registry.value["a"]).isSameInstanceAs(a)
        expectThat(registry.writes).isEqualTo(2)
    }

    @Test
    fun `retires and restores once self is unregistered`() {
        val a = OtherPlugin()
        val registry: Registry = MutableStateFlow(mapOf("a" to a, "self" to self))
        val subject = interceptor()
        subject.install(registry)
        expectThat(registry.value["a"]).isA<InterceptingPlugin>()

        registry.update { it - "self" }

        expectThat(subject.isInstalled).isFalse()
        expectThat(registry.value).isEqualTo(mapOf("a" to a))
        registry.register("self", self)
        expectThat(registry.value["a"]).isSameInstanceAs(a)
    }

    @Test
    fun `same-id update leaves exactly one active interceptor whose wrappers belong to the new copy`() {
        val a = OtherPlugin()
        val b = PluginWithSettings()
        val registry = CountingRegistry()
        val old = copy()
        old.install(registry)
        registry.register("a", a)
        registry.register("torrent", old.plugin)
        registry.register("b", b)
        expectThat(listOf(registry.value["a"], registry.value["b"])).all { get { old.owns(this) }.isTrue() }

        val new = copy()
        new.install(registry)
        expectThat(old.owns(registry.value["a"])).isTrue()
        registry.register("torrent", new.plugin)

        expectThat(old.isInstalled).isFalse()
        expectThat(new.isInstalled).isTrue()
        expectThat(registry.value["torrent"]).isSameInstanceAs(new.plugin)
        for ((key, inner) in listOf("a" to a, "b" to b)) {
            val wrapper = registry.value[key]!!
            expectThat(new.owns(wrapper)).isTrue()
            expectThat(wrapper.javaClass.getMethod("getInner").invoke(wrapper)).isSameInstanceAs(inner)
        }
        expectThat(registry.writes).isLessThanOrEqualTo(10)

        registry.register("c", OtherPlugin())
        expectThat(new.owns(registry.value["c"])).isTrue()

        new.uninstall()
        expectThat(registry.value).isEqualTo(mapOf("a" to a, "torrent" to new.plugin, "b" to b, "c" to registry.value["c"]!!))
        expectThat(registry.value["c"]).isA<OtherPlugin>()
    }

    @Test
    fun `profile switch to a profile with its own Torrent hands over without ping-pong`() {
        val registry = CountingRegistry()
        val old = copy()
        old.install(registry)
        registry.value = mapOf("torrent" to old.plugin, "a" to OtherPlugin())

        registry.value = emptyMap()
        expectThat(old.isInstalled).isFalse()

        val a2 = OtherPlugin()
        val new = copy()
        new.install(registry)
        registry.register("a", a2)
        expectThat(registry.value["a"]).isSameInstanceAs(a2)
        registry.register("torrent", new.plugin)

        expectThat(new.owns(registry.value["a"])).isTrue()
        expectThat(old.isInstalled).isFalse()
        expectThat(registry.writes).isLessThanOrEqualTo(10)
    }

    @Test
    fun `profile switch to a profile without Torrent leaves its providers unwrapped`() {
        val registry = CountingRegistry()
        val old = copy()
        old.install(registry)
        registry.value = mapOf("torrent" to old.plugin, "a" to OtherPlugin())
        expectThat(old.owns(registry.value["a"])).isTrue()

        registry.value = emptyMap()
        val a2 = OtherPlugin()
        registry.register("a", a2)

        expectThat(old.isInstalled).isFalse()
        expectThat(registry.value).isEqualTo(mapOf("a" to a2))
    }

    @Test
    fun `adopts wrappers left by another version once it is the only live copy`() {
        val a = OtherPlugin()
        val stale = foreignWrapper(a)
        expectThat(stale.javaClass).isNotSameInstanceAs(InterceptingPlugin::class.java)

        val wrapped = interceptor().wrapAll(mapOf("a" to stale, "self" to self))

        expectThat(wrapped["a"]).isA<InterceptingPlugin>().get { inner }.isSameInstanceAs(a)
    }

    @Test
    fun `leaves another live copy's wrappers alone`() {
        val stale = foreignWrapper(OtherPlugin())
        val map = mapOf("a" to stale, "self" to self, "other-copy" to SelfPlugin())

        expectThat(interceptor().wrapAll(map)).isSameInstanceAs(map)
    }

    @Test
    fun `two live copies under different ids don't fight`() {
        val registry = CountingRegistry()
        val first = copy()
        val second = copy()
        first.install(registry)
        second.install(registry)

        registry.value = mapOf("torrent" to first.plugin, "a" to OtherPlugin(), "torrent-debug" to second.plugin)
        registry.register("b", OtherPlugin())

        expectThat(first.isInstalled).isTrue()
        expectThat(second.isInstalled).isTrue()
        expectThat(registry.writes).isLessThanOrEqualTo(10)
    }

    @Test
    fun `wrapper declares SettingsScreen only when the wrapped provider does`() {
        val plain = InterceptingPlugin.create(OtherPlugin(), rewriter, { true })
        val withSettings = InterceptingPlugin.create(PluginWithSettings(), rewriter, { true })

        expectThrows<NoSuchMethodException> { plain.javaClass.getDeclaredComposableMethod("SettingsScreen") }
        expectThat(withSettings.javaClass.getDeclaredComposableMethod("SettingsScreen").asMethod().declaringClass)
            .isSameInstanceAs(InterceptingPlugin.WithSettings::class.java)
        expectThat(LinkInterceptor.isForeignWrapper(withSettings)).isTrue()
        expectThat(LinkInterceptor.isForeignWrapper(plain)).isTrue()
        expectThat(LinkInterceptor.isForeignWrapper(OtherPlugin())).isFalse()
    }

    @Test
    fun `wrapped plugin caches its link api and re-wraps when the inner api changes`() = runTest {
        val a = OtherPlugin()
        val wrapper = InterceptingPlugin(a, rewriter, { true })
        val context = mockk<Context>(relaxed = true)

        val first = wrapper.getMediaLinkApi(context)
        expectThat(first).isA<InterceptingLinkApi>().get { inner }.isSameInstanceAs(a.linkApi)
        expectThat(wrapper.getMediaLinkApi(context)).isSameInstanceAs(first)

        a.linkApi = mockk(relaxed = true)
        expectThat(wrapper.getMediaLinkApi(context)).isNotSameInstanceAs(first)

        a.linkApi = null
        expectThat(wrapper.getMediaLinkApi(context)).isNull()
    }
}
