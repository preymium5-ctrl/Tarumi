package org.koitharu.kotatsu.home.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.distinctById
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) : BaseViewModel() {

	private val _featuredComics = MutableStateFlow<List<Manga>>(emptyList())
	val featuredComics: StateFlow<List<Manga>> = _featuredComics

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

	init {
		_featuredComics.value = cachedFeaturedComics
		_trendingComics.value = cachedTrendingComics
		_recentUpdates.value = cachedRecentUpdates
		_recentUpdatesLoading.value = cachedRecentUpdates.isEmpty() && isRecentUpdatesLoading
		_manhuaRecommendations.value = cachedManhuaRecommendations
		_manhuaRecommendationsLoading.value = cachedManhuaRecommendations.isEmpty() && isManhuaRecommendationsLoading
		_mangaRecommendations.value = cachedMangaRecommendations
		_mangaRecommendationsLoading.value = cachedMangaRecommendations.isEmpty() && isMangaRecommendationsLoading
		val featuredPeriod = currentFeaturedPeriod()
		if (
			(cachedFeaturedComics.isEmpty() || cachedTrendingComics.isEmpty() || cachedFeaturedPeriod != featuredPeriod) &&
			!isFeaturedLoading
		) {
			launchJob(Dispatchers.Default) {
				isFeaturedLoading = true
				val all = loadAsuraComics(FEATURED_POOL_LIMIT)
				val featuredPool = all.rotateForPeriod(featuredPeriod)
				val featured = featuredPool.take(FEATURED_LIMIT).map { loadMangaDetailsOrDefault(it) }
				val trending = featuredPool.drop(FEATURED_LIMIT).take(TRENDING_LIMIT)
				cachedFeaturedComics = featured
				cachedTrendingComics = trending
				cachedFeaturedPeriod = featuredPeriod
				_featuredComics.value = featured
				_trendingComics.value = trending
				isFeaturedLoading = false
			}
		}
		if (cachedRecentUpdates.isEmpty() && !isRecentUpdatesLoading) {
			launchJob(Dispatchers.Default) {
				isRecentUpdatesLoading = true
				_recentUpdatesLoading.value = true
				try {
					val updates = runCatchingCancellable {
						loadRecentUpdates(RECENT_PAGE_SIZE * RECENT_PAGE_COUNT)
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrDefault(cachedRecentUpdates)
					cachedRecentUpdates = updates
					_recentUpdates.value = updates
				} finally {
					_recentUpdatesLoading.value = false
					isRecentUpdatesLoading = false
				}
			}
		}
		if (cachedManhuaRecommendations.isEmpty() && !isManhuaRecommendationsLoading) {
			launchJob(Dispatchers.Default) {
				isManhuaRecommendationsLoading = true
				_manhuaRecommendationsLoading.value = true
				try {
					val comics = loadRecommendationComics(
						sources = MANHUA_RECOMMENDATION_SOURCES,
						comicType = ComicType.MANHUA,
						limit = RECOMMENDATION_LIMIT,
					)
					cachedManhuaRecommendations = comics
					_manhuaRecommendations.value = comics
				} finally {
					_manhuaRecommendationsLoading.value = false
					isManhuaRecommendationsLoading = false
				}
			}
		}
		if (cachedMangaRecommendations.isEmpty() && !isMangaRecommendationsLoading) {
			launchJob(Dispatchers.Default) {
				isMangaRecommendationsLoading = true
				_mangaRecommendationsLoading.value = true
				try {
					val comics = loadRecommendationComics(
						sources = MANGA_RECOMMENDATION_SOURCES,
						comicType = ComicType.MANGA,
						limit = RECOMMENDATION_LIMIT,
					)
					cachedMangaRecommendations = comics
					_mangaRecommendations.value = comics
				} finally {
					_mangaRecommendationsLoading.value = false
					isMangaRecommendationsLoading = false
				}
			}
		}
	}

	fun setRecentUpdatesPage(page: Int) {
		val pageCount = ((recentUpdates.value.size + RECENT_PAGE_SIZE - 1) / RECENT_PAGE_SIZE)
			.coerceIn(1, RECENT_PAGE_COUNT)
		_recentUpdatesPage.value = page.coerceIn(0, pageCount - 1)
	}

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
			repeat(6) {
				if (result.size >= limit) {
					return@repeat
				}
				val page = runCatchingCancellable {
					repository.getList(offset, order, MangaListFilter.EMPTY)
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrDefault(emptyList())
				if (page.isEmpty()) {
					return@repeat
				}
				result.addAll(page)
				offset += page.size
			}
			if (result.size >= limit) {
				break
			}
		}
		return result.distinctById().take(limit)
	}

	private suspend fun loadRecommendationComics(
		sources: List<MangaParserSource>,
		comicType: ComicType,
		limit: Int,
	): List<Manga> {
		val result = ArrayList<Manga>(limit)
		for (source in sources) {
			if (result.size >= limit) {
				break
			}
			val repository = mangaRepositoryFactory.create(source)
			val order = when {
				SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
				SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
				else -> repository.defaultSortOrder
			}
			var offset = 0
			repeat(RECOMMENDATION_PAGE_ATTEMPTS) {
				if (result.size >= limit) {
					return@repeat
				}
				val page = runCatchingCancellable {
					repository.getList(offset, order, MangaListFilter.EMPTY)
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrDefault(emptyList())
				if (page.isEmpty()) {
					return@repeat
				}
				for (manga in page) {
					val details = runCatchingCancellable {
						repository.getDetails(manga)
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrDefault(manga)
					if (details.detectComicType() == comicType) {
						result += details
					}
					if (result.size >= limit) {
						break
					}
				}
				offset += page.size
			}
		}
		return result.distinctById().take(limit)
	}

	private suspend fun loadMangaDetailsOrDefault(manga: Manga): Manga {
		val repository = mangaRepositoryFactory.create(manga.source)
		return runCatchingCancellable {
			repository.getDetails(manga)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(manga)
	}

	private suspend fun loadRecentUpdates(limit: Int): List<RecentUpdateGroup> {
		val groups = ArrayList<RecentUpdateGroup>(limit)
		val seenIds = HashSet<Long>(limit + RECENT_CANDIDATES_PER_SOURCE)
		for (source in RECENT_UPDATE_SOURCES) {
			if (groups.size >= limit) {
				break
			}
			withTimeoutOrNull(RECENT_SOURCE_TIMEOUT_MS) {
				loadRecentUpdatesFromSource(source, groups, seenIds, limit)
			}
		}
		return publishRecentUpdates(groups, limit)
	}

	private suspend fun loadRecentUpdatesFromSource(
		source: MangaParserSource,
		groups: MutableList<RecentUpdateGroup>,
		seenIds: MutableSet<Long>,
		limit: Int,
	) {
		val repository = mangaRepositoryFactory.create(source)
		val order = when {
			SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
			SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
			else -> repository.defaultSortOrder
		}
		var offset = 0
		var sourceCount = 0
		repeat(RECENT_SOURCE_PAGE_ATTEMPTS) {
			if (sourceCount >= RECENT_CANDIDATES_PER_SOURCE || groups.size >= limit) {
				return@repeat
			}
			val page = withTimeoutOrNull(RECENT_PAGE_TIMEOUT_MS) {
				repository.getList(offset, order, MangaListFilter.EMPTY)
			}.orEmpty()
			if (page.isEmpty()) {
				return@repeat
			}
			for (manga in page) {
				if (sourceCount >= RECENT_CANDIDATES_PER_SOURCE || groups.size >= limit) {
					break
				}
				if (!seenIds.add(manga.id)) {
					continue
				}
				sourceCount++
				val details = withTimeoutOrNull(RECENT_DETAILS_TIMEOUT_MS) {
					repository.getDetails(manga)
				} ?: continue
				val chapters = details.chapters.orEmpty()
					.sortedWith(CHAPTER_COMPARATOR)
					.take(RECENT_CHAPTERS_PER_TITLE)
				if (chapters.isEmpty()) {
					continue
				}
				groups += RecentUpdateGroup(
					manga = details,
					chapters = chapters,
					sourceTitle = (details.source as? MangaParserSource)?.title ?: details.source.name,
					sortDate = chapters.maxOf(MangaChapter::uploadDate),
				)
				if (groups.size >= RECENT_PAGE_SIZE) {
					publishRecentUpdates(groups, limit)
					_recentUpdatesLoading.value = false
				}
				if (groups.size >= RECENT_FAST_VISIBLE_LIMIT) {
					return
				}
			}
			offset += page.size
		}
	}

	private fun publishRecentUpdates(groups: List<RecentUpdateGroup>, limit: Int): List<RecentUpdateGroup> {
		val updates = groups.sortedByDescending { it.sortDate }.take(limit)
		cachedRecentUpdates = updates
		_recentUpdates.value = updates
		if (updates.isNotEmpty()) {
			_recentUpdatesLoading.value = false
		}
		return updates
	}

	private fun List<Manga>.rotateForPeriod(period: Long): List<Manga> {
		if (size < 2) {
			return this
		}
		val start = ((period * FEATURED_LIMIT) % size).toInt()
		return drop(start) + take(start)
	}

	private fun currentFeaturedPeriod(): Long {
		return System.currentTimeMillis() / FEATURED_ROTATION_MS
	}

	private companion object {
		var cachedFeaturedComics: List<Manga> = emptyList()
		var cachedTrendingComics: List<Manga> = emptyList()
		var cachedRecentUpdates: List<RecentUpdateGroup> = emptyList()
		var cachedManhuaRecommendations: List<Manga> = emptyList()
		var cachedMangaRecommendations: List<Manga> = emptyList()
		var cachedFeaturedPeriod: Long = -1L
		var isFeaturedLoading = false
		var isRecentUpdatesLoading = false
		var isManhuaRecommendationsLoading = false
		var isMangaRecommendationsLoading = false

		const val FEATURED_LIMIT = 15
		const val FEATURED_POOL_LIMIT = 60
		const val TRENDING_LIMIT = 10
		const val RECOMMENDATION_LIMIT = 20
		const val RECOMMENDATION_PAGE_ATTEMPTS = 4
		const val RECENT_PAGE_SIZE = 10
		const val RECENT_PAGE_COUNT = 6
		const val RECENT_CHAPTERS_PER_TITLE = 3
		const val RECENT_CANDIDATES_PER_SOURCE = 24
		const val RECENT_SOURCE_PAGE_ATTEMPTS = 3
		const val RECENT_SOURCE_TIMEOUT_MS = 7_000L
		const val RECENT_PAGE_TIMEOUT_MS = 3_000L
		const val RECENT_DETAILS_TIMEOUT_MS = 2_000L
		const val RECENT_FAST_VISIBLE_LIMIT = 20
		const val FEATURED_ROTATION_MS = 3L * 24L * 60L * 60L * 1000L

		val CHAPTER_COMPARATOR = compareByDescending<MangaChapter> { it.uploadDate }
			.thenByDescending { it.number }

		val ASURA_SOURCES = listOf(
			MangaParserSource.ASURASCANS,
			MangaParserSource.ASURASCANS_US,
			MangaParserSource.ASURASCANSGG,
		)

		val RECENT_UPDATE_SOURCES = listOf(
			MangaParserSource.FLAMECOMICS,
		)

		val MANHUA_RECOMMENDATION_SOURCES = listOf(
			MangaParserSource.MANHUAFAST,
		)

		val MANGA_RECOMMENDATION_SOURCES = listOf(
			MangaParserSource.MANGAPLUSPARSER_EN,
			MangaParserSource.MANGAFIRE_EN,
			MangaParserSource.NINEMANGA_EN,
			MangaParserSource.AQUAMANGA,
		)
	}
}
