package com.abrogani.mytorrentclient.net

import com.flixclusive.core.util.log.LogRule
import okhttp3.internal.platform.Platform
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isFalse
import strikt.assertions.isNotSameInstanceAs
import strikt.assertions.isSameInstanceAs
import strikt.assertions.isTrue

private class OtherVersionClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val target = LoopbackPlatform::class.java.name

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name != target) return super.loadClass(name, resolve)
        synchronized(this) {
            findLoadedClass(name)?.let { return it }
            val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}

class LoopbackCleartextTest {
    @get:Rule
    val logRule = LogRule()

    private lateinit var original: Platform

    @Before
    fun setUp() {
        original = Platform.get()
    }

    @After
    fun tearDown() {
        LoopbackCleartext.uninstall()
        Platform.resetForTests(original)
    }

    private fun foreignWrapper(delegate: Platform): Platform {
        val type = OtherVersionClassLoader(javaClass.classLoader!!).loadClass(LoopbackPlatform::class.java.name)
        val constructor = type.getDeclaredConstructor(Platform::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(delegate) as Platform
    }

    @Test
    fun `isLoopback accepts only literal loopback addresses`() {
        listOf("localhost", "LOCALHOST", "::1", "[::1]", "127.0.0.1", "127.1.2.3", "127.255.255.255")
            .forEach { expectThat(LoopbackCleartext.isLoopback(it)).describedAs(it).isTrue() }
        listOf(
            "127.evil.com", "127.0.0.1.evil.com", "127.0.0", "127.0.0.256", "127.0.0.1a", "127..0.1",
            "128.0.0.1", "10.0.0.1", "localhost.evil.com", "::2", "0.0.0.0", "", "127.0.0.0001",
        ).forEach { expectThat(LoopbackCleartext.isLoopback(it)).describedAs(it).isFalse() }
    }

    @Test
    fun `install wraps the platform and uninstall restores it`() {
        LoopbackCleartext.install()
        val installed = Platform.get()
        expectThat(installed).isA<LoopbackPlatform>().get { delegate }.isSameInstanceAs(original)

        LoopbackCleartext.install()
        expectThat(Platform.get()).isSameInstanceAs(installed)

        LoopbackCleartext.uninstall()
        expectThat(Platform.get()).isSameInstanceAs(original)
    }

    @Test
    fun `replaces another version's wrapper instead of stacking on it`() {
        val stale = foreignWrapper(original)
        expectThat(stale.javaClass).isNotSameInstanceAs(LoopbackPlatform::class.java)
        expectThat(LoopbackCleartext.isLoopbackWrapper(stale)).isTrue()
        Platform.resetForTests(stale)

        LoopbackCleartext.install()

        expectThat(Platform.get()).isA<LoopbackPlatform>().get { delegate }.isSameInstanceAs(original)
        LoopbackCleartext.uninstall()
        expectThat(Platform.get()).isSameInstanceAs(original)
    }

    @Test
    fun `uninstall leaves a platform someone installed over ours alone`() {
        LoopbackCleartext.install()
        val later = foreignWrapper(Platform.get())
        Platform.resetForTests(later)

        LoopbackCleartext.uninstall()

        expectThat(Platform.get()).isSameInstanceAs(later)
    }
}
