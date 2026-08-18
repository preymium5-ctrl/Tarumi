package org.koitharu.kotatsu.tracker.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.list.PaginationScrollListener
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.FragmentFeedBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.adapter.MangaListListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.StaticItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.tracker.ui.feed.adapter.FeedAdapter
import javax.inject.Inject

@AndroidEntryPoint
class FeedFragment :
	BaseFragment<FragmentFeedBinding>(),
	PaginationScrollListener.Callback,
	RecyclerViewOwner,
	MangaListListener,
	SwipeRefreshLayout.OnRefreshListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<FeedViewModel>()

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentFeedBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentFeedBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width))
		val feedAdapter = FeedAdapter(this, sizeResolver) { item, v ->
			viewModel.onItemClick(item)
			router.openDetails(item.toMangaWithOverride())
		}
		with(binding.recyclerView) {
			val paddingVertical = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
			setPadding(0, paddingVertical, 0, paddingVertical)
			layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
			adapter = feedAdapter
			setHasFixedSize(true)
			addOnScrollListener(PaginationScrollListener(4, this@FeedFragment))
			addItemDecoration(TypedListSpacingDecoration(context, true))
			RecyclerScrollKeeper(this).attach()
		}
		binding.buttonFilter.setOnClickListener {
			binding.recyclerView.smoothScrollToPosition(0)
		}
		binding.buttonMarkAllRead.setOnClickListener {
			viewModel.markAllAsRead()
		}
		binding.swipeRefreshLayout.setOnRefreshListener(this)
		addMenuProvider(FeedMenuProvider(binding.recyclerView, viewModel))

		viewModel.isHeaderEnabled.drop(1).observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
		viewModel.unreadCount.observe(viewLifecycleOwner) { count ->
			binding.textViewUnreadCount.isVisible = count > 0
			binding.textViewUnreadCount.text = getString(R.string.notification_new_count, count)
			binding.buttonMarkAllRead.isEnabled = count > 0
		}
		viewModel.content.observe(viewLifecycleOwner, feedAdapter)
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.recyclerView, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
		viewModel.isRunning.observe(viewLifecycleOwner, this::onIsTrackerRunningChanged)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		val density = resources.displayMetrics.density
		fun dp(value: Int) = (value * density + 0.5f).toInt()
		viewBinding?.run {
			// Keep title / filter row clear of status bar and cutouts on all devices.
			notificationsHeader.setPadding(
				dp(16) + barsInsets.left,
				dp(8) + barsInsets.top,
				dp(16) + barsInsets.right,
				dp(10),
			)
			recyclerView.setPadding(
				barsInsets.left,
				dp(4),
				barsInsets.right,
				barsInsets.bottom + dp(16),
			)
		}
		return insets.consumeAll(typeMask)
	}

	override fun onRefresh() {
		viewModel.update()
	}

	override fun onFilterOptionClick(option: ListFilterOption) = viewModel.toggleFilterOption(option)

	override fun onRetryClick(error: Throwable) = Unit

	override fun onFilterClick(view: View?) = Unit

	override fun onEmptyActionClick() = Unit

	override fun onPrimaryButtonClick(tipView: TipView) = Unit

	override fun onSecondaryButtonClick(tipView: TipView) = Unit

	override fun onListHeaderClick(item: ListHeader, view: View) {
		router.openMangaUpdates()
	}

	private fun onIsTrackerRunningChanged(isRunning: Boolean) {
		requireViewBinding().swipeRefreshLayout.isRefreshing = isRunning
	}

	override fun onScrolledToEnd() {
		viewModel.requestMoreItems()
	}

	override fun onItemClick(item: MangaListModel, view: View) {
		router.openDetails(item.toMangaWithOverride())
	}

	override fun onReadClick(manga: Manga, view: View) = Unit

	override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
}
