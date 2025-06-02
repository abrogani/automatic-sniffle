package com.abrogani.streamplay

import android.content.Context
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.Provider
import com.lagradost.cloudstream3.extractors.DoodYtExtractor
import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.Gofile
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.OkRuHTTP
import com.lagradost.cloudstream3.extractors.OkRuSSL
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamSB8
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.Streamlare
import com.lagradost.cloudstream3.extractors.VidHidePro6
import com.lagradost.cloudstream3.extractors.VidSrcExtractor
import com.lagradost.cloudstream3.extractors.Vidmolyme
import com.lagradost.cloudstream3.extractors.Vidplay
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import com.phisher98.Alions
import com.phisher98.AllinoneDownloader
import com.phisher98.Animefever
import com.phisher98.Animezia
import com.phisher98.Bestx
import com.phisher98.Boosterx
import com.phisher98.Comedyshow
import com.phisher98.Driveleech
import com.phisher98.DriveleechPro
import com.phisher98.Driveseed
import com.phisher98.Embedrise
import com.phisher98.Embedwish
import com.phisher98.Embtaku
import com.phisher98.Filelion
import com.phisher98.Filelions
import com.phisher98.FilemoonNl
import com.phisher98.Flaswish
import com.phisher98.GDFlix
import com.phisher98.GDFlix1
import com.phisher98.GDFlix2
import com.phisher98.GDMirrorbot
import com.phisher98.Graceaddresscommunity
import com.phisher98.HubCloud
import com.phisher98.Kwik
import com.phisher98.M4ufree
import com.phisher98.Maxfinishseveral
import com.phisher98.MegaUp
import com.phisher98.MixDropPs
import com.phisher98.MixDropSi
import com.phisher98.Modflix
import com.phisher98.Moviesapi
import com.phisher98.Multimovies
import com.phisher98.MultimoviesAIO
import com.phisher98.MultimoviesSB
import com.phisher98.Mwish
import com.phisher98.Netembed
import com.phisher98.OwlExtractor
import com.phisher98.Pahe
import com.phisher98.PixelDrain
import com.phisher98.Pixeldra
import com.phisher98.Playm4u
import com.phisher98.Rapidplayers
import com.phisher98.Ridoo
import com.phisher98.Servertwo
import com.phisher98.Sethniceletter
import com.phisher98.Snolaxstream
import com.phisher98.Streamruby
import com.phisher98.Streamvid
import com.phisher98.Tellygossips
import com.phisher98.TravelR
import com.phisher98.Tvlogy
import com.phisher98.Uploadever
import com.phisher98.UqloadsXyz
import com.phisher98.VCloud
import com.phisher98.VCloudGDirect
import com.phisher98.Vectorx
import com.phisher98.Yipsu
import com.phisher98.bulbasaur
import com.phisher98.dlions
import com.phisher98.do0od
import com.phisher98.doodre
import com.phisher98.dwish
import com.phisher98.fastdlserver
import com.phisher98.furher
import okhttp3.OkHttpClient

internal const val STREAM_PLAY = "StreamPlay"

@FlixclusiveProvider
class StreamPlay : Provider() {
    override val name: String get() = STREAM_PLAY

    override fun getApi(
        context: Context,
        client: OkHttpClient,
    ) = StreamPlayApi(
        client = client,
        provider = this,
    )

    fun registerExtractorAPI(extractor: ExtractorApi) {
        extractorApis.add(extractor)
    }

    init {
        registerExtractorAPI(Animefever())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(MultimoviesSB())
        registerExtractorAPI(Yipsu())
        registerExtractorAPI(Mwish())
        registerExtractorAPI(TravelR())
        registerExtractorAPI(Playm4u())
        registerExtractorAPI(Vidplay())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(Kwik())
        registerExtractorAPI(Bestx())
        registerExtractorAPI(VCloudGDirect())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(Snolaxstream())
        registerExtractorAPI(Pixeldra())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Graceaddresscommunity())
        registerExtractorAPI(M4ufree())
        registerExtractorAPI(Streamruby())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Filelion())
        registerExtractorAPI(DoodYtExtractor())
        registerExtractorAPI(dlions())
        registerExtractorAPI(MixDrop())
        registerExtractorAPI(dwish())
        registerExtractorAPI(Embedwish())
        registerExtractorAPI(UqloadsXyz())
        registerExtractorAPI(Uploadever())
        registerExtractorAPI(Netembed())
        registerExtractorAPI(Flaswish())
        registerExtractorAPI(Comedyshow())
        registerExtractorAPI(Ridoo())
        registerExtractorAPI(Streamvid())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(do0od())
        registerExtractorAPI(doodre())
        registerExtractorAPI(Embedrise())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(FilemoonNl())
        registerExtractorAPI(Alions())
        registerExtractorAPI(Vidmolyme())
        registerExtractorAPI(AllinoneDownloader())
        registerExtractorAPI(Tellygossips())
        registerExtractorAPI(Tvlogy())
        registerExtractorAPI(Voe())
        //registerExtractorAPI(Mdrive())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Animezia())
        registerExtractorAPI(Moviesapi())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Modflix())
        registerExtractorAPI(Vectorx())
        registerExtractorAPI(Sethniceletter())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(GDFlix1())
        registerExtractorAPI(GDFlix2())
        registerExtractorAPI(furher())
        registerExtractorAPI(VidSrcExtractor())
        registerExtractorAPI(Servertwo())
        registerExtractorAPI(MultimoviesAIO())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(MixDropSi())
        registerExtractorAPI(MixDropPs())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Streamlare())
        registerExtractorAPI(StreamSB8())
        registerExtractorAPI(StreamSB())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Embtaku())
        registerExtractorAPI(bulbasaur())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(Boosterx())
        registerExtractorAPI(OwlExtractor())
        registerExtractorAPI(Rapidplayers())
        registerExtractorAPI(Maxfinishseveral())
        registerExtractorAPI(Pahe())
        registerExtractorAPI(MegaUp())
        registerExtractorAPI(DriveleechPro())
    }
}