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
	 * not series-wide progress across all chapters.
	 */
	suspend operator fun invoke(manga: Manga): Float {
		val history = database.getHistoryDao().find(manga.id) ?: return PROGRESS_NONE
		val details = resolveDetails(manga) ?: return history.percent.coerceIn(0f, 1f)

		val chapter = details.findChapterById(history.chapterId)
			?: return history.percent.coerceIn(0f, 1f)
		val chapters = details.getChapters(chapter.branch)
		val chaptersCount = chapters.size.coerceAtLeast(history.chaptersCount)

		val pagesCount = resolvePagesCount(details, chapter)
		val result = if (pagesCount > 0) {
			((history.page + 1).toFloat() / pagesCount).coerceIn(0f, 1f)
		} else {
			// Offline without page list: keep existing chapter-local percent when valid.
			history.percent.coerceIn(0f, 1f)
		}

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
}
