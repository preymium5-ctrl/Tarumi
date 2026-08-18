package org.koitharu.kotatsu.details.ui.pager.chapters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.dismissParentDialog
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.dialog.setCheckbox
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.PagerNestedScrollHelper
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.findParentCallback
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.FragmentChaptersBinding
import org.koitharu.kotatsu.details.ui.adapter.ChaptersAdapter
import org.koitharu.kotatsu.details.ui.adapter.ChaptersSelectionDecoration
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.details.ui.withVolumeHeaders
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.reader.ui.ReaderNavigationCallback

@AndroidEntryPoint
class ChaptersFragment :
	BaseFragment<FragmentChaptersBinding>(),
	OnListItemClickListener<ChapterListItem>,
	RecyclerViewOwner,
	ChipsView.OnChipClickListener {

	private val viewModel by ChaptersPagesViewModel.ActivityVMLazy(this)

	private var chaptersAdapter: ChaptersAdapter? = null
	private var selectionController: ListSelectionController? = null
	private var isFirstReverseEmission = true
	/** Always open the chapter list at the top (not mid-list / current chapter). */
	private var shouldScrollChaptersToTop = true
	private var pendingScrollToTop = true

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerViewChapters

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentChaptersBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentChaptersBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		// New view = always start at the first row (notifications / bookmarks / details).
		shouldScrollChaptersToTop = true
		pendingScrollToTop = true
		chaptersAdapter = ChaptersAdapter(this) { item ->
			onDownloadClick(item)
		}
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = ChaptersSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = ChaptersSelectionCallback(viewModel, router, binding.recyclerViewChapters),
		)
		viewModel.isChaptersInGridView.observe(viewLifecycleOwner) { chaptersInGridView ->
			binding.recyclerViewChapters.layoutManager = if (chaptersInGridView) {
				GridLayoutManager(context, ChapterGridSpanHelper.getSpanCount(binding.recyclerViewChapters)).apply {
					spanSizeLookup = ChapterGridSpanHelper.SpanSizeLookup(binding.recyclerViewChapters)
				}
			} else {
				LinearLayoutManager(context)
			}
		}
		with(binding.recyclerViewChapters) {
			addItemDecoration(TypedListSpacingDecoration(context, true))
			checkNotNull(selectionController).attachToRecyclerView(this)
			setHasFixedSize(true)
			PagerNestedScrollHelper(this).bind(viewLifecycleOwner)
			adapter = chaptersAdapter
			ChapterGridSpanHelper.attach(this)
		}
		binding.chipsFilter.onChipClickListener = this
		viewModel.isLoading.observe(viewLifecycleOwner, this::onLoadingStateChanged)
		viewModel.chapters
			.map { it.withVolumeHeaders(requireContext()) }
			.flowOn(Dispatchers.Default)
			.observe(viewLifecycleOwner, this::onChaptersChanged)
		viewModel.quickFilter.observe(viewLifecycleOwner, this::onFilterChanged)
		viewModel.isChaptersReversed.observe(viewLifecycleOwner) {
			if (isFirstReverseEmission) {
				isFirstReverseEmission = false
			} else {
				shouldScrollChaptersToTop = true
			}
		}
		viewModel.emptyReason.observe(viewLifecycleOwner) {
			binding.textViewHolder.setTextAndVisible(it?.msgResId ?: 0)
		}
	}

	override fun onDestroyView() {
		chaptersAdapter = null
		selectionController = null
		super.onDestroyView()
	}

	override fun onItemClick(item: ChapterListItem, view: View) {
		if (selectionController?.onItemClick(item.chapter.id) == true) {
			return
		}
		val listener = findParentCallback(ReaderNavigationCallback::class.java)
		if (listener != null && listener.onChapterSelected(item.chapter)) {
			dismissParentDialog()
		} else {
			router.openReader(
				ReaderIntent.Builder(view.context)
					.manga(viewModel.getMangaOrNull() ?: return)
					.branch(item.chapter.branch)
					.state(viewModel.getReaderStateForChapter(item.chapter.id))
					.build(),
			)
		}
	}

	private fun onDownloadClick(item: ChapterListItem) {
		if (viewModel.isAutoDownloadChapterEnabled) {
			router.askForDownloadOverMeteredNetwork { allow ->
				viewModel.download(setOf(item.chapter.id), allow)
			}
		} else {
			var dontAskAgain = false
			buildAlertDialog(requireContext()) {
				setTitle(R.string.download)
				setMessage(R.string.download_chapter_confirm)
				setCheckbox(R.string.dont_ask_again, false) { _, isChecked ->
					dontAskAgain = isChecked
				}
				setPositiveButton(R.string.download) { _, _ ->
					if (dontAskAgain) {
						viewModel.isAutoDownloadChapterEnabled = true
					}
					router.askForDownloadOverMeteredNetwork { allow ->
						viewModel.download(setOf(item.chapter.id), allow)
					}
				}
				setNegativeButton(android.R.string.cancel, null)
			}.show()
		}
	}

	override fun onItemLongClick(item: ChapterListItem, view: View): Boolean {
		return selectionController?.onItemLongClick(view, item.chapter.id) == true
	}

	override fun onItemContextClick(item: ChapterListItem, view: View): Boolean {
		return selectionController?.onItemContextClick(view, item.chapter.id) == true
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		if (data !is ListFilterOption.Branch) return
		viewModel.setSelectedBranch(data.titleText)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		viewBinding?.run {
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			recyclerViewChapters.updatePadding(
				left = bars.left,
				right = bars.right,
				bottom = bars.bottom,
			)
			chipsFilter.updatePadding(
				left = bars.left,
				right = bars.right,
			)
		}
		return WindowInsetsCompat.CONSUMED
	}

	private fun onChaptersChanged(list: List<ListModel>) {
		val adapter = chaptersAdapter ?: return
		// Always land on the first chapter row when the list opens or is rebuilt.
		// Opening from notifications / bookmarks previously left the list mid-scroll.
		val pinTop = shouldScrollChaptersToTop || pendingScrollToTop || adapter.itemCount == 0
		if (pinTop) {
			shouldScrollChaptersToTop = false
			pendingScrollToTop = false
			adapter.setItems(list) {
				scrollChaptersToTop()
			}
			return
		}
		adapter.items = list
	}

	override fun onResume() {
		super.onResume()
		if (pendingScrollToTop) {
			scrollChaptersToTop()
		}
	}

	private fun scrollChaptersToTop() {
		val rv = viewBinding?.recyclerViewChapters ?: return
		rv.stopScroll()
		rv.post {
			rv.scrollToPosition(0)
			(rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
			// Second pass after layout (covers delayed header/item measure).
			rv.post {
				rv.scrollToPosition(0)
				(rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
			}
		}
	}

	private fun onFilterChanged(list: List<ChipsView.ChipModel>) {
		viewBinding?.chipsFilter?.run {
			setChips(list)
			isGone = list.isEmpty()
		}
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		requireViewBinding().progressBar.isVisible = isLoading
	}
}
