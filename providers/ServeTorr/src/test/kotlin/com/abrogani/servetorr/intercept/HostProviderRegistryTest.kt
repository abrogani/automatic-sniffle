package com.abrogani.servetorr.intercept

import com.flixclusive.provider.ProviderPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isSameInstanceAs

private class FakeDoubleCheck(provider: () -> Any) {
    @Volatile private var provider: (() -> Any)? = provider

    @Volatile private var instance: Any? = UNINITIALIZED

    fun get(): Any {
        if (instance === UNINITIALIZED) {
            instance = provider!!()
            provider = null
        }
        return instance!!
    }

    companion object {
        @JvmField
        val UNINITIALIZED = Any()
    }
}

private class FakeSwitchingProvider(private val factory: () -> Any) {
    var calls = 0

    fun get(): Any {
        calls++
        return factory()
    }
}

private class FakeProviderRepositoryImpl {
    private val pluginsMap = MutableStateFlow<Map<String, ProviderPlugin>>(emptyMap())
    private val metadataMap = MutableStateFlow<Map<String, String>>(emptyMap())

    fun plugins() = pluginsMap
}

private class FakeOtherRepository {
    private val pluginsMap = "not a flow"
}

private class FakeSingletonCImpl(
    @JvmField val providerRepositoryImplProvider: FakeSwitchingProvider,
    @JvmField val provideProviderRepositoryProvider: FakeDoubleCheck,
    @JvmField val bindsUserRepositoryProvider: FakeDoubleCheck,
)

private class FakeAnonymousComponent(
    @JvmField val a: FakeDoubleCheck,
    @JvmField val b: FakeDoubleCheck,
    @JvmField val c: FakeDoubleCheck,
)

private open class FakeHiltApplication(private val component: Any) {
    private val componentManager = FakeComponentManager(component)

    fun componentManager(): FakeComponentManager = componentManager

    fun generatedComponent(): Any = componentManager.generatedComponent()
}

private class FakeFlixclusiveApplication(component: Any) : FakeHiltApplication(component)

private class FakeComponentManager(private val component: Any) {
    fun generatedComponent(): Any = component
}

class HostProviderRegistryTest {
    @Test
    fun `finds the component through the Hilt base class`() {
        val component = Any()

        expectThat(HostProviderRegistry.generatedComponent(FakeFlixclusiveApplication(component)))
            .isSameInstanceAs(component)
    }

    @Test
    fun `no component on a plain application`() {
        expectThat(HostProviderRegistry.generatedComponent(Any())).isNull()
    }

    @Test
    fun `finds pluginsMap through the scoped provider without touching the unscoped factory`() {
        val repository = FakeProviderRepositoryImpl()
        val factory = FakeSwitchingProvider { FakeProviderRepositoryImpl() }
        val component = FakeSingletonCImpl(
            providerRepositoryImplProvider = factory,
            provideProviderRepositoryProvider = FakeDoubleCheck { repository }.also { it.get() },
            bindsUserRepositoryProvider = FakeDoubleCheck { error("must not be instantiated") },
        )

        expectThat(HostProviderRegistry.findPluginsFlowIn(component)).isSameInstanceAs(repository.plugins())
        expectThat(factory.calls).isEqualTo(0)
    }

    @Test
    fun `initialises the name-matched scoped binding when nothing touched it yet`() {
        val repository = FakeProviderRepositoryImpl()
        val component = FakeSingletonCImpl(
            providerRepositoryImplProvider = FakeSwitchingProvider { FakeProviderRepositoryImpl() },
            provideProviderRepositoryProvider = FakeDoubleCheck { repository },
            bindsUserRepositoryProvider = FakeDoubleCheck { error("must not be instantiated") },
        )

        expectThat(HostProviderRegistry.findPluginsFlowIn(component)).isSameInstanceAs(repository.plugins())
    }

    @Test
    fun `falls back to initialised scoped bindings when names don't match`() {
        val repository = FakeProviderRepositoryImpl()
        var uninitialisedTouched = false
        val component = FakeAnonymousComponent(
            a = FakeDoubleCheck { FakeOtherRepository() }.also { it.get() },
            b = FakeDoubleCheck {
                uninitialisedTouched = true
                Any()
            },
            c = FakeDoubleCheck { repository }.also { it.get() },
        )

        expectThat(HostProviderRegistry.findPluginsFlowIn(component)).isSameInstanceAs(repository.plugins())
        expectThat(uninitialisedTouched).isFalse()
    }

    @Test
    fun `returns null when no registry is reachable`() {
        val component = FakeAnonymousComponent(
            a = FakeDoubleCheck { FakeOtherRepository() }.also { it.get() },
            b = FakeDoubleCheck { Any() },
            c = FakeDoubleCheck { "x" }.also { it.get() },
        )

        expectThat(HostProviderRegistry.findPluginsFlowIn(component)).isNull()
    }
}
