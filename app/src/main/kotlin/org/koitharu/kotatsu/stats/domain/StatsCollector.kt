package org.koitharu.kotatsu.stats.domain

import androidx.collection.LongSparseArray
import androidx.collection.set
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.RetainedLifecycleCoroutineScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.stats.data.StatsEntity
import javax.inject.Inject

@ViewModelScoped
class StatsCollector @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	lifecycle: ViewModelLifecycle,
) {

	private val viewModelScope = RetainedLifecycleCoroutineScope(lifecycle)
	private val stats = LongSparseArray<Entry>(1)
	private val chapterMutex = Mutex()

	@Synchronized
	fun onStateChanged(
		mangaId: Long,
		state: ReaderState,
		visibleStates: Collection<ReaderState> = listOf(state),
	) {
		if (!settings.isStatsEnabled) {
			return
		}
		val now = System.currentTimeMillis()
		val visiblePages = visibleStates.ifEmpty { listOf(state) }
			.mapTo(HashSet(visibleStates.size.coerceAtLeast(1))) { PageKey(it.chapterId, it.page) }
		val visibleChapters = visiblePages.mapTo(HashSet(visiblePages.size)) { it.chapterId }
		val entry = stats[mangaId]
		if (entry == null) {
			val newEntry = Entry(
				state = state,
				stats = StatsEntity(
					mangaId = mangaId,
					startedAt = now,
					duration = 0,
					pages = visiblePages.size,
					// Chapters are recorded asynchronously as unique global reads.
					chapters = 0,
				),
				pages = visiblePages,
				// Session-local set only tracks what we already attempted to persist this session.
				chapters = HashSet(),
			)
			stats[mangaId] = newEntry
			commit(newEntry.stats)
			recordUniqueChapters(mangaId, visibleChapters, now)
			return
		}
		val pagesDelta = visiblePages.count { entry.pages.add(it) }
		val newChapterIds = visibleChapters.filter { entry.chapters.add(it) }
		val newEntry = entry.copy(
			state = state,
			stats = StatsEntity(
				mangaId = mangaId,
				startedAt = entry.stats.startedAt,
				duration = now - entry.stats.startedAt,
				pages = entry.stats.pages + pagesDelta,
				chapters = entry.stats.chapters,
			),
		)
		stats[mangaId] = newEntry
		commit(newEntry.stats)
		if (newChapterIds.isNotEmpty()) {
			recordUniqueChapters(mangaId, newChapterIds, now)
		}
	}

	@Synchronized
	fun onPause(mangaId: Long) {
		val entry = stats[mangaId]
		if (entry != null) {
			val now = System.currentTimeMillis()
			commit(
				entry.stats.copy(
					duration = now - entry.stats.startedAt,
				),
			)
		}
		stats.remove(mangaId)
	}

	/**
	 * Persist unique chapter IDs and bump the session chapter counter only for first-time opens.
	 * This prevents re-opening the same chapter across sessions from inflating "chapters read".
	 */
	private fun recordUniqueChapters(mangaId: Long, chapterIds: Collection<Long>, readAt: Long) {
		if (chapterIds.isEmpty()) return
		viewModelScope.launch(Dispatchers.Default) {
			chapterMutex.withLock {
				var added = 0
				for (chapterId in chapterIds) {
					runCatchingCancellable {
						if (db.getStatsDao().tryInsertChapter(mangaId, chapterId, readAt)) {
							added++
						}
					}.onFailure {
						it.printStackTraceDebug()
					}
				}
				if (added > 0) {
					synchronized(this@StatsCollector) {
						val entry = stats[mangaId] ?: return@synchronized
						val updated = entry.copy(
							stats = entry.stats.copy(
								chapters = entry.stats.chapters + added,
								duration = System.currentTimeMillis() - entry.stats.startedAt,
							),
						)
						stats[mangaId] = updated
						commit(updated.stats)
					}
				}
			}
		}
	}

	private fun commit(entity: StatsEntity) {
		viewModelScope.launch(Dispatchers.Default) {
			var lastError: Throwable? = null
			repeat(STATS_WRITE_ATTEMPTS) { attempt ->
				runCatchingCancellable {
					db.getStatsDao().upsert(entity)
				}.onSuccess {
					return@launch
				}.onFailure { e ->
					lastError = e
					if (attempt < STATS_WRITE_ATTEMPTS - 1) {
						delay(STATS_WRITE_RETRY_DELAY_MS)
					}
				}
			}
			lastError?.printStackTraceDebug()
		}
	}

	private data class Entry(
		val state: ReaderState,
		val stats: StatsEntity,
		val pages: MutableSet<PageKey>,
		val chapters: MutableSet<Long>,
	)

	private data class PageKey(
		val chapterId: Long,
		val page: Int,
	)

	private companion object {
		const val STATS_WRITE_ATTEMPTS = 4
		const val STATS_WRITE_RETRY_DELAY_MS = 350L
	}
}
