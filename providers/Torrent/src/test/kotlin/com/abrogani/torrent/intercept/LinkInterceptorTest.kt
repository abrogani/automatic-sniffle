package com.abrogani.torrent.intercept

import android.content.Context
import com.abrogani.torrent.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.MediaLinkProviderApi
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotSameInstanceAs
import strikt.assertions.isNull
import strikt.assertions.isSameInstanceAs
import strikt.assertions.isTrue

private class OtherPlugin : ProviderPlugin() {
    var linkApi: MediaLinkProviderApi? = mockk(relaxed = true)

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi? = linkApi
}

private class SelfPlugin : ProviderPlugin()

/** Loads its own copy of [InterceptingPlugin], like an older version of this provider in another class loader. */
private class OtherVersionClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val target = InterceptingPlugin::class.java.name

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name != target) return super.loadClass(name, resolve)
        synchronized(this) {
            findLoadedClass(name)?.let { return it }
            val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LinkInterceptorTest {
    @get:Rule
    val logRule = LogRule()

    private val rewriter = mockk<TorrentLinkRewriter>()
    private val self = SelfPlugin()
    private val interceptors = mutableListOf<LinkInterceptor>()

    private fun interceptor(owner: ProviderPlugin = self) =
        LinkInterceptor(owner, rewriter, { true }, UnconfinedTestDispatcher()).also { interceptors += it }

    @After
    fun tearDown() = interceptors.forEach { it.uninstall() }

    private fun foreignWrapper(inner: ProviderPlugin): ProviderPlugin {
        val type = OtherVersionClassLoader(javaClass.classLoader!!).loadClass(InterceptingPlugin::class.java.name)
        val constructor = type.declaredConstructors.single { it.parameterCount == 3 }
        constructor.isAccessible = true
        return constructor.newInstance(inner, rewriter, mockk<suspend () -> Boolean>()) as ProviderPlugin
    }

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
    fun `wrapAll returns the same map when everything is already wrapped`() {
        val subject = interceptor()
        val wrapped = subject.wrapAll(mapOf("a" to OtherPlugin(), "self" to self))

        expectThat(subject.wrapAll(wrapped)).isSameInstanceAs(wrapped)
    }

    @Test
    fun `restoreAll puts the originals back`() {
        val a = OtherPlugin()
        val subject = interceptor()

        val restored = subject.restoreAll(subject.wrapAll(mapOf("a" to a, "self" to self)))

        expectThat(restored).isEqualTo(mapOf("a" to a, "self" to self))
    }

    @Test
    fun `install wraps current and later providers and uninstall restores them`() {
        val a = OtherPlugin()
        val b = OtherPlugin()
        val registry = MutableStateFlow<Map<String, ProviderPlugin>>(mapOf("a" to a))
        val subject = interceptor()

        expectThat(subject.install(registry)).isTrue()
        expectThat(subject.install(registry)).isTrue()
        expectThat(subject.isInstalled).isTrue()
        expectThat(registry.value["a"]).isA<InterceptingPlugin>().get { inner }.isSameInstanceAs(a)

        registry.update { it + ("b" to b) + ("self" to self) }
        expectThat(registry.value["b"]).isA<InterceptingPlugin>().get { inner }.isSameInstanceAs(b)
        expectThat(registry.value["self"]).isSameInstanceAs(self)

        registry.update { it - "a" }
        expectThat(registry.value.keys).isEqualTo(setOf("b", "self"))

        subject.uninstall()
        expectThat(subject.isInstalled).isFalse()
        expectThat(registry.value).isEqualTo(mapOf("b" to b, "self" to self))

        registry.update { it + ("c" to OtherPlugin()) }
        expectThat(registry.value["c"]).isA<OtherPlugin>()
    }

    @Test
    fun `install fails cleanly when the registry isn't found`() {
        val subject = LinkInterceptor(self, rewriter, { true }, UnconfinedTestDispatcher()) { null }

        expectThat(subject.install(mockk<Context>(relaxed = true))).isFalse()
        expectThat(subject.isInstalled).isFalse()
    }

    @Test
    fun `retires when a newer copy of the provider replaced it`() {
        val registry = MutableStateFlow<Map<String, ProviderPlugin>>(mapOf("self" to self))
        val subject = interceptor()
        subject.install(registry)

        registry.value = mapOf("self" to SelfPlugin(), "a" to OtherPlugin())

        expectThat(subject.isInstalled).isFalse()
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
    fun `wrapped plugin caches its link api and re-wraps when the inner api changes`() = runTest {
        val a = OtherPlugin()
        val wrapper = InterceptingPlugin(a, rewriter) { true }
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
