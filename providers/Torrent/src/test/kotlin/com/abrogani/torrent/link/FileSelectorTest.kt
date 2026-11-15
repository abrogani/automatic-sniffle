package com.abrogani.torrent.link

import com.flixclusive.model.media.common.tv.Episode
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

class FileSelectorTest {
    private fun files(vararg entries: Pair<String, Long>) =
        entries.mapIndexed { i, (path, length) -> TorrentFile(id = i + 1, path = path, length = length) }

    private fun episode(season: Int, number: Int) =
        Episode(id = "s${season}e$number", number = number, season = season, isReleased = true)

    private fun List<TorrentFile>.select(episode: Episode?) = FileSelector.select(this, episode)?.path

    @Test
    fun `movie picks the largest video and skips samples and non-video`() {
        val torrent =
            files(
                "Dune.Part.Two.2024.2160p.WEB-DL/Dune.Part.Two.2024.2160p.WEB-DL.mkv" to 20_000_000_000,
                "Dune.Part.Two.2024.2160p.WEB-DL/Sample/dune.sample.mkv" to 50_000_000,
                "Dune.Part.Two.2024.2160p.WEB-DL/dune.nfo" to 30_000_000_000,
                "Dune.Part.Two.2024.2160p.WEB-DL/Subs/English.srt" to 100_000,
            )

        expectThat(torrent.select(null)).isEqualTo("Dune.Part.Two.2024.2160p.WEB-DL/Dune.Part.Two.2024.2160p.WEB-DL.mkv")
    }

    @Test
    fun `extras are excluded even when larger`() {
        val torrent =
            files(
                "Movie (2010)/Movie (2010).mp4" to 2_000_000_000,
                "Movie (2010)/Extras/Behind the Scenes.mp4" to 3_000_000_000,
                "Movie (2010)/Movie-trailer.mp4" to 4_000_000_000,
                "Movie (2010)/Featurettes/Making.mp4" to 5_000_000_000,
            )

        expectThat(torrent.select(null)).isEqualTo("Movie (2010)/Movie (2010).mp4")
    }

    @Test
    fun `a word shared by most files is part of the title, not an extras marker`() {
        val torrent =
            files(
                "Trailer Park Boys S01/Trailer.Park.Boys.S01E01.mkv" to 400,
                "Trailer Park Boys S01/Trailer.Park.Boys.S01E02.mkv" to 500,
                "Trailer Park Boys S01/Trailer.Park.Boys.S01E03.mkv" to 450,
            )

        expectThat(torrent.select(episode(1, 2))).isEqualTo("Trailer Park Boys S01/Trailer.Park.Boys.S01E02.mkv")
    }

    @Test
    fun `no video at all returns null`() {
        expectThat(files("a.txt" to 1, "b.srt" to 2).select(null)).isNull()
        expectThat(emptyList<TorrentFile>().select(episode(1, 1))).isNull()
    }

    @Test
    fun `SxxEyy in a season pack`() {
        val torrent =
            files(
                "Breaking.Bad.S02.1080p.BluRay.x264/Breaking.Bad.S02E01.1080p.BluRay.x264.mkv" to 1_000,
                "Breaking.Bad.S02.1080p.BluRay.x264/Breaking.Bad.S02E02.1080p.BluRay.x264.mkv" to 1_100,
                "Breaking.Bad.S02.1080p.BluRay.x264/Breaking.Bad.S02E10.1080p.BluRay.x264.mkv" to 1_200,
            )

        expectThat(torrent.select(episode(2, 2))).isEqualTo("Breaking.Bad.S02.1080p.BluRay.x264/Breaking.Bad.S02E02.1080p.BluRay.x264.mkv")
        expectThat(torrent.select(episode(2, 10))).isEqualTo("Breaking.Bad.S02.1080p.BluRay.x264/Breaking.Bad.S02E10.1080p.BluRay.x264.mkv")
    }

    @Test
    fun `multi-season pack matches the right season`() {
        val torrent =
            files(
                "The Office (US) Complete/Season 1/The Office S01E02 Diversity Day.mkv" to 300,
                "The Office (US) Complete/Season 2/The Office S02E02 Sexual Harassment.mkv" to 200,
                "The Office (US) Complete/Season 3/The Office S03E02 The Convention.mkv" to 250,
            )

        expectThat(torrent.select(episode(2, 2))).isEqualTo("The Office (US) Complete/Season 2/The Office S02E02 Sexual Harassment.mkv")
        expectThat(torrent.select(episode(4, 2))).isNull()
    }

    @Test
    fun `season folder plus episode-only names`() {
        val torrent =
            files(
                "Show/Season 01/Episode 1.mkv" to 10,
                "Show/Season 01/Episode 2.mkv" to 10,
                "Show/Season 02/Episode 1.mkv" to 10,
                "Show/Season 02/Episode 2.mkv" to 10,
            )

        expectThat(torrent.select(episode(2, 1))).isEqualTo("Show/Season 02/Episode 1.mkv")
    }

    @Test
    fun `S folder with E and Ep markers and leading numbers`() {
        val e = files("Show/S03/Show E05.mp4" to 1, "Show/S03/Show E06.mp4" to 1)
        expectThat(e.select(episode(3, 6))).isEqualTo("Show/S03/Show E06.mp4")

        val ep = files("Show/Series 2/Show Ep.7.avi" to 1, "Show/Series 2/Show Ep.8.avi" to 1)
        expectThat(ep.select(episode(2, 7))).isEqualTo("Show/Series 2/Show Ep.7.avi")

        val leading = files("Show/Season 1/01 - Pilot.mkv" to 1, "Show/Season 1/02 - Cat's in the Bag.mkv" to 1)
        expectThat(leading.select(episode(1, 2))).isEqualTo("Show/Season 1/02 - Cat's in the Bag.mkv")
    }

    @Test
    fun `sxe notation without confusing resolutions`() {
        val torrent =
            files(
                "Friends 1x01 The One Where Monica Gets a Roommate 1920x1080.mkv" to 1,
                "Friends 1x02 The One with the Sonogram 1920x1080.mkv" to 1,
                "Friends 2x02 The One with the Breast Milk 1920x1080.mkv" to 1,
            )

        expectThat(torrent.select(episode(1, 2))).isEqualTo("Friends 1x02 The One with the Sonogram 1920x1080.mkv")
    }

    @Test
    fun `season and episode spelled out`() {
        val torrent =
            files(
                "Planet Earth/Planet Earth Season 1 Episode 1 - From Pole to Pole.mkv" to 1,
                "Planet Earth/Planet Earth Season 1 Episode 2 - Mountains.mkv" to 1,
            )

        expectThat(torrent.select(episode(1, 2))).isEqualTo("Planet Earth/Planet Earth Season 1 Episode 2 - Mountains.mkv")
    }

    @Test
    fun `anime dash numbering without a season`() {
        val torrent =
            files(
                "[SubsPlease] Frieren (01-12) [1080p]/[SubsPlease] Sousou no Frieren - 01 (1080p) [ABCD].mkv" to 1,
                "[SubsPlease] Frieren (01-12) [1080p]/[SubsPlease] Sousou no Frieren - 02 (1080p) [EF01].mkv" to 1,
                "[SubsPlease] Frieren (01-12) [1080p]/[SubsPlease] Sousou no Frieren - 12v2 (1080p) [EF01].mkv" to 1,
            )

        expectThat(torrent.select(episode(1, 2))).isEqualTo("[SubsPlease] Frieren (01-12) [1080p]/[SubsPlease] Sousou no Frieren - 02 (1080p) [EF01].mkv")
        expectThat(torrent.select(episode(1, 12))).isEqualTo("[SubsPlease] Frieren (01-12) [1080p]/[SubsPlease] Sousou no Frieren - 12v2 (1080p) [EF01].mkv")
    }

    @Test
    fun `exact season match beats an episode-only match`() {
        val torrent =
            files(
                "Pack/Show - 03.mkv" to 9_000,
                "Pack/Show.S02E03.mkv" to 1_000,
            )

        expectThat(torrent.select(episode(2, 3))).isEqualTo("Pack/Show.S02E03.mkv")
    }

    @Test
    fun `duplicates of an episode prefer the largest`() {
        val torrent =
            files(
                "Show.S01E01.720p.mkv" to 700,
                "Show.S01E01.1080p.mkv" to 1_500,
                "Show.S01E02.1080p.mkv" to 1_600,
            )

        expectThat(torrent.select(episode(1, 1))).isEqualTo("Show.S01E01.1080p.mkv")
    }

    @Test
    fun `multi-episode files cover their range`() {
        val torrent = files("Show.S01E01E02.mkv" to 1, "Show.S01E03-E05.mkv" to 1, "Show.S01E06.mkv" to 1)

        expectThat(torrent.select(episode(1, 2))).isEqualTo("Show.S01E01E02.mkv")
        expectThat(torrent.select(episode(1, 4))).isEqualTo("Show.S01E03-E05.mkv")
    }

    @Test
    fun `single video is returned even when the episode does not match`() {
        val torrent = files("Show.S05E09.mkv" to 1, "Sample/show.sample.mkv" to 1, "info.nfo" to 1)

        expectThat(torrent.select(episode(1, 1))).isEqualTo("Show.S05E09.mkv")
    }

    @Test
    fun `episode requested on a multi-video torrent with no match returns null`() {
        val torrent = files("Movie.Part.1.mkv" to 10, "Movie.Part.2.mkv" to 20)

        expectThat(torrent.select(episode(1, 5))).isNull()
    }

    @Test
    fun `release years and codecs are not read as episodes`() {
        val torrent =
            files(
                "Show.2019.S01.1080p.x264/Show.2019.S01E04.1080p.x264.mkv" to 1,
                "Show.2019.S01.1080p.x264/Show.2019.S01E05.1080p.x264.mkv" to 1,
            )

        expectThat(torrent.select(episode(1, 5))).isEqualTo("Show.2019.S01.1080p.x264/Show.2019.S01E05.1080p.x264.mkv")
        expectThat(torrent.select(episode(1, 19))).isNull()
    }
}
