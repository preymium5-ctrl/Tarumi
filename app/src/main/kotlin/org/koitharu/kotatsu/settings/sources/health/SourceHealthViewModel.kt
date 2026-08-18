package org.koitharu.kotatsu.settings.sources.health

import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.SourceDiagnostics
import org.koitharu.kotatsu.core.parser.SourceDiagnosticsStore
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.explore.data.MangaSourceHealthInfo
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import javax.inject.Inject

@HiltViewModel
class SourceHealthViewModel @Inject constructor(
	repository: MangaSourcesRepository,
	private val diagnosticsStore: SourceDiagnosticsStore,
) : BaseViewModel() {

	val content: StateFlow<List<ListModel>> = combine(
		repository.observeEnglishSourceHealth(),
		diagnosticsStore.observeReports(),
	) { list, reports ->
			list.map { info ->
				val report = reports[info.source.name]
				SourceHealthItem(
					source = info.source,
					status = info.toStatus(report),
					isEnabled = info.isEnabled,
					isPinned = info.isPinned,
					diagnostics = report,
				)
			}
		}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))

	private fun MangaSourceHealthInfo.toStatus(report: SourceDiagnostics?): SourceHealthStatus = when {
		source.isBroken -> SourceHealthStatus.BROKEN
		cfState == CloudFlareHelper.PROTECTION_BLOCKED -> SourceHealthStatus.BLOCKED
		cfState == CloudFlareHelper.PROTECTION_CAPTCHA -> SourceHealthStatus.CAPTCHA
		report != null && report.consecutiveFailures >= 3 -> SourceHealthStatus.BROKEN
		report != null && report.missingChapters > 0 && report.detailLoads > 0 -> SourceHealthStatus.MISSING_CHAPTERS
		report != null && report.missingDetailsScore > 0 && report.detailLoads > 0 -> SourceHealthStatus.MISSING_DETAILS
		report?.isSlow == true -> SourceHealthStatus.SLOW
		isEnabled || source.isNsfw() -> SourceHealthStatus.HEALTHY
		else -> SourceHealthStatus.AVAILABLE
	}
}
