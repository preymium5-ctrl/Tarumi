package org.koitharu.kotatsu.home.ui

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource

enum class ComicType(val label: String) {
	MANHUA("Manhua"),
	MANHWA("Manhwa"),
	MANGA("Manga"),
	COMIC("Comic"),
}

fun Manga.detectComicType(): ComicType {
	val sourceType = (source as? MangaParserSource)?.contentType
	val labels = buildList {
		for (tag in tags) {
			add(tag.title)
			add(tag.key)
		}
		if (source != MangaParserSource.WEEBCENTRAL) {
			add(source.name)
		}
		add(title)
		for (chapter in chapters.orEmpty()) {
			chapter.title?.let(::add)
		}
		val cleanedDesc = description?.let { desc ->
			if (source == MangaParserSource.DEMONICSCANS) {
				val marker = Regex("""\bThe\s+Summary\s+is\b[:\s]*""", RegexOption.IGNORE_CASE).find(desc)
				if (marker != null) {
					desc.substring(marker.range.last + 1).trim()
				} else {
					desc
				}
			} else {
				desc
			}
		}
		cleanedDesc?.let(::add)
	}.map { it.lowercase() }

	val hasManhuaLabel = labels.any {
		(it.contains("manhua") && !it.contains("manhuafast")) || it.contains("manhuaga") || it.contains("manhuaus")
	}
	val hasManhwaLabel = labels.any {
		it.contains("manhwa") ||
			it.contains("webtoon") ||
			it.contains("asura") ||
			it.contains("stonescape") ||
			it.contains("manhuafast")
	}
	val hasMangaLabel = labels.any {
		it.contains("manga") ||
			it.contains("shounen") ||
			it.contains("seinen") ||
			it.contains("shoujo") ||
			it.contains("mangaplus") ||
			it.contains("mangafire") ||
			it.contains("ninemanga") ||
			it.contains("aquamanga")
	}

	return when {
		hasManhuaLabel -> ComicType.MANHUA
		hasManhwaLabel -> ComicType.MANHWA
		hasMangaLabel -> ComicType.MANGA
		source != MangaParserSource.WEEBCENTRAL && sourceType == ContentType.MANHUA -> ComicType.MANHUA
		source != MangaParserSource.WEEBCENTRAL && sourceType == ContentType.MANHWA -> ComicType.MANHWA
		source != MangaParserSource.WEEBCENTRAL &&
			(sourceType == ContentType.MANGA || sourceType == ContentType.ONE_SHOT || sourceType == ContentType.DOUJINSHI) -> ComicType.MANGA
		else -> ComicType.COMIC
	}
}
