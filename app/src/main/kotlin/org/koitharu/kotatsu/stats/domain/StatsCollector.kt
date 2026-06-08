package org.koitharu.kotatsu.stats.domain

import androidx.collection.LongSparseArray
import androidx.collection.set
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
					chapters = visibleChapters.size.coerceAtLeast(1),
				),
				pages = visiblePages,
				chapters = visibleChapters,
			)
			stats[mangaId] = newEntry
			commit(newEntry.stats)
			return
		}
		val pagesDelta = visiblePages.count { entry.pages.add(it) }
		val chaptersDelta = visibleChapters.count { entry.chapters.add(it) }
		val newEntry = entry.copy(
			state = state,
			stats = StatsEntity(
				mangaId = mangaId,
				startedAt = entry.stats.startedAt,
				duration = now - entry.stats.startedAt,
				pages = entry.stats.pages + pagesDelta,
				chapters = entry.stats.chapters + chaptersDelta,
			),
		)
		stats[mangaId] = newEntry
		commit(newEntry.stats)
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
