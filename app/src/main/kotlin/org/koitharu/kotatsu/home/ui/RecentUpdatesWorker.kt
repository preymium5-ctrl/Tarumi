package org.koitharu.kotatsu.home.ui

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class RecentUpdatesWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) : CoroutineWorker(appContext, params) {

	private val homeFeedCache = HomeFeedCache(appContext)

	@AssistedFactory
	interface Factory {
		fun create(@Assisted appContext: Context, @Assisted params: WorkerParameters): RecentUpdatesWorker
	}

	override suspend fun doWork(): Result {
		return runCatchingCancellable {
			val snapshot = homeFeedCache.load()
			val updates = loadRecentUpdates()
			if (updates.isNotEmpty()) {
				homeFeedCache.save(snapshot.updatedWith(updates))
			}
			Result.success()
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(Result.retry())
	}

	private suspend fun loadRecentUpdates(): List<RecentUpdateGroup> {
		val groups = ArrayList<RecentUpdateGroup>(RECENT_LIMIT)
		val seenIds = HashSet<Long>(RECENT_LIMIT)
		for (source in RECENT_SOURCES) {
			if (groups.size >= RECENT_LIMIT) {
				break
			}
			runCatchingCancellable {
				loadSourceUpdates(source, groups, seenIds)
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
		return groups.rank()
	}

	private suspend fun loadSourceUpdates(
		source: MangaParserSource,
		groups: MutableList<RecentUpdateGroup>,
		seenIds: MutableSet<Long>,
	) = coroutineScope {
		val repository = mangaRepositoryFactory.create(source)
		val order = when {
			SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
			SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
			else -> repository.defaultSortOrder
		}
		var offset = 0
		var sourceCount = 0
		val semaphore = Semaphore(DETAIL_CONCURRENCY)
		repeat(PAGE_ATTEMPTS) {
			if (groups.size >= RECENT_LIMIT || sourceCount >= RECENT_SOURCE_LIMIT) {
				return@repeat
			}
			val page = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
				repository.getList(offset, order, MangaListFilter.EMPTY)
			}.orEmpty()
			if (page.isEmpty()) {
				return@repeat
			}
			val candidates = page.filter { seenIds.add(it.id) }
			if (candidates.isEmpty()) {
				offset += page.size
				return@repeat
			}
			val deferredDetails = candidates.map { manga ->
				async {
					semaphore.withPermit {
						val details = withTimeoutOrNull(DETAILS_TIMEOUT_MS) {
							repository.getDetails(manga)
						} ?: manga
						delay(DETAIL_DELAY_MS)
						details
					}
				}
			}
			val detailsList = deferredDetails.awaitAll()
			for (details in detailsList) {
				if (groups.size >= RECENT_LIMIT || sourceCount >= RECENT_SOURCE_LIMIT) {
					break
				}
				sourceCount++
				val chapters = details.chapters.orEmpty()
					.sortedWith(CHAPTER_COMPARATOR)
					.take(CHAPTERS_PER_TITLE)
				if (chapters.isEmpty()) {
					continue
				}
				groups += RecentUpdateGroup(
					manga = details,
					chapters = chapters,
					sourceTitle = source.title,
					sortDate = chapters.maxOf(MangaChapter::uploadDate),
				)
			}
			offset += page.size
			delay(PAGE_DELAY_MS)
		}
	}

	private fun HomeFeedSnapshot?.updatedWith(updates: List<RecentUpdateGroup>): HomeFeedSnapshot {
		val now = System.currentTimeMillis()
		val current = this ?: HomeFeedSnapshot(
			savedAt = now,
			recentUpdatesSavedAt = 0L,
			recentUpdatesCacheVersion = RECENT_CACHE_VERSION,
			recommendationPeriod = -1L,
			featuredPeriod = -1L,
			featured = emptyList(),
			trending = emptyList(),
			manhuaRecommendations = emptyList(),
			mangaRecommendations = emptyList(),
			smartRecommendationPeriod = -1L,
			smartRecommendations = emptyList(),
			recentUpdates = emptyList(),
		)
		return current.copy(
			savedAt = now,
			recentUpdatesSavedAt = now,
			recentUpdatesCacheVersion = RECENT_CACHE_VERSION,
			recentUpdates = (updates + current.recentUpdates).rank(),
		)
	}

	private fun List<RecentUpdateGroup>.rank(): List<RecentUpdateGroup> {
		return rankRecentUpdateGroups(
			limit = RECENT_LIMIT,
			chaptersPerTitle = CHAPTERS_PER_TITLE,
		)
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<RecentUpdatesWorker>(6, TimeUnit.HOURS)
				.setConstraints(createConstraints())
				.addTag(TAG)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(TAG).await()
		}

		override suspend fun isScheduled(): Boolean {
			return workManager.awaitUniqueWorkInfoByName(TAG).any { !it.state.isFinished }
		}

		private fun createConstraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()
	}

	private companion object {
		const val TAG = "recent_updates_worker"
		const val RECENT_LIMIT = 2_000
		const val RECENT_SOURCE_LIMIT = 350
		const val CHAPTERS_PER_TITLE = 3
		const val PAGE_ATTEMPTS = 24
		const val PAGE_TIMEOUT_MS = 6_000L
		const val DETAILS_TIMEOUT_MS = 6_000L
		const val PAGE_DELAY_MS = 750L
		const val DETAIL_DELAY_MS = 80L
		const val DETAIL_CONCURRENCY = 2
		const val RECENT_CACHE_VERSION = 9

		val RECENT_SOURCES = listOf(
			MangaParserSource.MANGAPLUSPARSER_EN,
			MangaParserSource.AQUAMANGA,
			MangaParserSource.ASURASCANS,
			MangaParserSource.ASURASCANS_US,
			MangaParserSource.ASURASCANSGG,
			MangaParserSource.WEEBCENTRAL,
			MangaParserSource.FLAMECOMICS,
			MangaParserSource.MANGAFIRE_EN,
			MangaParserSource.MANHUAFAST,
		)

		val CHAPTER_COMPARATOR = compareByDescending<MangaChapter> { it.uploadDate }
			.thenByDescending { it.number }
	}
}
