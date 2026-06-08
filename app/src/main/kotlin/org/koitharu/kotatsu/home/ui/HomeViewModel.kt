package org.koitharu.kotatsu.home.ui

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.model.distinctById
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
	@ApplicationContext context: Context,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	@BaseHttpClient private val okHttpClient: OkHttpClient,
	private val historyRepository: HistoryRepository,
) : BaseViewModel() {

	private val homeFeedCache = HomeFeedCache(context)

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

	private val _smartRecommendations = MutableStateFlow<List<Manga>>(emptyList())
	val smartRecommendations: StateFlow<List<Manga>> = _smartRecommendations

	private val _smartRecommendationsLoading = MutableStateFlow(true)
	val smartRecommendationsLoading: StateFlow<Boolean> = _smartRecommendationsLoading

	init {
		restoreCachedHomeFeed()
		_featuredComics.value = cachedFeaturedComics
		_trendingComics.value = cachedTrendingComics
		_recentUpdates.value = cachedRecentUpdates.visibleRecentUpdates()
		_recentUpdatesLoading.value = cachedRecentUpdates.isEmpty() && isRecentUpdatesLoading
		_manhuaRecommendations.value = cachedManhuaRecommendations
		_manhuaRecommendationsLoading.value = cachedManhuaRecommendations.isEmpty() && isManhuaRecommendationsLoading
		_mangaRecommendations.value = cachedMangaRecommendations
		_mangaRecommendationsLoading.value = cachedMangaRecommendations.isEmpty() && isMangaRecommendationsLoading
		_smartRecommendations.value = cachedSmartRecommendations
		_smartRecommendationsLoading.value = cachedSmartRecommendations.isEmpty() && isSmartRecommendationsLoading
		val featuredPeriod = currentFeaturedPeriod()
		val recommendationPeriod = currentRecommendationPeriod()
		val smartRecommendationPeriod = currentSmartRecommendationPeriod()
		val isHomeCacheExpired = cachedHomeFeedAt <= 0L ||
			System.currentTimeMillis() - cachedHomeFeedAt >= HOME_CACHE_TTL_MS
		val isRecentUpdatesExpired = cachedRecentUpdatesAt <= 0L ||
			System.currentTimeMillis() - cachedRecentUpdatesAt >= RECENT_CACHE_TTL_MS
		val areRecommendationsExpired = cachedRecommendationPeriod != recommendationPeriod
		val areSmartRecommendationsExpired = cachedSmartRecommendationPeriod != smartRecommendationPeriod
		if (
			(cachedFeaturedComics.isEmpty() || cachedTrendingComics.isEmpty() || cachedFeaturedPeriod != featuredPeriod || isHomeCacheExpired) &&
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
				cachedHomeFeedAt = System.currentTimeMillis()
				_featuredComics.value = featured
				_trendingComics.value = trending
				saveHomeFeedCache()
				isFeaturedLoading = false
			}
		}
		if ((cachedRecentUpdates.isEmpty() || isRecentUpdatesExpired) && !isRecentUpdatesLoading) {
			launchJob(Dispatchers.Default) {
				isRecentUpdatesLoading = true
				_recentUpdatesLoading.value = cachedRecentUpdates.isEmpty()
				try {
					val updates = runCatchingCancellable {
						loadRecentUpdates(RECENT_CACHE_LIMIT) { partial ->
							publishRecentUpdates(partial, updateSavedAt = false)
						}
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrDefault(cachedRecentUpdates)
					if (updates.isNotEmpty()) {
						publishRecentUpdates(updates, updateSavedAt = true)
					}
				} finally {
					_recentUpdatesLoading.value = false
					isRecentUpdatesLoading = false
				}
			}
		}
		if ((cachedManhuaRecommendations.isEmpty() || areRecommendationsExpired) && !isManhuaRecommendationsLoading) {
			launchJob(Dispatchers.Default) {
				isManhuaRecommendationsLoading = true
				_manhuaRecommendationsLoading.value = true
				try {
					val comics = loadRecommendationComics(
						sources = MANHUA_RECOMMENDATION_SOURCES,
						comicType = ComicType.MANHUA,
						period = recommendationPeriod,
						limit = RECOMMENDATION_LIMIT,
					)
					cachedManhuaRecommendations = comics
					cachedRecommendationPeriod = recommendationPeriod
					cachedHomeFeedAt = System.currentTimeMillis()
					_manhuaRecommendations.value = comics
					saveHomeFeedCache()
				} finally {
					_manhuaRecommendationsLoading.value = false
					isManhuaRecommendationsLoading = false
				}
			}
		}
		if ((cachedMangaRecommendations.isEmpty() || areRecommendationsExpired) && !isMangaRecommendationsLoading) {
			launchJob(Dispatchers.Default) {
				isMangaRecommendationsLoading = true
				_mangaRecommendationsLoading.value = true
				try {
					val comics = loadRecommendationComics(
						sources = MANGA_RECOMMENDATION_SOURCES,
						comicType = ComicType.MANGA,
						period = recommendationPeriod,
						limit = RECOMMENDATION_LIMIT,
					)
					cachedMangaRecommendations = comics
					cachedRecommendationPeriod = recommendationPeriod
					cachedHomeFeedAt = System.currentTimeMillis()
					_mangaRecommendations.value = comics
					saveHomeFeedCache()
				} finally {
					_mangaRecommendationsLoading.value = false
					isMangaRecommendationsLoading = false
				}
			}
		}
		if ((cachedSmartRecommendations.isEmpty() || areSmartRecommendationsExpired) && !isSmartRecommendationsLoading) {
			launchJob(Dispatchers.Default) {
				isSmartRecommendationsLoading = true
				_smartRecommendationsLoading.value = cachedSmartRecommendations.isEmpty()
				try {
					val comics = loadSmartRecommendationComics(
						period = smartRecommendationPeriod,
						limit = RECOMMENDATION_LIMIT,
					)
					if (comics.isNotEmpty()) {
						cachedSmartRecommendations = comics
						cachedSmartRecommendationPeriod = smartRecommendationPeriod
						cachedHomeFeedAt = System.currentTimeMillis()
						_smartRecommendations.value = comics
						saveHomeFeedCache()
					}
				} finally {
					_smartRecommendationsLoading.value = false
					isSmartRecommendationsLoading = false
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
		period: Long,
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
			var offset = recommendationStartOffset(source, period, limit)
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

	private fun recommendationStartOffset(source: MangaParserSource, period: Long, limit: Int): Int {
		val sourceBucket = (source.name.hashCode() and Int.MAX_VALUE) % RECOMMENDATION_OFFSET_BUCKETS
		val periodBucket = ((period + sourceBucket) % RECOMMENDATION_OFFSET_BUCKETS).toInt()
		return (periodBucket * limit).coerceAtLeast(0)
	}

	private suspend fun loadSmartRecommendationComics(period: Long, limit: Int): List<Manga> {
		val history = runCatchingCancellable {
			historyRepository.getList(0, SMART_HISTORY_LIMIT)
		}.getOrDefault(emptyList())
		val historyIds = history.mapTo(HashSet(history.size)) { it.id }
		val preferredTags = history
			.flatMap { it.tags }
			.groupingBy { it.title.lowercase() }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(SMART_TAG_LIMIT)
			.mapTo(HashSet(SMART_TAG_LIMIT)) { it.key }
		val preferredTypes = history
			.groupingBy { it.detectComicType() }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.map { it.key }
		val preferredSources = history
			.mapNotNull { it.source as? MangaParserSource }
			.distinct()
			.filterNot { it.isNsfw() }
		val sources = (preferredSources + SMART_RECOMMENDATION_SOURCES).distinct()
		val scored = ArrayList<Pair<Manga, Int>>(limit * 2)
		for (source in sources) {
			if (scored.size >= limit * 2) {
				break
			}
			val repository = mangaRepositoryFactory.create(source)
			val order = when {
				SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
				SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
				else -> repository.defaultSortOrder
			}
			var offset = ((period % SMART_OFFSET_BUCKETS).toInt() * limit).coerceAtLeast(0)
			repeat(RECOMMENDATION_PAGE_ATTEMPTS) {
				if (scored.size >= limit * 2) {
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
					if (manga.id in historyIds) {
						continue
					}
					val details = runCatchingCancellable {
						repository.getDetails(manga)
					}.getOrDefault(manga)
					val score = details.smartScore(preferredTags, preferredTypes)
					if (score > 0 || preferredTags.isEmpty()) {
						scored += details to score
					}
					if (scored.size >= limit * 2) {
						break
					}
				}
				offset += page.size
			}
		}
		return scored
			.sortedWith(compareByDescending<Pair<Manga, Int>> { it.second }.thenByDescending { it.first.rating })
			.map { it.first }
			.distinctById()
			.take(limit)
			.ifEmpty { loadFallbackSmartRecommendations(limit) }
	}

	private suspend fun loadFallbackSmartRecommendations(limit: Int): List<Manga> {
		val result = ArrayList<Manga>(limit)
		for (source in SMART_RECOMMENDATION_SOURCES) {
			if (result.size >= limit) {
				break
			}
			val repository = mangaRepositoryFactory.create(source)
			val order = when {
				SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
				SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
				else -> repository.defaultSortOrder
			}
			val page = runCatchingCancellable {
				repository.getList(0, order, MangaListFilter.EMPTY)
			}.getOrDefault(emptyList())
			result += page
		}
		return result.distinctById().take(limit)
	}

	private fun Manga.smartScore(preferredTags: Set<String>, preferredTypes: List<ComicType>): Int {
		val tagScore = tags.count { it.title.lowercase() in preferredTags } * 3
		val typeScore = preferredTypes.indexOf(detectComicType())
			.takeIf { it >= 0 }
			?.let { (preferredTypes.size - it) * 2 }
			?: 0
		val ratingScore = (rating * 2f).toInt().coerceAtLeast(0)
		return tagScore + typeScore + ratingScore
	}

	private suspend fun loadMangaDetailsOrDefault(manga: Manga): Manga {
		val repository = mangaRepositoryFactory.create(manga.source)
		return runCatchingCancellable {
			repository.getDetails(manga)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(manga)
	}

	private suspend fun loadRecentUpdates(
		limit: Int,
		onPartial: (List<RecentUpdateGroup>) -> Unit = {},
	): List<RecentUpdateGroup> {
		return loadRecentUpdatesFromParser(limit, onPartial)
	}

	private suspend fun loadWeebCentralLatestUpdates(limit: Int): List<RecentUpdateGroup> {
		val feedItems = withTimeoutOrNull(RECENT_SOURCE_TIMEOUT_MS) {
			fetchWeebCentralLatestFeed(limit)
		}.orEmpty()
		if (feedItems.isEmpty()) {
			return emptyList()
		}
		val groups = ArrayList<RecentUpdateGroup>(feedItems.size)
		val seenSeries = HashSet<String>(feedItems.size)
		for (item in feedItems) {
			if (!seenSeries.add(item.seriesUrl)) {
				continue
			}
			val fallbackManga = item.toManga()
			val fallbackChapter = item.toChapter()
			val chapters = listOf(fallbackChapter)
			groups += RecentUpdateGroup(
				manga = fallbackManga.copy(chapters = chapters),
				chapters = chapters,
				sourceTitle = MangaParserSource.WEEBCENTRAL.title,
				sortDate = item.uploadDate,
			)
			if (groups.size >= limit) {
				break
			}
		}
		return groups
	}

	private suspend fun fetchWeebCentralLatestFeed(limit: Int): List<WeebCentralFeedItem> {
		val request = Request.Builder()
			.get()
			.url(WEEBCENTRAL_HOME_URL)
			.tag(org.koitharu.kotatsu.parsers.model.MangaSource::class.java, MangaParserSource.WEEBCENTRAL)
			.build()
		val html = okHttpClient.newCall(request).await().use { response ->
			response.body?.string().orEmpty()
		}
		val document = Jsoup.parse(html, WEEBCENTRAL_HOME_URL)
		val latestSection = document.select("h2").firstOrNull { heading ->
			heading.text().contains("Latest Updates", ignoreCase = true)
		}?.parent() ?: return emptyList()
		return latestSection.select("article")
			.mapNotNull { it.toWeebCentralFeedItem() }
			.take(limit)
	}

	private fun Element.toWeebCentralFeedItem(): WeebCentralFeedItem? {
		val seriesLink = selectFirst("a[href*=/series/]") ?: return null
		val chapterLink = selectFirst("a[href*=/chapters/]")
		val title = attr("data-tip")
			.ifBlank { selectFirst(".font-semibold")?.text().orEmpty() }
			.trim()
		if (title.isEmpty()) {
			return null
		}
		val seriesUrl = seriesLink.absUrl("href")
		if (seriesUrl.isEmpty()) {
			return null
		}
		val chapterUrl = chapterLink?.absUrl("href")?.takeIf { it.isNotEmpty() } ?: seriesUrl
		val coverUrl = selectFirst("source[srcset]")?.attr("srcset")?.substringBefore(' ')?.trim()
			?.let(::resolveWeebCentralUrl)
			?.takeIf { it.isNotEmpty() }
			?: selectFirst("img[src]")?.absUrl("src")?.takeIf { it.isNotEmpty() }
		val chapterTitle = chapterLink?.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
			?: select("span").lastOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
			?: "Chapter"
		val uploadDate = selectFirst("time[datetime]")?.attr("datetime")
			?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
			?: System.currentTimeMillis()
		return WeebCentralFeedItem(
			title = title,
			seriesUrl = seriesUrl,
			chapterUrl = chapterUrl,
			coverUrl = coverUrl,
			chapterTitle = chapterTitle,
			uploadDate = uploadDate,
		)
	}

	private fun resolveWeebCentralUrl(url: String): String {
		return when {
			url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
			url.startsWith("/") -> WEEBCENTRAL_HOME_URL.trimEnd('/') + url
			else -> WEEBCENTRAL_HOME_URL + url
		}
	}

	private fun WeebCentralFeedItem.toManga(): Manga {
		return Manga(
			id = stableId(seriesUrl),
			title = title,
			altTitles = emptySet(),
			url = seriesUrl,
			publicUrl = seriesUrl,
			rating = 0f,
			contentRating = ContentRating.SAFE,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = MangaState.ONGOING,
			authors = emptySet(),
			largeCoverUrl = coverUrl,
			description = null,
			chapters = listOf(toChapter()),
			source = MangaParserSource.WEEBCENTRAL,
		)
	}

	private fun WeebCentralFeedItem.toChapter(): MangaChapter {
		return MangaChapter(
			id = stableId(chapterUrl),
			title = chapterTitle,
			number = chapterTitle.toChapterNumber(),
			volume = 0,
			url = chapterUrl,
			scanlator = MangaParserSource.WEEBCENTRAL.title,
			uploadDate = uploadDate,
			branch = null,
			source = MangaParserSource.WEEBCENTRAL,
		)
	}

	private fun stableId(value: String): Long {
		return value.hashCode().toLong() and 0xffffffffL
	}

	private fun String.toChapterNumber(): Float {
		return Regex("""(?:Chapter|Episode|Rating)\s+([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
			.find(this)
			?.groupValues
			?.getOrNull(1)
			?.toFloatOrNull()
			?: 0f
	}

	private suspend fun loadRecentUpdatesFromParser(
		limit: Int,
		onPartial: (List<RecentUpdateGroup>) -> Unit,
	): List<RecentUpdateGroup> {
		val groups = ArrayList<RecentUpdateGroup>(limit)
		val seenIds = HashSet<Long>(limit + RECENT_CANDIDATES_PER_SOURCE)
		for (source in RECENT_UPDATE_SOURCES) {
			if (groups.size >= limit) {
				break
			}
			loadRecentUpdatesFromParserSource(source, groups, seenIds, limit, onPartial)
		}
		return rankRecentUpdates(groups, limit)
	}

	private suspend fun loadRecentUpdatesFromParserSource(
		source: MangaParserSource,
		groups: MutableList<RecentUpdateGroup>,
		seenIds: MutableSet<Long>,
		limit: Int,
		onPartial: (List<RecentUpdateGroup>) -> Unit,
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
				} ?: manga
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
				if (groups.size == 1 || groups.size % RECENT_PUBLISH_BATCH_SIZE == 0) {
					onPartial(rankRecentUpdates(groups, limit))
				}
				if (groups.size < limit) {
					delay(RECENT_DETAILS_DELAY_MS)
				}
			}
			offset += page.size
			if (groups.size < limit) {
				delay(RECENT_SOURCE_PAGE_DELAY_MS)
			}
		}
	}

	private fun publishRecentUpdates(updates: List<RecentUpdateGroup>, updateSavedAt: Boolean) {
		if (updates.isEmpty()) {
			return
		}
		val rankedUpdates = mergeRecentUpdates(updates, cachedRecentUpdates)
		cachedRecentUpdates = rankedUpdates
		if (updateSavedAt) {
			cachedRecentUpdatesAt = System.currentTimeMillis()
		}
		_recentUpdates.value = rankedUpdates.visibleRecentUpdates()
		_recentUpdatesLoading.value = false
		setRecentUpdatesPage(_recentUpdatesPage.value)
		saveHomeFeedCache()
	}

	private fun rankRecentUpdates(groups: List<RecentUpdateGroup>, limit: Int): List<RecentUpdateGroup> {
		return groups
			.distinctBy { recentUpdateKey(it) }
			.sortedByDescending { it.sortDate }
			.take(limit)
	}

	private fun mergeRecentUpdates(
		freshUpdates: List<RecentUpdateGroup>,
		cachedUpdates: List<RecentUpdateGroup>,
	): List<RecentUpdateGroup> {
		return rankRecentUpdates(freshUpdates + cachedUpdates, RECENT_CACHE_LIMIT)
	}

	private fun List<RecentUpdateGroup>.visibleRecentUpdates(): List<RecentUpdateGroup> {
		return take(RECENT_VISIBLE_LIMIT)
	}

	private fun recentUpdateKey(group: RecentUpdateGroup): String {
		val newestChapter = group.chapters.maxByOrNull { it.uploadDate }
		return "${group.manga.source.name}:${group.manga.id}:${newestChapter?.id ?: newestChapter?.url ?: group.manga.url}"
	}

	private fun List<RecentUpdateGroup>.isRecentUpdatesCacheCompatible(): Boolean {
		return all { group ->
			RECENT_UPDATE_SOURCES.any { source ->
				group.manga.source.name == source.name ||
					group.sourceTitle.equals(source.title, ignoreCase = true)
			}
		}
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

	private fun currentRecommendationPeriod(): Long {
		return System.currentTimeMillis() / RECOMMENDATION_ROTATION_MS
	}

	private fun currentSmartRecommendationPeriod(): Long {
		return System.currentTimeMillis() / SMART_RECOMMENDATION_ROTATION_MS
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
		if (System.currentTimeMillis() - snapshot.savedAt >= HOME_CACHE_TTL_MS) {
			return
		}
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

	private companion object {
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
		var cachedRecentUpdatesAt: Long = 0L
		var isFeaturedLoading = false
		var isRecentUpdatesLoading = false
		var isManhuaRecommendationsLoading = false
		var isMangaRecommendationsLoading = false
		var isSmartRecommendationsLoading = false

		const val FEATURED_LIMIT = 15
		const val FEATURED_POOL_LIMIT = 60
		const val TRENDING_LIMIT = 10
		const val RECOMMENDATION_LIMIT = 20
		const val RECOMMENDATION_PAGE_ATTEMPTS = 4
		const val RECOMMENDATION_OFFSET_BUCKETS = 8
		const val SMART_HISTORY_LIMIT = 25
		const val SMART_TAG_LIMIT = 8
		const val SMART_OFFSET_BUCKETS = 5
		const val RECENT_PAGE_SIZE = 10
		const val RECENT_PAGE_COUNT = 6
		const val RECENT_VISIBLE_LIMIT = RECENT_PAGE_SIZE * RECENT_PAGE_COUNT
		const val RECENT_CACHE_LIMIT = RECENT_VISIBLE_LIMIT
		const val RECENT_CHAPTERS_PER_TITLE = 3
		const val RECENT_CANDIDATES_PER_SOURCE = RECENT_VISIBLE_LIMIT
		const val RECENT_SOURCE_PAGE_ATTEMPTS = 8
		const val RECENT_PUBLISH_BATCH_SIZE = 3
		const val RECENT_SOURCE_TIMEOUT_MS = 12_000L
		const val RECENT_PAGE_TIMEOUT_MS = 3_000L
		const val RECENT_DETAILS_TIMEOUT_MS = 4_000L
		const val RECENT_SOURCE_PAGE_DELAY_MS = 350L
		const val RECENT_DETAILS_DELAY_MS = 40L
		const val HOME_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
		const val RECENT_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
		const val RECENT_CACHE_VERSION = 8
		const val FEATURED_ROTATION_MS = 3L * 24L * 60L * 60L * 1000L
		const val RECOMMENDATION_ROTATION_MS = 3L * 24L * 60L * 60L * 1000L
		const val SMART_RECOMMENDATION_ROTATION_MS = 2L * 24L * 60L * 60L * 1000L
		const val WEEBCENTRAL_HOME_URL = "https://weebcentral.com/"

		val CHAPTER_COMPARATOR = compareByDescending<MangaChapter> { it.uploadDate }
			.thenByDescending { it.number }

		val ASURA_SOURCES = listOf(
			MangaParserSource.ASURASCANS,
			MangaParserSource.ASURASCANS_US,
			MangaParserSource.ASURASCANSGG,
		)

		val RECENT_UPDATE_SOURCES = listOf(
			MangaParserSource.MANGAPLUSPARSER_EN,
			MangaParserSource.AQUAMANGA,
			MangaParserSource.ASURASCANS,
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

		val SMART_RECOMMENDATION_SOURCES = (
			MANHUA_RECOMMENDATION_SOURCES +
				MANGA_RECOMMENDATION_SOURCES +
				listOf(
					MangaParserSource.WEEBCENTRAL,
					MangaParserSource.FLAMECOMICS,
				)
			).distinct()
	}
}
