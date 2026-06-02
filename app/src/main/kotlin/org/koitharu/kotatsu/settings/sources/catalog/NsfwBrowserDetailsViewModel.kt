package org.koitharu.kotatsu.settings.sources.catalog

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class NsfwBrowserDetailsViewModel @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

	private val seed = checkNotNull(MangaIntent(savedStateHandle).manga) {
		"Manga is required for browser details"
	}
	private val _state = MutableStateFlow(NsfwBrowserDetailsState(manga = seed, isLoading = true))
	val state: StateFlow<NsfwBrowserDetailsState> = _state

	init {
		load()
	}

	fun retry() {
		load()
	}

	private fun load() {
		launchJob(Dispatchers.Default) {
			_state.value = _state.value.copy(isLoading = true, error = null)
			val repository = mangaRepositoryFactory.create(seed.source)
			val result = runCatchingCancellable {
				val details = repository.getDetails(seed)
				val chapter = details.chapters.orEmpty().firstOrNull()
				val pages = chapter?.let { repository.getPages(it) }.orEmpty()
				NsfwBrowserDetailsState(
					manga = details,
					pages = pages,
					isLoading = false,
					error = null,
				)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrElse { error ->
				NsfwBrowserDetailsState(
					manga = _state.value.manga,
					pages = emptyList(),
					isLoading = false,
					error = error,
				)
			}
			_state.value = result
		}
	}
}

data class NsfwBrowserDetailsState(
	val manga: Manga,
	val pages: List<MangaPage> = emptyList(),
	val isLoading: Boolean = false,
	val error: Throwable? = null,
)
