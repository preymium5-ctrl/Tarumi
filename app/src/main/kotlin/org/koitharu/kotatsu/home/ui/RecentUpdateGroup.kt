package org.koitharu.kotatsu.home.ui

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

data class RecentUpdateGroup(
	val manga: Manga,
	val chapters: List<MangaChapter>,
	val sourceTitle: String,
	val sortDate: Long,
)

data class WeebCentralFeedItem(
	val title: String,
	val seriesUrl: String,
	val chapterUrl: String,
	val coverUrl: String?,
	val chapterTitle: String,
	val uploadDate: Long,
)

private val recentChapterComparator = compareByDescending<MangaChapter> { it.uploadDate }
	.thenByDescending { it.number }

fun List<RecentUpdateGroup>.rankRecentUpdateGroups(
	limit: Int,
	chaptersPerTitle: Int,
): List<RecentUpdateGroup> {
	return groupBy { it.recentUpdateMangaKey() }
		.values
		.mapNotNull { groups ->
			val newestGroup = groups.maxByOrNull { it.sortDate } ?: return@mapNotNull null
			val chapters = groups
				.flatMap { it.chapters }
				.distinctBy { chapter -> "${chapter.source.name}:${chapter.id}:${chapter.url}" }
				.sortedWith(recentChapterComparator)
				.take(chaptersPerTitle)
			if (chapters.isEmpty()) {
				return@mapNotNull null
			}
			newestGroup.copy(
				manga = newestGroup.manga.copy(chapters = chapters),
				chapters = chapters,
				sortDate = maxOf(newestGroup.sortDate, chapters.maxOf(MangaChapter::uploadDate)),
			)
		}
		.sortedByDescending { it.sortDate }
		.take(limit)
}

fun RecentUpdateGroup.recentUpdateMangaKey(): String {
	return "${manga.source.name}:${manga.id}:${manga.url.ifBlank { manga.publicUrl }}"
}
