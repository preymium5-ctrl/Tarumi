package org.koitharu.kotatsu.tracker.domain.model

import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

sealed interface MangaUpdates {

	val manga: Manga

	data class Success(
		override val manga: Manga,
		val branch: String?,
		val newChapters: List<MangaChapter>,
		val isValid: Boolean,
	) : MangaUpdates {

		fun isNotEmpty() = newChapters.isNotEmpty()

		fun lastChapterDate(): Long {
			val latestNewChapterDate = newChapters.maxOfOrNull { it.uploadDate } ?: 0L
			if (latestNewChapterDate > 0L) {
				return latestNewChapterDate
			}
			if (newChapters.isNotEmpty()) {
				return System.currentTimeMillis()
			}
			return manga.chapters?.maxOfOrNull { it.uploadDate } ?: 0L
		}
	}

	data class Failure(
		override val manga: Manga,
		val error: Throwable?,
	) : MangaUpdates {

		fun shouldRetry() = error is TooManyRequestExceptions
	}
}
