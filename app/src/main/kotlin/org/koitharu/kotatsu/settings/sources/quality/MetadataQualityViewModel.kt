package org.koitharu.kotatsu.settings.sources.quality

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.parser.SourceDiagnosticsStore
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class MetadataQualityViewModel @Inject constructor(
	repository: MangaSourcesRepository,
	diagnosticsStore: SourceDiagnosticsStore,
) : BaseViewModel() {

	private val sources = repository.allMangaSources
		.filter { it.locale == "en" }
		.sortedBy { it.title }

	val content: StateFlow<List<ListModel>> = diagnosticsStore.observeReports()
		.map { reports ->
			sources
				.map { source -> MetadataQualityItem(source, reports[source.name]) }
				.sortedWith(
					compareByDescending<MetadataQualityItem> { it.report != null }
						.thenBy { it.report?.qualityPercent ?: -1 }
						.thenBy { it.source.title },
				)
		}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))
}
