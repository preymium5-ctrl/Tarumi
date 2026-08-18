package org.koitharu.kotatsu.home.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivityTrendingBinding
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.parsers.model.Manga

@AndroidEntryPoint
class TrendingActivity : BaseActivity<ActivityTrendingBinding>() {

	private val viewModel: TrendingViewModel by viewModels()
	private lateinit var adapter: TrendingAdapter

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityTrendingBinding.inflate(layoutInflater))

		setSupportActionBar(viewBinding.toolbar)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		title = getString(R.string.trending_title)

		adapter = TrendingAdapter { manga -> router.openDetails(manga) }
		val layoutManager = GridLayoutManager(this, COLUMNS)
		layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
			override fun getSpanSize(position: Int): Int {
				return if (adapter.getItemViewType(position) == TrendingAdapter.TYPE_LOADING) COLUMNS else 1
			}
		}
		viewBinding.recyclerView.layoutManager = layoutManager
		viewBinding.recyclerView.adapter = adapter
		viewBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
				if (dy <= 0) return
				val total = layoutManager.itemCount
				val lastVisible = layoutManager.findLastVisibleItemPosition()
				if (lastVisible >= total - PREFETCH_AHEAD) {
					viewModel.loadMore()
				}
			}
		})

		viewBinding.swipeRefresh.setOnRefreshListener {
			viewModel.loadInitial()
		}

		viewModel.isInitialLoading.observe(this) { loading ->
			viewBinding.initialLoading.isVisible = loading && adapter.itemCount == 0
			if (!loading) {
				viewBinding.swipeRefresh.isRefreshing = false
			}
		}
		viewModel.items.observe(this) { adapter.submitItems(it) }
		viewModel.isPaging.observe(this) { adapter.setShowFooter(it) }
		viewModel.isExhausted.observe(this) { adapter.setShowFooter(adapter.isFooterVisible && !it) }
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
		viewBinding.recyclerView.updatePadding(
			left = bars.left + viewBinding.recyclerView.paddingLeft.coerceAtLeast(0),
			right = bars.right + viewBinding.recyclerView.paddingRight.coerceAtLeast(0),
			bottom = bars.bottom + 20.dp(v),
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	private fun Int.dp(view: View): Int = (this * view.resources.displayMetrics.density).toInt()

	private companion object {
		const val COLUMNS = 2
		const val PREFETCH_AHEAD = 6
	}
}

private class TrendingAdapter(
	private val onClick: (Manga) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

	private val items = ArrayList<Manga>()
	var isFooterVisible: Boolean = false
		private set

	fun submitItems(newItems: List<Manga>) {
		val oldSize = items.size
		if (newItems.size > oldSize && newItems.subList(0, oldSize) == items) {
			val added = newItems.size - oldSize
			items.addAll(newItems.subList(oldSize, newItems.size))
			notifyItemRangeInserted(footerPositionBefore(oldSize), added)
		} else {
			items.clear()
			items.addAll(newItems)
			notifyDataSetChanged()
		}
	}

	fun setShowFooter(visible: Boolean) {
		if (isFooterVisible == visible) return
		isFooterVisible = visible
		if (visible) {
			notifyItemInserted(items.size)
		} else {
			notifyItemRemoved(items.size)
		}
	}

	override fun getItemCount(): Int = items.size + if (isFooterVisible) 1 else 0

	override fun getItemViewType(position: Int): Int =
		if (position >= items.size) TYPE_LOADING else TYPE_ITEM

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return if (viewType == TYPE_LOADING) {
			LoadingHolder(inflater.inflate(R.layout.item_trending_loading, parent, false))
		} else {
			ItemHolder(inflater.inflate(R.layout.item_trending, parent, false), onClick)
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		if (holder is ItemHolder) {
			holder.bind(items[position])
		}
	}

	private fun footerPositionBefore(oldDataSize: Int): Int = oldDataSize

	private class ItemHolder(view: View, val onClick: (Manga) -> Unit) : RecyclerView.ViewHolder(view) {
		private val cover: CoverImageView = view.findViewById(R.id.imageView_cover)
		private val title: TextView = view.findViewById(R.id.textView_title)

		fun bind(manga: Manga) {
			title.text = manga.title
			itemView.contentDescription = manga.title
			cover.setImageAsync(manga.largeCoverUrl?.ifEmpty { manga.coverUrl } ?: manga.coverUrl, manga)
			itemView.setOnClickListener { onClick(manga) }
		}
	}

	private class LoadingHolder(view: View) : RecyclerView.ViewHolder(view) {
		init {
			view.layoutParams = (view.layoutParams as? FrameLayout.LayoutParams)
				?: view.layoutParams
		}
	}

	companion object {
		const val TYPE_ITEM = 0
		const val TYPE_LOADING = 1
	}
}
