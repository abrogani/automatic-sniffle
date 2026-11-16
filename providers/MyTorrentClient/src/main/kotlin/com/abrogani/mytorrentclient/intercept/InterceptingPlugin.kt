package com.abrogani.mytorrentclient.intercept

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import com.abrogani.mytorrentclient.link.TorrentLinkRewriter
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CatalogProviderApi
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import com.flixclusive.provider.capability.TrackerProviderApi

internal open class InterceptingPlugin(
    val inner: ProviderPlugin,
    private val rewriter: TorrentLinkRewriter,
    private val isEnabled: suspend () -> Boolean,
    val owner: Any? = null,
) : ProviderPlugin() {
    @Volatile
    private var cachedLinkApi: InterceptingLinkApi? = null

    init {
        try {
            manifest = inner.manifest
        } catch (_: UninitializedPropertyAccessException) {
        }
        try {
            settings = inner.settings
        } catch (_: UninitializedPropertyAccessException) {
        }
        resources = inner.resources
    }

    override val name: String
        get() = inner.name

    override suspend fun getCatalogApi(context: Context): CatalogProviderApi? = inner.getCatalogApi(context)

    override suspend fun getSearchApi(context: Context): SearchProviderApi? = inner.getSearchApi(context)

    override suspend fun getMetadataApi(context: Context): MediaMetadataProviderApi? = inner.getMetadataApi(context)

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi? = inner.getCrossMatchApi(context)

    override suspend fun getTrackerApi(context: Context): TrackerProviderApi? = inner.getTrackerApi(context)

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi? {
        val api = inner.getMediaLinkApi(context) ?: return null
        cachedLinkApi?.takeIf { it.inner === api }?.let { return it }
        return InterceptingLinkApi(api, rewriter, isEnabled).also { cachedLinkApi = it }
    }

    override suspend fun onUnload(context: Context) = inner.onUnload(context)

    class WithSettings(
        inner: ProviderPlugin,
        rewriter: TorrentLinkRewriter,
        isEnabled: suspend () -> Boolean,
        owner: Any? = null,
    ) : InterceptingPlugin(inner, rewriter, isEnabled, owner) {
        @Composable
        override fun SettingsScreen() {
            inner.SettingsScreen()
        }
    }

    companion object {
        fun create(
            inner: ProviderPlugin,
            rewriter: TorrentLinkRewriter,
            isEnabled: suspend () -> Boolean,
            owner: Any? = null,
        ): InterceptingPlugin =
            if (declaresSettingsScreen(inner.javaClass)) {
                WithSettings(inner, rewriter, isEnabled, owner)
            } else {
                InterceptingPlugin(inner, rewriter, isEnabled, owner)
            }

        fun declaresSettingsScreen(type: Class<*>): Boolean =
            try {
                type.getDeclaredComposableMethod("SettingsScreen")
                true
            } catch (_: Throwable) {
                false
            }
    }
}
