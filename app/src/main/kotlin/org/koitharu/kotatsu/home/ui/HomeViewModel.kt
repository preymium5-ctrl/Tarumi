package org.koitharu.kotatsu.home.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.distinctById
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import kotlinx.coroutines.plus

enum class HomeSection {
	FEATURED,
	RECENT,
	SMART,
	MANHUA,
	MANGA,
}

@HiltViewModel
class HomeViewModel @Inject constructor(
	@ApplicationContext context: Context,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val historyRepository: HistoryRepository,
	private val appSettings: AppSettings,
) : BaseViewModel() {

	private val homeFeedCache = HomeFeedCache(context)
	/** Performance mode or low-RAM device → lite home (smaller lists, fewer network hits). */
	val isLiteMode: Boolean = appSettings.isPerformanceMode || context.isLowRamDevice()
	val isPerformanceMode get() = isLiteMode

	private val _isRecentUpdatesEnabled = MutableStateFlow(appSettings.isRecentUpdatesEnabled)
	val isRecentUpdatesEnabled: StateFlow<Boolean> = _isRecentUpdatesEnabled

	private val _featuredComics = MutableStateFlow<List<Manga>>(emptyList())
	val featuredComics: StateFlow<List<Manga>> = _featuredComics

	val continueReadingComics: StateFlow<List<MangaWithHistory>> = historyRepository
		.observeAllWithHistory(
			order = org.koitharu.kotatsu.list.domain.ListSortOrder.LAST_READ,
			filterOptions = emptySet(),
			limit = CONTINUE_READING_LIMIT,
		)
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	private val _trendingComics = MutableStateFlow<List<Manga>>(emptyList())
	val trendingComics: StateFlow<List<Manga>> = _trendingComics

	private val _recentUpdates = MutableStateFlow<List<RecentUpdateGroup>>(emptyList())
	val recentUpdates: StateFlow<List<RecentUpdateGroup>> = _recentUpdates

	private val _recentUpdatesPage = MutableStateFlow(0)
	val recentUpdatesPage: StateFlow<Int> = _recentUpdatesPage

	private val _recentUpdatesLoading = MutableStateFlow(true)
	val recentUpdatesLoading: StateFlow<Boolean> = _recentUpdatesLoading

	private val _manhuaRecommendations = MutableStateFlow<List<Manga>>(emptyList())
	val manhuaRecommendations: StateFlow<List<Manga>> = _manhuaRecommendations

	private val _manhuaRecommendationsLoading = MutableStateFlow(true)
	val manhuaRecommendationsLoading: StateFlow<Boolean> = _manhuaRecommendationsLoading

	private val _mangaRecommendations = MutableStateFlow<List<Manga>>(emptyList())
	val mangaRecommendations: StateFlow<List<Manga>> = _mangaRecommendations

	private val _mangaRecommendationsLoading = MutableStateFlow(true)
	val mangaRecommendationsLoading: StateFlow<Boolean> = _mangaRecommendationsLoading

	private val _smartRecommendations = MutableStateFlow<List<Manga>>(emptyList())
	val smartRecommendations: StateFlow<List<Manga>> = _smartRecommendations

	private val _smartRecommendationsLoading = MutableStateFlow(true)
	val smartRecommendationsLoading: StateFlow<Boolean> = _smartRecommendationsLoading

	init {
		// Cache-first: paint disk/memory cache immediately, then refresh lightly.
		restoreCachedHomeFeed()
		_featuredComics.value = cachedFeaturedComics
		_trendingComics.value = cachedTrendingComics
		_recentUpdates.value = if (isLiteMode || !appSettings.isRecentUpdatesEnabled) {
			emptyList()
		} else {
			cachedRecentUpdates.visibleRecentUpdates()
		}
		_recentUpdatesLoading.value = false
		_manhuaRecommendations.value = cachedManhuaRecommendations
		_manhuaRecommendationsLoading.value = false
		_mangaRecommendations.value = cachedMangaRecommendations
		_mangaRecommendationsLoading.value = false
		_smartRecommendations.value = cachedSmartRecommendations
		_smartRecommendationsLoading.value = false

		// Only featured/trending on open; other rails load when scrolled into view.
		ensureSectionLoaded(HomeSection.FEATURED)
	}

	/** Toggle Recent Updates on Home. Off = hide section and stop all recent fetching. */
	fun setRecentUpdatesEnabled(enabled: Boolean) {
		if (_isRecentUpdatesEnabled.value == enabled) return
		appSettings.isRecentUpdatesEnabled = enabled
		_isRecentUpdatesEnabled.value = enabled
		if (enabled) {
			if (!isLiteMode) {
				loadRecentIfNeeded()
			}
		} else {
			// Stop UI work; background expand checks the flag as well.
			_recentUpdatesLoading.value = false
			_recentUpdates.value = emptyList()
		}
	}

	/**
	 * Lazy-load a home section when it becomes visible (or on first open for featured).
	 * List-only fetches — no [MangaRepository.getDetails].
	 */
	fun ensureSectionLoaded(section: HomeSection) {
		when (section) {
			HomeSection.FEATURED -> loadFeaturedIfNeeded()
			HomeSection.RECENT -> {
				if (!isLiteMode && _isRecentUpdatesEnabled.value) {
					loadRecentIfNeeded()
				}
			}
			HomeSection.SMART -> loadSmartIfNeeded()
			HomeSection.MANHUA -> loadManhuaIfNeeded()
			HomeSection.MANGA -> loadMangaIfNeeded()
		}
	}

	private fun loadFeaturedIfNeeded() {
		val hasCache = cachedFeaturedComics.isNotEmpty() && cachedTrendingComics.isNotEmpty()
		val age = feedAgeMs(cachedHomeFeedAt)
		// Soft refresh: keep showing cache; only block with loading when empty.
		if (!hasCache && !isFeaturedLoading) {
			launchJob(Dispatchers.Default) {
				isFeaturedLoading = true
				try {
					refreshFeaturedAndTrending(showLoading = true)
				} finally {
					isFeaturedLoading = false
				}
			}
			return
		}
		// Background prefetch near/after 7-day TTL — swap only when ready (no lag).
		if (hasCache && shouldBackgroundRefresh(age) && !isFeaturedLoading) {
			launchJob(Dispatchers.Default) {
				isFeaturedLoading = true
				try {
					refreshFeaturedAndTrending(showLoading = false)
				} finally {
					isFeaturedLoading = false
				}
			}
		}
	}

	private suspend fun refreshFeaturedAndTrending(showLoading: Boolean) {
		if (showLoading) {
			// only first install / empty cache
		}
		val period = currentFeaturedPeriod()
		val all = loadAsuraComics(featuredPoolLimit())
		val featuredPool = all.rotateForPeriod(period)
		val featured = featuredPool.take(featuredLimit())
		val trending = featuredPool.drop(featuredLimit()).take(trendingLimit())
		if (featured.isEmpty() && trending.isEmpty()) return
		// Atomic swap after network completes — UI never shows empty/loading mid-refresh.
		cachedFeaturedComics = featured
		cachedTrendingComics = trending
		cachedFeaturedPeriod = period
		cachedHomeFeedAt = System.currentTimeMillis()
		_featuredComics.value = featured
		_trendingComics.value = trending
		saveHomeFeedCache()
	}

	private fun loadRecentIfNeeded() {
		if (!_isRecentUpdatesEnabled.value || isLiteMode) {
			_recentUpdates.value = emptyList()
			_recentUpdatesLoading.value = false
			return
		}
		val expired = cachedRecentUpdatesAt <= 0L ||
			System.currentTimeMillis() - cachedRecentUpdatesAt >= recentCacheTtlMs()
		val needForeground = cachedRecentUpdates.isEmpty() || expired
		// Always paint cache first (may already be set in init).
		if (cachedRecentUpdates.isNotEmpty()) {
			_recentUpdates.value = cachedRecentUpdates.visibleRecentUpdates()
			_recentUpdatesLoading.value = false
		}
		if (needForeground && !isRecentUpdatesLoading) {
			launchJob(Dispatchers.Default) {
				if (!_isRecentUpdatesEnabled.value) return@launchJob
				isRecentUpdatesLoading = true
				_recentUpdatesLoading.value = cachedRecentUpdates.isEmpty()
				try {
					// Fast first paint: small batch with real latest + 2 chapters.
					val updates = loadMangaPlusRecentUpdates(recentRefreshLimit())
					if (updates.isNotEmpty() && _isRecentUpdatesEnabled.value) {
						publishRecentUpdates(updates, updateSavedAt = true)
					}
				} finally {
					_recentUpdatesLoading.value = false
					isRecentUpdatesLoading = false
				}
			}
		}
		// Background: grow library toward 2000 without blocking the home UI.
		if (!isLiteMode && !isRecentExpanding && _isRecentUpdatesEnabled.value) {
			launchJob(Dispatchers.Default) {
				expandRecentLibraryInBackground()
			}
		}
	}

	private fun loadManhuaIfNeeded() {
		loadRecommendationRailIfNeeded(
			cached = { cachedManhuaRecommendations },
			setCached = { cachedManhuaRecommendations = it },
			emit = { _manhuaRecommendations.value = it },
			loadingFlag = { isManhuaRecommendationsLoading },
			setLoadingFlag = { isManhuaRecommendationsLoading = it },
			loadingFlow = _manhuaRecommendationsLoading,
			bucket = 1,
			savedAt = { cachedRecommendationSavedAt },
			setSavedAt = { cachedRecommendationSavedAt = it },
		)
	}

	private fun loadMangaIfNeeded() {
		loadRecommendationRailIfNeeded(
			cached = { cachedMangaRecommendations },
			setCached = { cachedMangaRecommendations = it },
			emit = { _mangaRecommendations.value = it },
			loadingFlag = { isMangaRecommendationsLoading },
			setLoadingFlag = { isMangaRecommendationsLoading = it },
			loadingFlow = _mangaRecommendationsLoading,
			bucket = 2,
			savedAt = { cachedRecommendationSavedAt },
			setSavedAt = { cachedRecommendationSavedAt = it },
		)
	}

	private fun loadSmartIfNeeded() {
		loadRecommendationRailIfNeeded(
			cached = { cachedSmartRecommendations },
			setCached = { cachedSmartRecommendations = it },
			emit = { _smartRecommendations.value = it },
			loadingFlag = { isSmartRecommendationsLoading },
			setLoadingFlag = { isSmartRecommendationsLoading = it },
			loadingFlow = _smartRecommendationsLoading,
			bucket = 0,
			savedAt = { cachedSmartRecommendationSavedAt },
			setSavedAt = { cachedSmartRecommendationSavedAt = it },
		)
	}

	/**
	 * 7-day rails: show cache forever until refresh completes in background, then swap.
	 * Loading spinner only when there is nothing cached yet.
	 */
	private fun loadRecommendationRailIfNeeded(
		cached: () -> List<Manga>,
		setCached: (List<Manga>) -> Unit,
		emit: (List<Manga>) -> Unit,
		loadingFlag: () -> Boolean,
		setLoadingFlag: (Boolean) -> Unit,
		loadingFlow: MutableStateFlow<Boolean>,
		bucket: Int,
		savedAt: () -> Long,
		setSavedAt: (Long) -> Unit,
	) {
		val hasCache = cached().isNotEmpty()
		val age = feedAgeMs(savedAt())
		if (!hasCache && !loadingFlag()) {
			launchJob(Dispatchers.Default) {
				setLoadingFlag(true)
				loadingFlow.value = true
				try {
					val comics = loadMangaPlusList(
						period = currentRecommendationPeriod(),
						bucket = bucket,
						limit = recommendationLimit(),
					)
					if (comics.isNotEmpty()) {
						setCached(comics)
						setSavedAt(System.currentTimeMillis())
						cachedHomeFeedAt = System.currentTimeMillis()
						emit(comics)
						saveHomeFeedCache()
					}
				} finally {
					loadingFlow.value = false
					setLoadingFlag(false)
				}
			}
			return
		}
		if (hasCache && shouldBackgroundRefresh(age) && !loadingFlag()) {
			launchJob(Dispatchers.Default) {
				setLoadingFlag(true)
				// Do NOT flip loadingFlow — keep current cards on screen.
				try {
					val comics = loadMangaPlusList(
						period = currentRecommendationPeriod(),
						bucket = bucket,
						limit = recommendationLimit(),
					)
					if (comics.isNotEmpty()) {
						setCached(comics)
						setSavedAt(System.currentTimeMillis())
						cachedHomeFeedAt = System.currentTimeMillis()
						emit(comics) // silent swap
						saveHomeFeedCache()
					}
				} finally {
					setLoadingFlag(false)
				}
			}
		}
	}

	private fun featuredLimit() = if (isLiteMode) FEATURED_LIMIT_LITE else FEATURED_LIMIT
	private fun trendingLimit() = if (isLiteMode) TRENDING_LIMIT_LITE else TRENDING_LIMIT
	private fun featuredPoolLimit() = if (isLiteMode) FEATURED_POOL_LIMIT_LITE else FEATURED_POOL_LIMIT
	private fun recommendationLimit() = if (isLiteMode) RECOMMENDATION_LIMIT_LITE else RECOMMENDATION_LIMIT
	private fun recentRefreshLimit() = if (isLiteMode) RECENT_FOREGROUND_REFRESH_LIMIT_LITE else RECENT_FOREGROUND_REFRESH_LIMIT
	private fun recentCacheTtlMs() = if (isLiteMode) RECENT_CACHE_TTL_LITE_MS else RECENT_CACHE_TTL_MS

	/** Age of a feed snapshot in ms (Long.MAX_VALUE if never saved). */
	private fun feedAgeMs(savedAt: Long): Long {
		if (savedAt <= 0L) return Long.MAX_VALUE
		return (System.currentTimeMillis() - savedAt).coerceAtLeast(0L)
	}

	/**
	 * Refresh in background when past [HOME_FEED_PREFETCH_LEAD_MS] before the 7-day TTL,
	 * or when already expired. UI keeps old data until the new fetch finishes.
	 */
	private fun shouldBackgroundRefresh(ageMs: Long): Boolean {
		if (ageMs == Long.MAX_VALUE) return true
		return ageMs >= (HOME_FEED_TTL_MS - HOME_FEED_PREFETCH_LEAD_MS)
	}

	fun setRecentUpdatesPage(page: Int) {
		val total = recentUpdates.value.size
		val pageCount = if (total <= 0) {
			1
		} else {
			((total + RECENT_PAGE_SIZE - 1) / RECENT_PAGE_SIZE).coerceAtLeast(1)
		}
		_recentUpdatesPage.value = page.coerceIn(0, pageCount - 1)
	}

	/** Featured/trending still use Asura list cards only (no getDetails). */
	private suspend fun loadAsuraComics(limit: Int): List<Manga> {
		val result = ArrayList<Manga>(limit)
		for (source in ASURA_SOURCES) {
			val repository = mangaRepositoryFactory.create(source)
			val order = when {
				SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
				SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
				else -> repository.defaultSortOrder
			}
			var offset = 0
			repeat(3) {
				if (result.size >= limit) return@repeat
				val page = runCatchingCancellable {
					repository.getList(offset, order, MangaListFilter.EMPTY)
				}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
				if (page.isEmpty()) return@repeat
				result.addAll(page)
				offset += page.size
			}
			if (result.size >= limit) break
		}
		return result.distinctById().take(limit)
	}

	/**
	 * Manga Plus EN list slice — no getDetails.
	 *
	 * Manga Plus is a [org.koitharu.kotatsu.parsers.core.SinglePageMangaParser]:
	 * any `offset > 0` returns an **empty list**. We always fetch offset 0, then
	 * rotate/slice in-memory so Smart / Manhua / Manga rails don't show the same cards.
	 */
	private suspend fun loadMangaPlusList(period: Long, bucket: Int, limit: Int): List<Manga> {
		val repository = mangaRepositoryFactory.create(MANGA_PLUS_EN)
		val order = when {
			SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
			SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
			else -> repository.defaultSortOrder
		}
		// Must stay 0 — SinglePageMangaParser short-circuits non-zero offsets.
		val page = withTimeoutOrNull(RECENT_PAGE_TIMEOUT_MS) {
			runCatchingCancellable {
				repository.getList(0, order, MangaListFilter.EMPTY)
			}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
		}.orEmpty()
		val all = page.distinctById()
		if (all.isEmpty()) {
			return emptyList()
		}
		// In-memory rail offset (parser ignores non-zero offsets).
		val start = (
			((period + bucket) % RECOMMENDATION_OFFSET_BUCKETS).toInt() * limit
			) % all.size
		return (all.drop(start) + all.take(start)).take(limit)
	}

	/**
	 * Fast first paint for Recent: Manga Plus EN list + getDetails for a **small** batch only
	 * so the latest chapter + 2 previous are real titles.
	 */
	private suspend fun loadMangaPlusRecentUpdates(limit: Int): List<RecentUpdateGroup> = coroutineScope {
		val repository = mangaRepositoryFactory.create(MANGA_PLUS_EN)
		val order = when {
			SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
			SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
			else -> repository.defaultSortOrder
		}
		val page = withTimeoutOrNull(RECENT_PAGE_TIMEOUT_MS) {
			runCatchingCancellable {
				repository.getList(0, order, MangaListFilter.EMPTY)
			}.getOrDefault(emptyList())
		}.orEmpty()
		val seeds = page.distinctById().take(limit)
		if (seeds.isEmpty()) {
			return@coroutineScope emptyList()
		}
		val semaphore = Semaphore(if (isLiteMode) 2 else 3)
		seeds.map { manga ->
			async {
				semaphore.withPermit {
					detailsToRecentGroup(repository, manga, MANGA_PLUS_EN)
				}
			}
		}.awaitAll().filterNotNull()
	}

	/**
	 * Grow the recent library toward [RECENT_CACHE_LIMIT] (2000) in the background:
	 * - multi-source **list** pages
	 * - low-concurrency getDetails
	 * - slim groups (3 chapters, no description)
	 * - partial UI/disk updates so home never freezes
	 */
	private suspend fun expandRecentLibraryInBackground() {
		if (isRecentExpanding || !_isRecentUpdatesEnabled.value) return
		isRecentExpanding = true
		try {
			if (cachedRecentUpdates.size >= RECENT_CACHE_LIMIT) return
			val seenIds = cachedRecentUpdates.mapTo(HashSet(RECENT_CACHE_LIMIT)) { it.manga.id }
			for (source in RECENT_EXPAND_SOURCES) {
				if (!_isRecentUpdatesEnabled.value) break
				if (cachedRecentUpdates.size >= RECENT_CACHE_LIMIT) break
				runCatchingCancellable {
					expandRecentFromSource(source, seenIds)
				}.onFailure { it.printStackTraceDebug() }
			}
			// Final persist
			if (_isRecentUpdatesEnabled.value) {
				cachedRecentUpdatesAt = System.currentTimeMillis()
				saveHomeFeedCache()
			}
		} finally {
			isRecentExpanding = false
		}
	}

	private suspend fun expandRecentFromSource(
		source: MangaParserSource,
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
		val semaphore = Semaphore(RECENT_EXPAND_CONCURRENCY)
		repeat(RECENT_EXPAND_PAGE_ATTEMPTS) {
			if (cachedRecentUpdates.size >= RECENT_CACHE_LIMIT || sourceCount >= RECENT_EXPAND_PER_SOURCE) {
				return@repeat
			}
			val page = withTimeoutOrNull(RECENT_PAGE_TIMEOUT_MS) {
				runCatchingCancellable {
					repository.getList(offset, order, MangaListFilter.EMPTY)
				}.getOrDefault(emptyList())
			}.orEmpty()
			if (page.isEmpty()) return@repeat
			val candidates = page.filter { seenIds.add(it.id) }.take(
				(RECENT_CACHE_LIMIT - cachedRecentUpdates.size).coerceAtLeast(0),
			)
			if (candidates.isEmpty()) {
				offset += page.size
				return@repeat
			}
			val batch = candidates.map { manga ->
				async {
					semaphore.withPermit {
						detailsToRecentGroup(repository, manga, source)
					}
				}
			}.awaitAll().filterNotNull()
			sourceCount += candidates.size
			if (batch.isNotEmpty()) {
				// Merge + slim; publish so pagination can grow without a full re-crawl.
				publishRecentUpdates(batch, updateSavedAt = true)
			}
			offset += page.size
		}
	}

	private suspend fun detailsToRecentGroup(
		repository: MangaRepository,
		manga: Manga,
		source: MangaParserSource,
	): RecentUpdateGroup? {
		val details = withTimeoutOrNull(RECENT_DETAILS_TIMEOUT_MS) {
			runCatchingCancellable { repository.getDetails(manga) }.getOrNull()
		} ?: manga
		val chapters = details.chapters.orEmpty()
			.sortedWith(CHAPTER_COMPARATOR)
			.take(RECENT_CHAPTERS_PER_TITLE)
		if (chapters.isEmpty()) return null
		return RecentUpdateGroup(
			manga = details.slimForRecent(chapters),
			chapters = chapters,
			sourceTitle = source.title,
			sortDate = chapters.maxOf(MangaChapter::uploadDate).takeIf { it > 0L }
				?: System.currentTimeMillis(),
		)
	}

	/** Drop heavy fields so 2000 titles fit in memory/disk more easily. */
	private fun Manga.slimForRecent(chapters: List<MangaChapter>): Manga = copy(
		description = null,
		chapters = chapters,
		// Keep cover/title/tags for UI; drop large alt sets if huge
		altTitles = if (altTitles.size > 3) altTitles.take(3).toSet() else altTitles,
	)

	private fun publishRecentUpdates(updates: List<RecentUpdateGroup>, updateSavedAt: Boolean) {
		if (updates.isEmpty()) {
			return
		}
		val rankedUpdates = mergeRecentUpdates(updates, cachedRecentUpdates)
			.map { it.slimGroup() }
		cachedRecentUpdates = rankedUpdates
		if (updateSavedAt) {
			cachedRecentUpdatesAt = System.currentTimeMillis()
		}
		// UI list can hold the full library for paging, but only one page of Views is inflated.
		_recentUpdates.value = rankedUpdates.visibleRecentUpdates()
		_recentUpdatesLoading.value = false
		setRecentUpdatesPage(_recentUpdatesPage.value)
		saveHomeFeedCache()
	}

	private fun RecentUpdateGroup.slimGroup(): RecentUpdateGroup {
		val ch = chapters.take(RECENT_CHAPTERS_PER_TITLE)
		return copy(
			manga = manga.slimForRecent(ch),
			chapters = ch,
		)
	}

	private fun rankRecentUpdates(groups: List<RecentUpdateGroup>, limit: Int): List<RecentUpdateGroup> {
		return groups.rankRecentUpdateGroups(
			limit = limit,
			chaptersPerTitle = RECENT_CHAPTERS_PER_TITLE,
		)
	}

	private fun mergeRecentUpdates(
		freshUpdates: List<RecentUpdateGroup>,
		cachedUpdates: List<RecentUpdateGroup>,
	): List<RecentUpdateGroup> {
		return rankRecentUpdates(freshUpdates + cachedUpdates, RECENT_CACHE_LIMIT)
	}

	private fun List<RecentUpdateGroup>.visibleRecentUpdates(): List<RecentUpdateGroup> {
		// Keep up to 2000 for pagination; HomeFragment only inflates the current page.
		return take(RECENT_VISIBLE_LIMIT)
	}

	private fun List<RecentUpdateGroup>.isRecentUpdatesCacheCompatible(): Boolean {
		// Accept any non-empty cache; rails now use Manga Plus EN only.
		return isNotEmpty()
	}

	private fun List<Manga>.rotateForPeriod(period: Long): List<Manga> {
		if (size < 2) {
			return this
		}
		val start = ((period * FEATURED_LIMIT) % size).toInt()
		return drop(start) + take(start)
	}

	/** 7-day rotation bucket (stable content for a week). */
	private fun currentFeaturedPeriod(): Long {
		return System.currentTimeMillis() / HOME_FEED_TTL_MS
	}

	private fun currentRecommendationPeriod(): Long {
		return System.currentTimeMillis() / HOME_FEED_TTL_MS
	}

	private fun currentSmartRecommendationPeriod(): Long {
		return System.currentTimeMillis() / HOME_FEED_TTL_MS
	}

	private fun restoreCachedHomeFeed() {
		if (
			cachedFeaturedComics.isNotEmpty() ||
			cachedTrendingComics.isNotEmpty() ||
			cachedRecentUpdates.isNotEmpty() ||
			cachedManhuaRecommendations.isNotEmpty() ||
			cachedMangaRecommendations.isNotEmpty() ||
			cachedSmartRecommendations.isNotEmpty()
		) {
			return
		}
		val snapshot = homeFeedCache.load() ?: return
		// Always restore cache for instant UI; background refresh handles 7-day expiry.
		cachedFeaturedComics = snapshot.featured
		cachedTrendingComics = snapshot.trending
		cachedRecentUpdates = snapshot.recentUpdates
		cachedManhuaRecommendations = snapshot.manhuaRecommendations
		cachedMangaRecommendations = snapshot.mangaRecommendations
		cachedSmartRecommendations = snapshot.smartRecommendations
		cachedFeaturedPeriod = snapshot.featuredPeriod
		cachedRecommendationPeriod = snapshot.recommendationPeriod
		cachedSmartRecommendationPeriod = snapshot.smartRecommendationPeriod
		cachedHomeFeedAt = snapshot.savedAt
		// Prefer explicit rail timestamps; fall back to snapshot.savedAt for older caches.
		cachedRecommendationSavedAt = snapshot.savedAt
		cachedSmartRecommendationSavedAt = snapshot.savedAt
		cachedRecentUpdatesAt = snapshot.recentUpdatesSavedAt
		if (
			snapshot.recentUpdatesCacheVersion != RECENT_CACHE_VERSION ||
			!cachedRecentUpdates.isRecentUpdatesCacheCompatible()
		) {
			cachedRecentUpdates = emptyList()
			cachedRecentUpdatesAt = 0L
		}
	}

	private fun saveHomeFeedCache() {
		val savedAt = cachedHomeFeedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
		cachedHomeFeedAt = savedAt
		homeFeedCache.save(
			HomeFeedSnapshot(
				savedAt = savedAt,
				recentUpdatesSavedAt = cachedRecentUpdatesAt,
				recentUpdatesCacheVersion = RECENT_CACHE_VERSION,
				recommendationPeriod = cachedRecommendationPeriod,
				featuredPeriod = cachedFeaturedPeriod,
				featured = cachedFeaturedComics,
				trending = cachedTrendingComics,
				manhuaRecommendations = cachedManhuaRecommendations,
				mangaRecommendations = cachedMangaRecommendations,
				smartRecommendationPeriod = cachedSmartRecommendationPeriod,
				smartRecommendations = cachedSmartRecommendations,
				recentUpdates = cachedRecentUpdates.take(RECENT_CACHE_LIMIT),
			),
		)
	}

	companion object {
		var cachedFeaturedComics: List<Manga> = emptyList()
		var cachedTrendingComics: List<Manga> = emptyList()
		var cachedRecentUpdates: List<RecentUpdateGroup> = emptyList()
		var cachedManhuaRecommendations: List<Manga> = emptyList()
		var cachedMangaRecommendations: List<Manga> = emptyList()
		var cachedSmartRecommendations: List<Manga> = emptyList()
		var cachedFeaturedPeriod: Long = -1L
		var cachedRecommendationPeriod: Long = -1L
		var cachedSmartRecommendationPeriod: Long = -1L
		var cachedHomeFeedAt: Long = 0L
		/** Wall-clock when manhua/manga rails were last successfully refreshed. */
		var cachedRecommendationSavedAt: Long = 0L
		/** Wall-clock when smart rail was last successfully refreshed. */
		var cachedSmartRecommendationSavedAt: Long = 0L
		var cachedRecentUpdatesAt: Long = 0L
		var isFeaturedLoading = false
		var isRecentUpdatesLoading = false
		var isRecentExpanding = false
		var isManhuaRecommendationsLoading = false
		var isMangaRecommendationsLoading = false
		var isSmartRecommendationsLoading = false

		const val FEATURED_LIMIT = 8
		const val FEATURED_LIMIT_LITE = 5
		const val CONTINUE_READING_LIMIT = 4
		const val FEATURED_POOL_LIMIT = 24
		const val FEATURED_POOL_LIMIT_LITE = 14
		const val TRENDING_LIMIT = 6
		const val TRENDING_LIMIT_LITE = 4
		const val RECOMMENDATION_LIMIT = 10
		const val RECOMMENDATION_LIMIT_LITE = 6
		const val RECOMMENDATION_OFFSET_BUCKETS = 8
		/** Titles shown per pagination page (Views only for this many). */
		const val RECENT_PAGE_SIZE = 10
		/** Full library size target (disk + slim in-memory cache). */
		const val RECENT_CACHE_LIMIT = 2_000
		const val RECENT_VISIBLE_LIMIT = RECENT_CACHE_LIMIT
		/** Fast first paint for Recent. */
		const val RECENT_FOREGROUND_REFRESH_LIMIT = 20
		const val RECENT_FOREGROUND_REFRESH_LIMIT_LITE = 10
		/** Latest chapter + 2 previous. */
		const val RECENT_CHAPTERS_PER_TITLE = 3
		const val RECENT_PAGE_TIMEOUT_MS = 4_000L
		const val RECENT_DETAILS_TIMEOUT_MS = 5_000L
		const val RECENT_EXPAND_CONCURRENCY = 2
		const val RECENT_EXPAND_PAGE_ATTEMPTS = 20
		const val RECENT_EXPAND_PER_SOURCE = 400
		/** Featured / trending / rec rails: refresh every 7 days. */
		const val HOME_FEED_TTL_MS = 7L * 24L * 60L * 60L * 1000L
		/** Start silent background fetch 1 day before expiry so swap is ready. */
		const val HOME_FEED_PREFETCH_LEAD_MS = 1L * 24L * 60L * 60L * 1000L
		const val RECENT_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
		const val RECENT_CACHE_TTL_LITE_MS = 24L * 60L * 60L * 1000L
		const val RECENT_CACHE_VERSION = 13

		val CHAPTER_COMPARATOR = compareByDescending<MangaChapter> { it.uploadDate }
			.thenByDescending { it.number }

		val MANGA_PLUS_EN = MangaParserSource.MANGAPLUSPARSER_EN

		val ASURA_SOURCES = listOf(
			MangaParserSource.ASURASCANS,
			MangaParserSource.ASURASCANS_US,
			MangaParserSource.ASURASCANSGG,
		)

		/** Background expand sources (still capped + low concurrency). */
		val RECENT_EXPAND_SOURCES = listOf(
			MangaParserSource.MANGAPLUSPARSER_EN,
			MangaParserSource.ASURASCANS,
			MangaParserSource.AQUAMANGA,
			MangaParserSource.MANGAFIRE_EN,
		)
	}
}
