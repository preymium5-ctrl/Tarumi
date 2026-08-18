package org.koitharu.kotatsu.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject

@HiltViewModel
class ContinueReadingViewModel @Inject constructor(
	historyRepository: HistoryRepository,
) : ViewModel() {

	val items = historyRepository.observeAllWithHistory(
		order = ListSortOrder.LAST_READ,
		filterOptions = emptySet(),
		limit = CONTINUE_READING_GRID_LIMIT,
	).stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	private companion object {
		const val CONTINUE_READING_GRID_LIMIT = 300
	}
}
