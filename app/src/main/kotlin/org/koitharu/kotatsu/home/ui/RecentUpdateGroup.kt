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
