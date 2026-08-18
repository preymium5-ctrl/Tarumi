package org.koitharu.kotatsu.reader.domain

import android.util.LongSparseArray
import androidx.annotation.CheckResult
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject

/** Keep this many chapters in continuous (webtoon) memory — page-count trim was too aggressive. */
private const val MAX_CHAPTERS_IN_MEMORY = 5

@ViewModelScoped
class ChaptersLoader @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
) {

	private val chapters = LongSparseArray<MangaChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()
	@Volatile
	private var mangaDetails: MangaDetails? = null

	val size: Int
		get() = chapters.size()

	suspend fun init(manga: MangaDetails) = mutex.withLock {
		mangaDetails = manga
		chapters.clear()
		manga.allChapters.forEach {
			chapters.put(it.id, it)
		}
	}

	/** Ensure [mangaDetails] chapter map includes every chapter id from details (branch switches). */
	fun ensureChapter(chapter: MangaChapter) {
		if (chapters[chapter.id] == null) {
			chapters.put(chapter.id, chapter)
		}
	}

	suspend fun loadPrevNextChapter(manga: MangaDetails, currentId: Long, isNext: Boolean): Boolean {
		mangaDetails = manga
		// Refresh chapter map in case details were reloaded
		manga.allChapters.forEach { ensureChapter(it) }
		val list = manga.allChapters
		val predicate: (MangaChapter) -> Boolean = { it.id == currentId }
		val index = if (isNext) list.indexOfFirst(predicate) else list.indexOfLast(predicate)
		if (index == -1) return false
		val newChapter = list.getOrNull(if (isNext) index + 1 else index - 1) ?: return false
		ensureChapter(newChapter)
		if (hasPages(newChapter.id)) return true
		val newPages = loadChapter(newChapter.id)
		if (newPages.isEmpty()) return false
		mutex.withLock {
			// Trim oldest chapters so continuous webtoon can keep loading without OOM.
			while (chapterPages.chaptersSize >= MAX_CHAPTERS_IN_MEMORY) {
				if (isNext) {
					chapterPages.removeFirst()
				} else {
					chapterPages.removeLast()
				}
			}
			if (isNext) {
				chapterPages.addLast(newChapter.id, newPages)
			} else {
				chapterPages.addFirst(newChapter.id, newPages)
			}
		}
		return true
	}

	@CheckResult
	suspend fun loadSingleChapter(chapterId: Long): Boolean {
		// Recover chapter from details if missing from the map
		if (chapters[chapterId] == null) {
			mangaDetails?.allChapters?.find { it.id == chapterId }?.let { ensureChapter(it) }
		}
		val pages = loadChapter(chapterId)
		return mutex.withLock {
			chapterPages.clear()
			if (pages.isEmpty()) {
				false
			} else {
				chapterPages.addLast(chapterId, pages)
				true
			}
		}
	}

	fun peekChapter(chapterId: Long): MangaChapter? = chapters[chapterId]
		?: mangaDetails?.allChapters?.find { it.id == chapterId }

	fun hasPages(chapterId: Long): Boolean {
		return chapterId in chapterPages
	}

	fun getPages(chapterId: Long): List<MangaPage> = synchronized(chapterPages) {
		return chapterPages.subList(chapterId).map { it.toMangaPage() }
	}

	fun getPagesCount(chapterId: Long): Int {
		return chapterPages.size(chapterId)
	}

	fun last() = chapterPages.last()

	fun first() = chapterPages.first()

	fun snapshot() = chapterPages.toList()

	private suspend fun loadChapter(chapterId: Long): List<ReaderPage> {
		val chapter = chapters[chapterId]
			?: mangaDetails?.allChapters?.find { it.id == chapterId }
			?: return emptyList()
		ensureChapter(chapter)
		val pages = resolvePages(chapter)
		if (pages.isEmpty()) return emptyList()
		return pages.mapIndexed { index, page ->
			ReaderPage(page, index, chapterId)
		}
	}

	/**
	 * Prefer offline/downloaded pages first. This fixes NSFW offline reading (e.g. HiveComic)
	 * where the chapter object still carries the remote source and network getPages fails,
	 * which previously left the progress bar stuck and blocked next-chapter loading.
	 */
	private suspend fun resolvePages(chapter: MangaChapter): List<MangaPage> {
		val localPages = runCatchingCancellable {
			resolveLocalPages(chapter)
		}.onFailure(Throwable::printStackTraceDebug).getOrNull()
		if (!localPages.isNullOrEmpty()) {
			return localPages
		}

		val repo = mangaRepositoryFactory.create(chapter.source)
		return runCatchingCancellable {
			repo.getPages(chapter)
		}.onFailure(Throwable::printStackTraceDebug).getOrDefault(emptyList())
	}

	private suspend fun resolveLocalPages(chapter: MangaChapter): List<MangaPage>? {
		val details = mangaDetails

		// 1) Chapter already points at local storage.
		if (chapter.source == LocalMangaSource || isLocalChapterUrl(chapter.url)) {
			val pages = localMangaRepository.getPages(
				chapter.copy(source = LocalMangaSource),
			)
			if (pages.isNotEmpty()) return pages
		}

		// 2) Merged local chapter for this id/url from MangaDetails.
		val localFromDetails = details?.local?.manga?.chapters?.findById(chapter.id)
			?: details?.local?.manga?.chapters?.find { it.url == chapter.url }
		if (localFromDetails != null) {
			val pages = localMangaRepository.getPages(
				localFromDetails.copy(source = LocalMangaSource),
			)
			if (pages.isNotEmpty()) return pages
		}

		// 3) Lookup download by remote manga id (HiveComic / any remote source offline).
		val seed = details?.toManga()
		if (seed != null) {
			val saved = if (seed.isLocal) {
				seed
			} else {
				localMangaRepository.findSavedManga(seed, withDetails = true)?.manga
			}
			val savedChapter = saved?.chapters?.findById(chapter.id)
				?: saved?.chapters?.find { it.number == chapter.number && chapter.number > 0f }
			if (savedChapter != null) {
				val pages = localMangaRepository.getPages(
					savedChapter.copy(source = LocalMangaSource),
				)
				if (pages.isNotEmpty()) return pages
			}
		}
		return null
	}

	private fun isLocalChapterUrl(url: String): Boolean {
		return url.startsWith("file:", ignoreCase = true) ||
			url.startsWith("content:", ignoreCase = true) ||
			url.startsWith("zip:", ignoreCase = true) ||
			url.contains("://") && (
				url.contains("/storage/") ||
					url.contains("/Android/data/")
				)
	}
}
