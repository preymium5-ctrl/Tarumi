package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

class ProgressUpdateUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val database: MangaDatabase,
	private val localMangaRepository: LocalMangaRepository,
	private val networkState: NetworkState,
) {

	suspend operator fun invoke(manga: Manga): Float {
		val history = database.getHistoryDao().find(manga.id) ?: return PROGRESS_NONE
		val details = resolveDetails(manga) ?: return PROGRESS_NONE

		val chapter = details.findChapterById(history.chapterId)
			?: return estimateFromCounts(details, history)
		val chapters = details.getChapters(chapter.branch)
		val chaptersCount = chapters.size
		if (chaptersCount == 0) {
			return PROGRESS_NONE
		}
		val chapterIndex = chapters.indexOfFirst { x -> x.id == history.chapterId }.coerceAtLeast(0)

		val pagesCount = resolvePagesCount(details, chapter)
		val pagePercent = if (pagesCount > 0) {
			((history.page + 1).toFloat() / pagesCount).coerceIn(0f, 1f)
		} else {
			// Offline / pages unavailable: treat stored percent as chapter-local if it looks like one,
			// otherwise assume chapter start.
			when {
				history.percent in 0f..1f && history.chaptersCount == chaptersCount -> {
					// Likely already overall — extract residual chapter share
					val residual = history.percent * chaptersCount - chapterIndex
					residual.coerceIn(0f, 1f)
				}
				history.percent in 0f..1f && history.chaptersCount <= 1 -> history.percent.coerceIn(0f, 1f)
				else -> 0f
			}
		}

		val ppc = 1f / chaptersCount
		val result = (ppc * chapterIndex + ppc * pagePercent).coerceIn(0f, 1f)
		if (result != history.percent || history.chaptersCount != chaptersCount) {
			database.getHistoryDao().update(
				history.copy(
					chapterId = chapter.id,
					percent = result,
					chaptersCount = chaptersCount,
				),
			)
		}
		return result
	}

	/**
	 * Prefer chapters already attached to [manga] so offline/downloaded manga can recompute progress
	 * without network. Fall back to local copy, then remote details when online.
	 */
	private suspend fun resolveDetails(manga: Manga): Manga? {
		if (!manga.chapters.isNullOrEmpty()) {
			return manga
		}
		// Downloaded / local entry
		if (manga.isLocal) {
			return manga
		}
		// Remote manga with a local download that has chapter list
		runCatchingCancellable {
			localMangaRepository.findSavedManga(manga, withDetails = true)?.manga
		}.getOrNull()?.takeIf { !it.chapters.isNullOrEmpty() }?.let { return it }

		if (!networkState.value) {
			return null
		}

		val seed = if (manga.isLocal) {
			localMangaRepository.getRemoteManga(manga) ?: manga
		} else {
			manga
		}
		val repo = mangaRepositoryFactory.create(seed.source)
		return runCatchingCancellable {
			if (manga.source != seed.source || seed.chapters.isNullOrEmpty()) {
				repo.getDetails(seed)
			} else {
				seed
			}
		}.getOrNull()
	}

	private suspend fun resolvePagesCount(details: Manga, chapter: MangaChapter): Int {
		// Prefer local pages when available (offline-safe)
		runCatchingCancellable {
			val local = localMangaRepository.findSavedManga(details, withDetails = true)?.manga
			val localChapter = local?.chapters?.findById(chapter.id) ?: chapter.takeIf {
				details.isLocal || local != null
			}
			if (localChapter != null && (details.isLocal || local != null)) {
				val localRepo = mangaRepositoryFactory.create(
					local?.source ?: details.source,
				)
				val pages = localRepo.getPages(localChapter)
				if (pages.isNotEmpty()) return pages.size
			}
		}
		if (!networkState.value && !details.isLocal) {
			return 0
		}
		return runCatchingCancellable {
			val repo = mangaRepositoryFactory.create(chapter.source)
			repo.getPages(chapter).size
		}.getOrDefault(0)
	}

	/**
	 * Fallback when the stored chapter id is no longer present (source rotated ids).
	 * Rescale previous progress to the new chapter total.
	 */
	private suspend fun estimateFromCounts(details: Manga, history: HistoryEntity): Float {
		val newTotal = details.getChapters(details.getPreferredBranch(null)).size
			.takeIf { it > 0 } ?: details.chapters?.size ?: 0
		if (newTotal == 0 || history.chaptersCount <= 0 || !ReadingProgress.isValid(history.percent)) {
			return PROGRESS_NONE
		}
		val estimated = (history.percent * history.chaptersCount / newTotal).coerceIn(0f, 1f)
		if (estimated != history.percent || history.chaptersCount != newTotal) {
			database.getHistoryDao().update(history.copy(percent = estimated, chaptersCount = newTotal))
		}
		return estimated
	}
}
