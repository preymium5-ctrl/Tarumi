package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class NsfwBrowserModeViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val sourcesRepository: MangaSourcesRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

	private val _state = MutableStateFlow(NsfwBrowserModeState())
	val state: StateFlow<NsfwBrowserModeState> = _state
	private val pageSignatures = HashMap<Int, Set<String>>()
	private var loadJob: Job? = null
	private var tagsJob: Job? = null
	private val initialSourceName = savedStateHandle.get<String>(EXTRA_SOURCE)
	private val initialQuery = savedStateHandle.get<String>(EXTRA_QUERY).orEmpty()

	init {
		val sources = sourcesRepository.allMangaSources
			.asSequence()
			.filter { it.contentType == ContentType.HENTAI && it.isEnglishSource() }
			.sortedBy { it.getTitle(context) }
			.toList()
		val selected = sources.firstOrNull { it.name == initialSourceName } ?: sources.firstOrNull()
		_state.value = _state.value.copy(
			sources = sources,
			selectedSource = selected,
			query = initialQuery,
			knownPageCount = if (sources.isEmpty()) 0 else 1,
		)
		if (sources.isNotEmpty()) {
			loadAvailableTags()
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
			availableTags = emptyList(),
			selectedTags = emptyList(),
			error = null,
		)
		pageSignatures.clear()
		loadAvailableTags()
		loadPage(page = 0)
	}

	fun setQuery(query: String) {
		val normalized = query.trim()
		val current = _state.value
		if (current.query == normalized) {
			return
		}
		_state.value = current.copy(
			query = normalized,
			items = emptyList(),
			page = 0,
			knownPageCount = if (current.selectedSource == null) 0 else 1,
			hasNext = false,
			selectedTags = emptyList(),
			error = null,
		)
		pageSignatures.clear()
		loadPage(page = 0)
	}

	fun selectTag(tag: MangaTag?) {
		val current = _state.value
		val selectedTags = when {
			tag == null -> emptyList()
			current.selectedTags.any { it.key == tag.key && it.source == tag.source } -> current.selectedTags
			else -> current.selectedTags + tag
		}
		if (current.selectedTags == selectedTags) {
			return
		}
		_state.value = current.copy(
			selectedTags = selectedTags,
			query = selectedTags.joinToString(", ") { it.title.ifBlank { it.key } },
			items = emptyList(),
			page = 0,
			knownPageCount = if (current.selectedSource == null) 0 else 1,
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
		loadJob?.cancel()
		loadJob = launchJob(Dispatchers.Default) {
			val query = _state.value.query
			val selectedTags = _state.value.selectedTags
			_state.value = _state.value.copy(isLoading = true, error = null, page = page)
			val repository = mangaRepositoryFactory.create(source)
			val order = when {
				SortOrder.NEWEST in repository.sortOrders -> SortOrder.NEWEST
				SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
				SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
				else -> repository.defaultSortOrder
			}
			val pageResult = runCatchingCancellable {
				fetchPage(repository, order, page, query, selectedTags)
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
				.filter { selectedTags.isNotEmpty() || it.matchesBrowserQuery(query) }
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
		query: String,
		selectedTags: List<MangaTag>,
	): BrowserPageResult {
		val previousSignature = pageSignatures[page - 1]
		val filter = if (selectedTags.isNotEmpty()) {
			MangaListFilter(tags = selectedTags.toSet())
		} else if (query.isBlank()) {
			MangaListFilter.EMPTY
		} else {
			MangaListFilter(query = query)
		}
		val offsets = buildOffsetCandidates(page)
		var fallback: BrowserPageResult? = null
		for (offset in offsets) {
			val raw = repository.getList(offset, order, filter)
			val window = when {
				raw.size > PAGE_SIZE -> raw.take(PAGE_SIZE)
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

	private fun loadAvailableTags() {
		val source = _state.value.selectedSource ?: return
		tagsJob?.cancel()
		tagsJob = launchJob(Dispatchers.Default) {
			val repository = mangaRepositoryFactory.create(source)
			val tags = runCatchingCancellable {
				repository.getFilterOptions().availableTags
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(emptySet())
				.sortedWith(compareBy<MangaTag> { it.title.lowercase() }.thenBy { it.key })
			_state.value = _state.value.copy(availableTags = tags)
		}
	}

	private fun buildOffsetCandidates(page: Int): List<Int> {
		if (page <= 0) {
			return listOf(0)
		}
		return listOf(
			page * PAGE_SIZE,
			page * SOURCE_PAGE_HINT,
			page,
			page + 1,
			(page * PAGE_SIZE) + 1,
			(page * SOURCE_PAGE_HINT) + 1,
		).filter { it >= 0 }.distinct()
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

	private fun Manga.matchesBrowserQuery(query: String): Boolean {
		if (query.isBlank()) {
			return true
		}
		return title.contains(query, ignoreCase = true) ||
			altTitles.any { it.contains(query, ignoreCase = true) } ||
			authors.any { it.contains(query, ignoreCase = true) } ||
			tags.any { tag ->
				tag.title.contains(query, ignoreCase = true) ||
					tag.key.contains(query, ignoreCase = true)
			}
	}

	private fun MangaParserSource.isEnglishSource(): Boolean {
		return locale.equals("en", ignoreCase = true) ||
			locale.startsWith("en-", ignoreCase = true) ||
			locale.startsWith("en_", ignoreCase = true)
	}

	companion object {
		const val EXTRA_SOURCE = "browser_source"
		const val EXTRA_QUERY = "browser_query"
		const val PAGE_SIZE = 25
		private const val SOURCE_PAGE_HINT = 50
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
	val query: String = "",
	val availableTags: List<MangaTag> = emptyList(),
	val selectedTags: List<MangaTag> = emptyList(),
	val page: Int = 0,
	val knownPageCount: Int = 0,
	val hasNext: Boolean = false,
	val isLoading: Boolean = false,
	val error: Throwable? = null,
)
