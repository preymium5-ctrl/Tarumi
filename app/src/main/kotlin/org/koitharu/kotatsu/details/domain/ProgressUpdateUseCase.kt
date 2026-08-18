package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.MangaRepository
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

	/**
	 * Refreshes history with **chapter-local** page progress (0..1 within the current chapter),
	 * not series-wide progress across all chapters. Works online and for offline/downloaded manga.
	 */
	suspend operator fun invoke(manga: Manga): Float {
		val history = database.getHistoryDao().find(manga.id) ?: return PROGRESS_NONE
		val details = resolveDetails(manga)
			?: return history.percent.takeIf { it >= 0f } ?: PROGRESS_NONE

		val chapter = details.findChapterById(history.chapterId)
			?: return history.percent.takeIf { it >= 0f } ?: PROGRESS_NONE
		val branchChapters = details.chapters.orEmpty().let { list ->
			if (chapter.branch == null) list else list.filter { it.branch == chapter.branch }
		}
		val chaptersCount = branchChapters.size.coerceAtLeast(history.chaptersCount)

		val pagesCount = resolvePagesCount(details, chapter)
		val result = when {
			pagesCount > 0 -> chapterLocalPercent(history.page, pagesCount)
			history.percent in 0f..1f -> history.percent
			// Offline without page list: treat having a page index as partial progress.
			history.page > 0 -> (history.page / (history.page + 1f)).coerceIn(0.05f, 0.95f)
			else -> 0f
		}

		if (result != history.percent || history.chaptersCount != chaptersCount || chapter.id != history.chapterId) {
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
		if (manga.isLocal) {
			return manga
		}
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
		// 1) Local/offline pages first (downloaded chapter).
		runCatchingCancellable {
			val localManga = when {
				details.isLocal -> details
				else -> localMangaRepository.findSavedManga(details, withDetails = true)?.manga
			}
			if (localManga != null) {
				val localChapter = localManga.chapters?.findById(chapter.id)
					?: localManga.chapters?.find { it.url == chapter.url }
					?: chapter.takeIf { details.isLocal }
				if (localChapter != null) {
					val localRepo = mangaRepositoryFactory.create(localManga.source)
					val pages = localRepo.getPages(localChapter)
					if (pages.isNotEmpty()) return pages.size
				}
			}
		}
		// 2) Try chapter source directly (local storage source when offline).
		if (!networkState.value || details.isLocal) {
			runCatchingCancellable {
				val repo = mangaRepositoryFactory.create(chapter.source)
				repo.getPages(chapter).size
			}.getOrNull()?.takeIf { it > 0 }?.let { return it }
			return 0
		}
		// 3) Online fallback.
		return runCatchingCancellable {
			val repo = mangaRepositoryFactory.create(chapter.source)
			repo.getPages(chapter).size
		}.getOrDefault(0)
	}

	private fun chapterLocalPercent(pageIndex: Int, pagesCount: Int): Float {
		if (pagesCount <= 0) return 0f
		if (pagesCount == 1) return 1f
		if (pageIndex >= pagesCount - 1) return 1f
		if (pagesCount >= 10 && pageIndex >= pagesCount - 3) return 1f
		if (pagesCount >= 5 && pageIndex >= pagesCount - 2) return 1f
		return (pageIndex / (pagesCount - 1).toFloat()).coerceIn(0f, 1f)
	}
}
