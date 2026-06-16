package org.koitharu.kotatsu.stats.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetOtherStatsBinding
import org.koitharu.kotatsu.databinding.ItemOtherStatsMangaBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.stats.data.StatsRepository
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.StatsRecord
import javax.inject.Inject

@HiltViewModel
class OtherStatsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: StatsRepository,
) : BaseViewModel() {

	val periodOrdinal = savedStateHandle.get<Int>(KEY_PERIOD_ORDINAL) ?: StatsPeriod.WEEK.ordinal
	val categories = savedStateHandle.get<LongArray>(KEY_CATEGORIES)?.toSet().orEmpty()

	val otherStats = MutableStateFlow<List<StatsRecord>>(emptyList())

	init {
		val period = StatsPeriod.entries.getOrNull(periodOrdinal) ?: StatsPeriod.WEEK
		launchLoadingJob(Dispatchers.Default) {
			otherStats.value = repository.getOtherReadingStats(period, categories)
		}
	}

	companion object {
		const val KEY_PERIOD_ORDINAL = "period_ordinal"
		const val KEY_CATEGORIES = "categories"
	}
}

@AndroidEntryPoint
class OtherStatsSheet : BaseAdaptiveSheet<SheetOtherStatsBinding>() {

	private val viewModel: OtherStatsViewModel by viewModels()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetOtherStatsBinding {
		return SheetOtherStatsBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetOtherStatsBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = OtherStatsMangaAdapter { manga ->
			dismiss()
			router.showStatisticSheet(manga)
		}
		binding.recyclerView.adapter = adapter
		viewModel.otherStats.observe(viewLifecycleOwner) { records ->
			adapter.submitList(records)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.recyclerView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}
}

class OtherStatsMangaAdapter(
	private val onClick: (Manga) -> Unit,
) : RecyclerView.Adapter<OtherStatsMangaAdapter.ViewHolder>() {

	private var items: List<StatsRecord> = emptyList()

	fun submitList(newItems: List<StatsRecord>) {
		items = newItems
		notifyDataSetChanged()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val binding = ItemOtherStatsMangaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = items[position]
		holder.bind(item)
	}

	override fun getItemCount(): Int = items.size

	inner class ViewHolder(private val binding: ItemOtherStatsMangaBinding) : RecyclerView.ViewHolder(binding.root) {
		fun bind(item: StatsRecord) {
			val manga = item.manga ?: return
			binding.textViewTitle.text = manga.title
			binding.textViewSummary.text = binding.root.context.getString(
				R.string.stats_manga_summary_pattern,
				item.time.format(binding.root.resources),
				item.chapters,
			)
			binding.imageViewCover.setImageAsync(manga.coverUrl, manga)
			binding.root.setOnClickListener {
				onClick(manga)
			}
		}
	}
}
