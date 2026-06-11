package org.koitharu.kotatsu.home.ui

import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById

data class ContinueReadingProgress(
	val currentChapterLabel: String?,
	val currentChapterPosition: Int?,
	val totalChapters: Int,
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
	val actualTotal = manga.chaptersCount()
	val branchTotal = branchChapters.size
	val total = maxOf(history.chaptersCount, actualTotal, branchTotal)
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
		percent = (history.percent * 100f).toInt().coerceIn(0, 100),
	)
}
