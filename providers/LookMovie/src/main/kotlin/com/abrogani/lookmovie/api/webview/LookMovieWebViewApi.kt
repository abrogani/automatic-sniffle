package com.abrogani.lookmovie.api.webview

import android.content.Context
import com.abrogani.lookmovie.api.LookMovieApi
import com.flixclusive.model.film.Film
import com.flixclusive.model.film.FilmMetadata
import com.flixclusive.model.film.FilmSearchItem
import com.flixclusive.model.film.SearchResponseData
import com.flixclusive.model.provider.ProviderCatalog
import com.flixclusive.provider.ProviderWebViewApi
import com.flixclusive.provider.filter.FilterList
import com.flixclusive.provider.webview.ProviderWebView

class LookMovieWebViewApi(
    private val baseApi: LookMovieApi,
    context: Context
) : ProviderWebViewApi(
    client = baseApi.client,
    provider = baseApi.provider,
    context = context
) {
    override val baseUrl: String = baseApi.baseUrl
    override val testFilm: FilmMetadata = baseApi.testFilm
    override val catalogs: List<ProviderCatalog> = baseApi.catalogs

    override fun getWebView(): ProviderWebView {
        return LookMovieWebView(context, baseApi)
    }

    override suspend fun getMetadata(film: Film): FilmMetadata {
        return baseApi.getMetadata(film)
    }

    override suspend fun search(
        title: String,
        page: Int,
        id: String?,
        imdbId: String?,
        tmdbId: Int?,
        filters: FilterList
    ): SearchResponseData<FilmSearchItem> {
        return baseApi.search(title, page, id, imdbId, tmdbId, filters)
    }

    override suspend fun getCatalogItems(
        catalog: ProviderCatalog,
        page: Int
    ): SearchResponseData<FilmSearchItem> {
        return baseApi.getCatalogItems(catalog, page)
    }
}