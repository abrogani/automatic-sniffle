package com.abrogani.servetorr.link

import com.flixclusive.model.media.common.tv.Episode

internal object FileSelector {
    private val VIDEO_EXTENSIONS =
        setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts",
            "wmv", "flv", "mpg", "mpeg", "vob", "ogv", "3gp", "divx", "rmvb",
        )

    private val EXTRA_WORDS = Regex("""(?<![a-z0-9])(samples?|trailers?|featurettes?|extras|bonus|behind[\s._-]the[\s._-]scenes|deleted[\s._-]scenes|making[\s._-]of)(?![a-z0-9])""")

    private val WORD_SEPARATOR = Regex("""[\s._-]""")

    private val SXXEYY = Regex("""(?<![a-z0-9])s(\d{1,2})[\s._-]?e(\d{1,3})((?:[\s._-]?-?[\s._-]?e\d{1,3})*)(?!\d)""")
    private val EXTRA_EPISODE = Regex("""e(\d{1,3})""")
    private val NXNN = Regex("""(?<![\dx])(\d{1,2})x(\d{2,3})(?![\dp])""")
    private val SEASON_EPISODE_WORDS = Regex("""season[\s._-]*(\d{1,2})\D{0,20}?episode[\s._-]*(\d{1,3})(?!\d)""")
    private val SEASON_SEGMENT = Regex("""(?<![a-z0-9])(?:season|series|saison|staffel|temporada)[\s._-]*(\d{1,2})(?!\d)|(?<![a-z0-9])s(\d{1,2})(?![\de])""")
    private val EPISODE_IN_NAME =
        listOf(
            Regex("""(?<![a-z0-9])(?:episode|ep)[\s._-]*(\d{1,3})(?!\d)"""),
            Regex("""(?<![a-z0-9])e(\d{1,3})(?!\d)"""),
            Regex("""(?:^|\s)-\s*(\d{1,3})(?=$|[\s._\[(]|v\d)"""),
            Regex("""^(\d{1,3})(?=$|[\s._\-\[(])"""),
        )

    private data class EpisodeInfo(val season: Int?, val episodes: Set<Int>)

    fun select(files: List<TorrentFile>, episode: Episode?): TorrentFile? =
        select(files, episode?.season, episode?.number)

    fun select(files: List<TorrentFile>, season: Int?, episode: Int?): TorrentFile? {
        val videos = files.filter { isVideo(it.path) }
        val candidates = withoutExtras(videos).ifEmpty { videos }
        if (candidates.isEmpty()) return null
        if (season == null || episode == null) return candidates.maxByOrNull { it.length }

        if (candidates.size == 1) {
            val only = candidates.single()
            val info = parseEpisode(only.path) ?: return only
            return only.takeIf { info.season == null || (info.season == season && episode in info.episodes) }
        }

        val scored = candidates.map { it to score(it.path, season, episode) }
        val best = scored.maxOf { it.second }
        if (best == 0) return null
        return scored.filter { it.second == best }.maxByOrNull { it.first.length }?.first
    }

    internal fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private fun withoutExtras(videos: List<TorrentFile>): List<TorrentFile> {
        val wordsByFile = videos.associateWith { extraWords(it.path) }
        val titleWords =
            wordsByFile.values
                .flatten()
                .groupingBy { it }
                .eachCount()
                .filterValues { it * 2 > videos.size }
                .keys
        return videos.filter { (wordsByFile.getValue(it) - titleWords).isEmpty() }
    }

    private fun extraWords(path: String): Set<String> =
        EXTRA_WORDS.findAll(path.lowercase()).map { it.groupValues[1].replace(WORD_SEPARATOR, " ") }.toSet()

    private fun score(path: String, season: Int, episode: Int): Int {
        val info = parseEpisode(path) ?: return 0
        if (episode !in info.episodes) return 0
        return when (info.season) {
            season -> 2
            null -> 1
            else -> 0
        }
    }

    private fun parseEpisode(path: String): EpisodeInfo? {
        val lower = path.lowercase().replace('\\', '/')
        val segments = lower.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull()?.substringBeforeLast('.') ?: return null
        val folders = segments.dropLast(1)

        SXXEYY.find(name)?.let { match ->
            val first = match.groupValues[2].toInt()
            val more = EXTRA_EPISODE.findAll(match.groupValues[3]).map { it.groupValues[1].toInt() }.toList()
            val episodes =
                if (more.size == 1 && more.single() > first) (first..more.single()).toSet() else setOf(first) + more
            return EpisodeInfo(match.groupValues[1].toInt(), episodes)
        }
        NXNN.find(name)?.let { return EpisodeInfo(it.groupValues[1].toInt(), setOf(it.groupValues[2].toInt())) }
        SEASON_EPISODE_WORDS.find(lower)?.let {
            return EpisodeInfo(it.groupValues[1].toInt(), setOf(it.groupValues[2].toInt()))
        }

        val episodeNumber = EPISODE_IN_NAME.firstNotNullOfOrNull { it.find(name)?.groupValues?.get(1)?.toInt() } ?: return null
        val season = (folders.asReversed() + name).firstNotNullOfOrNull(::seasonOf)
            ?: folders.asReversed().firstNotNullOfOrNull { SXXEYY.find(it)?.groupValues?.get(1)?.toInt() }
        return EpisodeInfo(season, setOf(episodeNumber))
    }

    private fun seasonOf(segment: String): Int? =
        SEASON_SEGMENT.find(segment)?.let { match ->
            (match.groups[1] ?: match.groups[2])?.value?.toInt()
        }
}
