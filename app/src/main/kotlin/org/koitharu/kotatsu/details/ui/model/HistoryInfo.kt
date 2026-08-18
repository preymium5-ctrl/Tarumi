package org.koitharu.kotatsu.details.ui.model

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReadingTime
import org.koitharu.kotatsu.parsers.util.findById

data class HistoryInfo(
	val totalChapters: Int,
	val currentChapter: Int,
	val history: MangaHistory?,
	val isIncognitoMode: Boolean,
	val isChapterMissing: Boolean,
	val canDownload: Boolean,
	val estimatedTime: ReadingTime?,
) {
	val isValid: Boolean
		get() = totalChapters >= 0

	val canContinue
		get() = currentChapter >= 0

	/** Progress within the current chapter (pages), not across the whole series. */
	val percent: Float
		get() = if (history != null && (canContinue || isChapterMissing)) {
			history.percent.coerceIn(0f, 1f)
		} else {
			0f
		}
}

fun HistoryInfo(
	manga: MangaDetails?,
	branch: String?,
	history: MangaHistory?,
	isIncognitoMode: Boolean,
	estimatedTime: ReadingTime?,
): HistoryInfo {
	if (manga == null) {
		return HistoryInfo(
			totalChapters = -1,
			currentChapter = -2,
			history = history,
			isIncognitoMode = isIncognitoMode,
			isChapterMissing = false,
			canDownload = false,
			estimatedTime = estimatedTime,
		)
	}

	// When no branch is selected, resolve the preferred scanlation so Start/Continue stays enabled
	// and progress maps to the correct chapter list (named branches never live under map[null]).
	val mangaObj = manga.toManga()
	val resolvedBranch = when {
		branch != null && manga.chapters.containsKey(branch) -> branch
		history != null -> manga.allChapters.findById(history.chapterId)?.branch
			?: mangaObj.getPreferredBranch(history)
		else -> mangaObj.getPreferredBranch(null)
	}

	val chapters = when {
		manga.chapters.isEmpty() -> emptyList()
		resolvedBranch != null -> manga.chapters[resolvedBranch] ?: manga.allChapters
		// All chapters have null branch, or mixed with no preferred match
		manga.chapters.containsKey(null) -> manga.chapters[null].orEmpty().ifEmpty { manga.allChapters }
		else -> manga.allChapters
	}

	val currentChapter = if (history != null && chapters.isNotEmpty()) {
		chapters.indexOfFirst { it.id == history.chapterId }
	} else {
		-2
	}

	return HistoryInfo(
		totalChapters = chapters.size,
		currentChapter = currentChapter,
		history = history,
		isIncognitoMode = isIncognitoMode,
		isChapterMissing = history != null && manga.isLoaded && manga.allChapters.none { it.id == history.chapterId },
		canDownload = !manga.isLocal,
		estimatedTime = estimatedTime,
	)
}
