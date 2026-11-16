package com.abrogani.flixhq

import com.abrogani.flixhq.dto.ServerDto
import com.abrogani.flixhq.extensions.parseJson
import com.abrogani.flixhq.extensions.toList
import com.abrogani.flixhq.extractors.ByseCaptures
import com.abrogani.flixhq.extractors.StreamExpiry
import com.abrogani.flixhq.extractors.SubtitleResolver
import com.abrogani.flixhq.extractors.VidmolyExtractor
import com.flixclusive.model.provider.link.Flag
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue
import strikt.assertions.startsWith

class FlixHQExtractorTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture: $name" }.readText()

    @Test
    fun `server list parses arrays and single objects`() {
        val array = parseJson(
            """[{"name":"FlixHQ","link":"https://mfw09.org/e/abc?sub.info=https://x/y.json","en_sub":1},{"name":"Videasy","link":"https://v/x"}]""",
        ).toList<ServerDto>()
        val single = parseJson("""{"name":"Vidmoly","link":"https://k/embed-1.html"}""").toList<ServerDto>()

        expectThat(array).hasSize(2)
        expectThat(array.first().enSub).isEqualTo(1)
        expectThat(single.single().name).isEqualTo("Vidmoly")
    }

    @Test
    fun `vidmoly source is read from the player setup`() {
        val url = VidmolyExtractor.parseSource(fixture("vidmoly_embed.html"))

        expectThat(url).isNotNull().startsWith("https://prx-am-s-1.vmpx.online/hls2/")
        expectThat(url!!).contains("master.m3u8")
    }

    @Test
    fun `vidmoly falls back to any playlist url`() {
        val url = VidmolyExtractor.parseSource("""var a = "https://cdn.example/v/index.m3u8?t=1";""")

        expectThat(url).isEqualTo("https://cdn.example/v/index.m3u8?t=1")
        expectThat(VidmolyExtractor.parseSource("nothing here")).isNull()
    }

    @Test
    fun `vidmoly expiry comes from the signed url`() {
        val flag = StreamExpiry.of("https://h/master.m3u8?t=x&s=1794816889&e=43200")

        expectThat(flag).isEqualTo(Flag.Expires((1794816889L + 43200L) * 1000))
        expectThat(StreamExpiry.of("https://h/master.m3u8")).isNull()
    }

    @Test
    fun `subtitle json keeps captions only`() {
        val subtitles = SubtitleResolver.parse(fixture("subtitles.json"))

        expectThat(subtitles).hasSize(12)
        expectThat(subtitles.map { it.language }).contains("English")
        expectThat(subtitles.all { it.url.startsWith("https://qqqcdn.cloud/") }).isTrue()
    }

    @Test
    fun `subtitle labels lose the language code and index`() {
        expectThat(SubtitleResolver.cleanLabel("English - [Eng] 2")).isEqualTo("English")
        expectThat(SubtitleResolver.cleanLabel("English - Sdh [Eng] 3")).isEqualTo("English - Sdh")
        expectThat(SubtitleResolver.cleanLabel("Norwegian Bokmål - [Nob] 11")).isEqualTo("Norwegian Bokmål")
        expectThat(SubtitleResolver.cleanLabel("French")).isEqualTo("French")
    }

    @Test
    fun `duplicate subtitle labels get an index`() {
        val subtitles = SubtitleResolver.parse(fixture("subtitles.json"))
        val spanish = subtitles.map { it.language }.filter { it.startsWith("Spanish") }

        expectThat(spanish).isEqualTo(listOf("Spanish 1", "Spanish 2"))
        expectThat(subtitles.map { it.language }.distinct()).hasSize(subtitles.size)
    }

    @Test
    fun `subtitle json drops thumbnails and relative files`() {
        val subtitles = SubtitleResolver.parse(
            """[{"file":"/api/thumbs","kind":"thumbnails"},{"file":"https://a/b.vtt","label":"French","kind":"captions"},{"file":"https://a/c.vtt","kind":"thumbnails"}]""",
        )

        expectThat(subtitles).hasSize(1)
        expectThat(subtitles.single().language).isEqualTo("French")
    }

    @Test
    fun `byse capture parses the player playlist`() {
        val raw = "\"{\\\"sources\\\":[{\\\"file\\\":\\\"https://cdn.example/a/master.m3u8\\\",\\\"label\\\":\\\"1080p\\\"}]," +
            "\\\"tracks\\\":[{\\\"file\\\":\\\"https://cdn.example/en.vtt\\\",\\\"label\\\":\\\"English\\\",\\\"kind\\\":\\\"captions\\\"}]}\""

        val capture = ByseCaptures.parseCapture(raw)

        expectThat(capture).isNotNull()
        expectThat(capture!!.sources.single().url).isEqualTo("https://cdn.example/a/master.m3u8")
        expectThat(capture.sources.single().label).isEqualTo("1080p")
        expectThat(capture.tracks.single().label).isEqualTo("English")
    }

    @Test
    fun `byse capture ignores empty and invalid playlists`() {
        expectThat(ByseCaptures.parseCapture(null)).isNull()
        expectThat(ByseCaptures.parseCapture("null")).isNull()
        expectThat(ByseCaptures.parseCapture("\"{\\\"sources\\\":[]}\"")).isNull()
        expectThat(ByseCaptures.parseCapture("\"{\\\"sources\\\":[{\\\"file\\\":\\\"/relative.m3u8\\\"}]}\"")).isNull()
    }

    @Test
    fun `byse network capture only keeps media urls and prefers master`() {
        expectThat(ByseCaptures.isMediaUrl("https://c/x/master.m3u8?t=1")).isTrue()
        expectThat(ByseCaptures.isMediaUrl("https://c/x/file.mp4")).isTrue()
        expectThat(ByseCaptures.isMediaUrl("https://c/x/seg-1.ts")).isFalse()
        expectThat(ByseCaptures.isMediaUrl("https://c/x/app.js")).isFalse()

        val capture = ByseCaptures.fromNetwork(listOf("https://c/x/index-v1.m3u8", "https://c/x/master.m3u8"))
        expectThat(capture!!.sources.single().url).isEqualTo("https://c/x/master.m3u8")
        expectThat(ByseCaptures.fromNetwork(emptyList())).isNull()
    }
}
