package org.koitharu.kotatsu.home.ui

import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById

data class ContinueReadingProgress(
	val currentChapterLabel: String?,
	val currentChapterPosition: Int?,
	val totalChapters: Int,
	val totalChapterLabel: String?,
	val percent: Int,
)

fun MangaWithHistory.continueReadingProgress(): ContinueReadingProgress {
	val chapters = manga.chapters.orEmpty()
	val currentChapter = chapters.findById(history.chapterId)
	val branchChapters = currentChapter?.let { chapter ->
		chapters
			.filter { it.branch == chapter.branch }
			.sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.uploadDate })
	}.orEmpty()
	val currentPosition = currentChapter?.let { chapter ->
		branchChapters.indexOfFirst { it.id == chapter.id }
			.takeIf { it >= 0 }
			?.plus(1)
	}
	val branchTotal = branchChapters.size
	val sourceTotal = branchTotal.takeIf { it > 0 } ?: manga.chaptersCount()
	val latestChapterLabel = (branchChapters.ifEmpty { chapters })
		.maxWithOrNull(compareBy<MangaChapter> { it.number }.thenBy { it.uploadDate })
		?.numberString()
		?.takeIf { it.isNotBlank() }
	val total = sourceTotal.takeIf { it > 0 } ?: history.chaptersCount
	val fallbackPosition = history.percent
		.takeIf { total > 0 && it > 0f }
		?.let { (it * total).toInt().coerceIn(1, total) }
	return ContinueReadingProgress(
		currentChapterLabel = currentChapter?.numberString()
			?.takeIf { it.isNotBlank() }
			?: currentPosition?.toString()
			?: fallbackPosition?.toString(),
		currentChapterPosition = currentPosition ?: fallbackPosition,
		totalChapters = total,
		totalChapterLabel = latestChapterLabel ?: total.takeIf { it > 0 }?.toString(),
		percent = (history.percent * 100f).toInt().coerceIn(0, 100),
	)
}
