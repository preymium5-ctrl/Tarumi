package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class NsfwBrowserModeViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val sourcesRepository: MangaSourcesRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) : BaseViewModel() {

	private val _state = MutableStateFlow(NsfwBrowserModeState())
	val state: StateFlow<NsfwBrowserModeState> = _state
	private val pageSignatures = HashMap<Int, Set<String>>()

	init {
		val sources = sourcesRepository.allMangaSources
			.asSequence()
			.filter { it.contentType == ContentType.HENTAI }
			.sortedBy { it.getTitle(context) }
			.toList()
		_state.value = _state.value.copy(
			sources = sources,
			selectedSource = sources.firstOrNull(),
			knownPageCount = if (sources.isEmpty()) 0 else 1,
		)
		if (sources.isNotEmpty()) {
			loadPage(page = 0)
		}
	}

	fun selectSource(source: MangaParserSource) {
		val current = _state.value
		if (current.selectedSource == source) {
			return
		}
		_state.value = current.copy(
			selectedSource = source,
			items = emptyList(),
			page = 0,
			knownPageCount = 1,
			hasNext = false,
			error = null,
		)
		pageSignatures.clear()
		loadPage(page = 0)
	}

	fun nextPage() {
		val current = _state.value
		if (!current.hasNext || current.isLoading) {
			return
		}
		loadPage(current.page + 1)
	}

	fun previousPage() {
		val current = _state.value
		if (current.page <= 0 || current.isLoading) {
			return
		}
		loadPage(current.page - 1)
	}

	fun retry() {
		loadPage(_state.value.page)
	}

	private fun loadPage(page: Int) {
		val source = _state.value.selectedSource ?: return
		launchJob(Dispatchers.Default) {
			_state.value = _state.value.copy(isLoading = true, error = null)
			val repository = mangaRepositoryFactory.create(source)
			val order = when {
				SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
				SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
				else -> repository.defaultSortOrder
			}
			val pageResult = runCatchingCancellable {
				fetchPage(repository, order, page)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrElse { error ->
				_state.value = _state.value.copy(
					isLoading = false,
					error = error,
					items = emptyList(),
					hasNext = false,
				)
				return@launchJob
			}
			pageSignatures[page] = pageResult.signature
			val visibleManga = hydrateDetails(repository, pageResult.items)
			_state.value = _state.value.copy(
				isLoading = false,
				items = visibleManga,
				page = page,
				knownPageCount = maxOf(_state.value.knownPageCount, page + if (pageResult.hasNext) 2 else 1),
				hasNext = pageResult.hasNext,
				error = null,
			)
		}
	}

	private suspend fun fetchPage(
		repository: MangaRepository,
		order: SortOrder,
		page: Int,
	): BrowserPageResult {
		val previousSignature = pageSignatures[page - 1]
		val offsets = if (page == 0) {
			listOf(0)
		} else {
			listOf(
				page * PAGE_SIZE,
				page,
				page + 1,
				(page * PAGE_SIZE) + 1,
			).distinct()
		}
		var fallback: BrowserPageResult? = null
		for (offset in offsets) {
			val raw = repository.getList(offset, order, MangaListFilter.EMPTY)
			val window = when {
				raw.size > PAGE_SIZE && offset >= PAGE_SIZE -> raw.drop(offset).take(PAGE_SIZE)
				else -> raw.take(PAGE_SIZE)
			}
			val result = BrowserPageResult(
				items = window,
				signature = window.mapTo(LinkedHashSet()) { it.url.ifEmpty { it.id.toString() } },
				hasNext = raw.size >= PAGE_SIZE,
			)
			if (fallback == null) {
				fallback = result
			}
			if (result.items.isNotEmpty() && result.signature != previousSignature) {
				return result
			}
		}
		return fallback?.copy(hasNext = false) ?: BrowserPageResult(emptyList(), emptySet(), false)
	}

	private suspend fun hydrateDetails(repository: MangaRepository, manga: List<Manga>): List<Manga> = coroutineScope {
		manga.chunked(DETAILS_CHUNK_SIZE).flatMap { chunk ->
			chunk.map { item ->
				async {
					runCatchingCancellable { repository.getDetails(item) }
						.onFailure { it.printStackTraceDebug() }
						.getOrDefault(item)
				}
			}.awaitAll()
		}
	}

	companion object {
		const val PAGE_SIZE = 25
		private const val DETAILS_CHUNK_SIZE = 5
	}
}

private data class BrowserPageResult(
	val items: List<Manga>,
	val signature: Set<String>,
	val hasNext: Boolean,
)

data class NsfwBrowserModeState(
	val sources: List<MangaParserSource> = emptyList(),
	val selectedSource: MangaParserSource? = null,
	val items: List<Manga> = emptyList(),
	val page: Int = 0,
	val knownPageCount: Int = 0,
	val hasNext: Boolean = false,
	val isLoading: Boolean = false,
	val error: Throwable? = null,
)
