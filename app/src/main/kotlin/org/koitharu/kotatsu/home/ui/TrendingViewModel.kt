package org.koitharu.kotatsu.home.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class TrendingViewModel @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) : BaseViewModel() {

	private val _items = MutableStateFlow<List<Manga>>(emptyList())
	val items: StateFlow<List<Manga>> = _items.asStateFlow()

	private val _isInitialLoading = MutableStateFlow(true)
	val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

	private val _isPaging = MutableStateFlow(false)
	val isPaging: StateFlow<Boolean> = _isPaging.asStateFlow()

	private val _isExhausted = MutableStateFlow(false)
	val isExhausted: StateFlow<Boolean> = _isExhausted.asStateFlow()

	private val seenIds = HashSet<Long>()
	private var sourceIndex = 0
	private var sourceOffset = 0
	private var loadJob: Job? = null

	init {
		if (!restoreCache()) {
			loadInitial()
		}
	}

	fun loadInitial() {
		if (loadJob?.isActive == true) {
			return
		}
		loadJob = launchJob(Dispatchers.Default) {
			seenIds.clear()
			sourceIndex = 0
			sourceOffset = 0
			_isExhausted.value = false
			_items.value = emptyList()
			_isInitialLoading.value = true
			val firstPage = loadNext(INITIAL_PAGE_SIZE)
			_items.value = firstPage
			storeCache(firstPage)
			_isInitialLoading.value = false
			if (firstPage.isEmpty()) {
				_isExhausted.value = true
				storeCache(firstPage)
			}
		}
	}

	fun loadMore() {
		if (loadJob?.isActive == true || _isExhausted.value) {
			return
		}
		loadJob = launchJob(Dispatchers.Default) {
			_isPaging.value = true
			val next = loadNext(PAGE_SIZE)
			if (next.isEmpty()) {
				_isExhausted.value = true
			} else {
				_items.value = _items.value + next
			}
			storeCache(_items.value)
			_isPaging.value = false
		}
	}

	private fun restoreCache(): Boolean {
		if (cachedItems.isEmpty()) {
			return false
		}
		seenIds.clear()
		seenIds.addAll(cachedSeenIds)
		sourceIndex = cachedSourceIndex
		sourceOffset = cachedSourceOffset
		_isExhausted.value = cachedIsExhausted
		_items.value = cachedItems
		_isInitialLoading.value = false
		return true
	}

	private fun storeCache(items: List<Manga>) {
		cachedItems = items
		cachedSeenIds = HashSet(seenIds)
		cachedSourceIndex = sourceIndex
		cachedSourceOffset = sourceOffset
		cachedIsExhausted = _isExhausted.value
	}

	private suspend fun loadNext(target: Int): List<Manga> {
		val collected = ArrayList<Manga>(target)
		while (collected.size < target && sourceIndex < ASURA_SOURCES.size) {
			val source = ASURA_SOURCES[sourceIndex]
			val repository = mangaRepositoryFactory.create(source)
			val order = when {
				SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
				SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
				else -> repository.defaultSortOrder
			}
			val page = runCatchingCancellable {
				repository.getList(sourceOffset, order, MangaListFilter.EMPTY)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(emptyList())
			if (page.isEmpty()) {
				sourceIndex++
				sourceOffset = 0
				continue
			}
			sourceOffset += page.size
			for (manga in page) {
				if (seenIds.add(manga.id)) {
					collected.add(loadDetailsForCover(manga, repository))
					if (collected.size >= target) {
						break
					}
				}
			}
		}
		return collected
	}

	private suspend fun loadDetailsForCover(manga: Manga, repository: org.koitharu.kotatsu.core.parser.MangaRepository): Manga {
		if (!manga.coverUrl.isNullOrEmpty() || !manga.largeCoverUrl.isNullOrEmpty()) {
			return manga
		}
		return runCatchingCancellable {
			repository.getDetails(manga)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(manga)
	}

	private companion object {
		const val INITIAL_PAGE_SIZE = 16
		const val PAGE_SIZE = 20

		var cachedItems: List<Manga> = emptyList()
		var cachedSeenIds: HashSet<Long> = HashSet()
		var cachedSourceIndex: Int = 0
		var cachedSourceOffset: Int = 0
		var cachedIsExhausted: Boolean = false

		val ASURA_SOURCES = listOf(
			MangaParserSource.ASURASCANS,
			MangaParserSource.ASURASCANS_US,
			MangaParserSource.ASURASCANSGG,
		)
	}
}
