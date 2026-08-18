package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.databinding.ActivityNsfwBrowserModeBinding
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag

@AndroidEntryPoint
class NsfwBrowserModeActivity :
	BaseActivity<ActivityNsfwBrowserModeBinding>(),
	AppBarOwner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<NsfwBrowserModeViewModel>()
	private lateinit var adapter: BrowserEntryAdapter
	private var renderedPage = -1

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityNsfwBrowserModeBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		title = getString(R.string.browser_mode)

		adapter = BrowserEntryAdapter(
			onClick = { manga -> router.openNsfwBrowserDetails(manga) },
			onTagClick = { tag -> viewModel.selectTag(tag) },
			onPreviousPage = { viewModel.previousPage() },
			onNextPage = { viewModel.nextPage() },
		)
		viewBinding.recyclerView.layoutManager = GridLayoutManager(this, BROWSER_GRID_COLUMNS).apply {
			spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
				override fun getSpanSize(position: Int): Int {
					return if (adapter.getItemViewType(position) == BrowserEntryAdapter.VIEW_TYPE_PAGER) {
						BROWSER_GRID_COLUMNS
					} else {
						1
					}
				}
			}
		}
		viewBinding.recyclerView.adapter = adapter
		viewBinding.buttonSource.setOnClickListener { showSourceMenu(it) }
		viewBinding.buttonFilter.setOnClickListener { showTagMenu(it) }
		viewBinding.buttonPrev.setOnClickListener { viewModel.previousPage() }
		viewBinding.buttonNext.setOnClickListener { viewModel.nextPage() }
		viewBinding.editTextSearch.doAfterTextChanged { text ->
			viewModel.setQuery(text?.toString().orEmpty())
		}

		viewModel.state.observe(this, this::render)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		viewBinding.recyclerView.updatePadding(
			left = bars.left + 20.dp(),
			right = bars.right + 20.dp(),
			bottom = bars.bottom + 104.dp(),
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	private fun render(state: NsfwBrowserModeState) {
		val selected = state.selectedSource
		val canPrevious = state.page > 0 && !state.isLoading
		val canNext = state.hasNext && !state.isLoading
		val pageWindow = buildPageWindow(state)
		viewBinding.buttonSource.text = selected?.getTitle(this) ?: getString(R.string.select_source)
		viewBinding.textBrowserHeading.text = state.query.takeIf { it.isNotBlank() }
			?: getString(R.string.browser_recently_added)
		viewBinding.buttonFilter.isEnabled = state.availableTags.isNotEmpty()
		viewBinding.buttonFilter.alpha = if (state.availableTags.isNotEmpty()) 1f else 0.45f
		if (viewBinding.editTextSearch.text?.toString() != state.query) {
			viewBinding.editTextSearch.setText(state.query)
			viewBinding.editTextSearch.setSelection(state.query.length)
		}
		adapter.submitItems(state.items, state.page, pageWindow, canPrevious, canNext)
		if (state.page != renderedPage) {
			renderedPage = state.page
			viewBinding.recyclerView.scrollToPosition(0)
		}
		viewBinding.progress.isVisible = state.isLoading
		viewBinding.recyclerView.alpha = if (state.isLoading && state.items.isNotEmpty()) 0.45f else 1f
		viewBinding.textEmpty.isVisible = !state.isLoading && state.items.isEmpty()
		viewBinding.textEmpty.text = when {
			state.sources.isEmpty() -> getString(R.string.no_browser_sources)
			state.error != null -> state.error.localizedMessage ?: state.error.javaClass.simpleName
			else -> getString(R.string.no_browser_comics)
		}
		viewBinding.buttonPrev.isEnabled = canPrevious
		viewBinding.buttonPrev.alpha = if (canPrevious) 1f else 0.35f
		viewBinding.buttonNext.isEnabled = canNext
		viewBinding.buttonNext.alpha = if (canNext) 1f else 0.35f
		viewBinding.textPage.text = pageWindow
	}

	private fun buildPageWindow(state: NsfwBrowserModeState): String {
		val current = state.page + 1
		val known = state.knownPageCount.coerceAtLeast(current)
		return when {
			known <= 1 -> current.toString()
			known <= 4 -> (1..known).joinToString("   ")
			current <= 3 -> "1   2   3 ... $known"
			current >= known - 1 -> "1 ... ${known - 2}   ${known - 1}   $known"
			else -> "1 ... $current ... $known"
		}
	}

	private fun showSourceMenu(anchor: View) {
		val sources = viewModel.state.value.sources
		if (sources.isEmpty()) {
			return
		}
		val popup = PopupMenu(this, anchor)
		for ((index, source) in sources.withIndex()) {
			popup.menu.add(Menu.NONE, index, index, source.getTitle(this))
		}
		popup.setOnMenuItemClickListener { item ->
			sources.getOrNull(item.itemId)?.let(viewModel::selectSource)
			true
		}
		popup.show()
	}

	private fun showTagMenu(anchor: View) {
		val tags = viewModel.state.value.availableTags
		if (tags.isEmpty()) {
			return
		}
		val popup = PopupMenu(this, anchor)
		popup.menu.add(Menu.NONE, MENU_ALL_TAGS, 0, getString(R.string.browser_all_tags))
		for ((index, tag) in tags.withIndex()) {
			popup.menu.add(Menu.NONE, index, index + 1, tag.title.ifBlank { tag.key })
		}
		popup.setOnMenuItemClickListener { item ->
			if (item.itemId == MENU_ALL_TAGS) {
				viewModel.selectTag(null)
			} else {
				tags.getOrNull(item.itemId)?.let(viewModel::selectTag)
			}
			true
		}
		popup.show()
	}

	private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

	private companion object {
		const val MENU_ALL_TAGS = -1
		const val BROWSER_GRID_COLUMNS = 3
	}
}

private class BrowserEntryAdapter(
	private val onClick: (Manga) -> Unit,
	private val onTagClick: (MangaTag) -> Unit,
	private val onPreviousPage: () -> Unit,
	private val onNextPage: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

	private val items = ArrayList<Manga>()
	private var page = 0
	private var pageText = "1"
	private var canPrevious = false
	private var canNext = false

	fun submitItems(
		newItems: List<Manga>,
		page: Int,
		pageText: String,
		canPrevious: Boolean,
		canNext: Boolean,
	) {
		this.page = page
		this.pageText = pageText
		this.canPrevious = canPrevious
		this.canNext = canNext
		items.clear()
		items.addAll(newItems)
		notifyDataSetChanged()
	}

	override fun getItemViewType(position: Int): Int = if (position < items.size) VIEW_TYPE_ENTRY else VIEW_TYPE_PAGER

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return when (viewType) {
			VIEW_TYPE_ENTRY -> Holder(inflater.inflate(R.layout.item_nsfw_browser_entry, parent, false), onClick, onTagClick)
			VIEW_TYPE_PAGER -> PagerHolder(
				view = inflater.inflate(R.layout.item_nsfw_browser_pager, parent, false),
				onPreviousPage = onPreviousPage,
				onNextPage = onNextPage,
			)
			else -> error("Unknown view type: $viewType")
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		when (holder) {
			is Holder -> holder.bind(items[position], page)
			is PagerHolder -> holder.bind(pageText, canPrevious, canNext)
		}
	}

	override fun getItemCount(): Int = items.size + if (items.isNotEmpty()) 1 else 0

	class PagerHolder(
		view: View,
		onPreviousPage: () -> Unit,
		onNextPage: () -> Unit,
	) : RecyclerView.ViewHolder(view) {

		private val pageText: TextView = view.findViewById(R.id.text_bottom_page)
		private val previous: MaterialButton = view.findViewById(R.id.button_bottom_prev)
		private val next: MaterialButton = view.findViewById(R.id.button_bottom_next)

		init {
			previous.setOnClickListener { onPreviousPage() }
			next.setOnClickListener { onNextPage() }
		}

		fun bind(text: String, canPrevious: Boolean, canNext: Boolean) {
			pageText.text = text
			previous.isEnabled = canPrevious
			previous.alpha = if (canPrevious) 1f else 0.35f
			next.isEnabled = canNext
			next.alpha = if (canNext) 1f else 0.35f
		}
	}

	class Holder(
		view: View,
		private val onClick: (Manga) -> Unit,
		private val onTagClick: (MangaTag) -> Unit,
	) : RecyclerView.ViewHolder(view) {

		private val title: TextView = view.findViewById(R.id.textView_title)
		private val subtitle: TextView = view.findViewById(R.id.textView_subtitle)
		private val cover: CoverImageView = view.findViewById(R.id.imageView_cover)
		private val backCover: CoverImageView = view.findViewById(R.id.imageView_back)
		private val series: TextView = view.findViewById(R.id.textView_series)
		private val type: TextView = view.findViewById(R.id.textView_type)
		private val language: TextView = view.findViewById(R.id.textView_language)
		private val chips: ChipGroup = view.findViewById(R.id.chipGroup_tags)
		private val footer: TextView = view.findViewById(R.id.textView_footer)

		fun bind(manga: Manga, page: Int) {
			val context = itemView.context
			val source = manga.source as? MangaParserSource
			val sourceTitle = source?.getTitle(context) ?: manga.source.getTitle(context)
			val coverUrl = manga.largeCoverUrl?.ifEmpty { manga.coverUrl } ?: manga.coverUrl
			val typeText = inferEntryType(context, manga, source)
			val languageText = source?.locale?.toLocale()?.getDisplayName(context).ifNullOrBlank("N/A")

			title.text = manga.title
			subtitle.text = manga.altTitles.firstOrNull().ifNullOrBlank(sourceTitle)
			series.text = findSeries(manga).ifNullOrBlank(manga.altTitles.firstOrNull().ifNullOrBlank("N/A"))
			type.text = typeText
			language.text = languageText
			footer.text = context.getString(R.string.browser_footer_pattern, page + 1, sourceTitle)
			cover.setImageAsync(coverUrl, manga)
			backCover.setImageAsync(coverUrl, manga)
			bindTags(context, manga, typeText, languageText)

			itemView.contentDescription = manga.title
			itemView.setOnClickListener { onClick(manga) }
		}

		private fun findSeries(manga: Manga): String? {
			val keys = setOf("series", "parody", "parodies", "franchise", "original")
			return manga.tags.firstOrNull { tag ->
				tag.key.lowercase() in keys || tag.title.equals("Original", ignoreCase = true)
			}?.title
		}

		private fun inferEntryType(context: Context, manga: Manga, source: MangaParserSource?): String {
			val preferred = listOf("Doujinshi", "Manga", "Manhwa", "Manhua", "Artist CG", "Game CG", "Image Set")
			val tagType = manga.tags.firstOrNull { tag ->
				preferred.any { tag.title.equals(it, ignoreCase = true) }
			}?.title
			return tagType.ifNullOrBlank(source?.contentType?.let { context.getString(it.titleResId) }.ifNullOrBlank("N/A"))
		}

		private fun bindTags(context: Context, manga: Manga, typeText: String, languageText: String) {
			chips.removeAllViews()
			val visibleTags = manga.tags
				.filter { it.title.isNotBlank() }
				.distinct()
				.take(MAX_TAGS)
			if (visibleTags.isEmpty()) {
				listOf(typeText, languageText, "NSFW").forEach { tag ->
					chips.addView(createTagChip(context, tag, null))
				}
				return
			}
			for (tag in visibleTags) {
				chips.addView(createTagChip(context, tag.title, tag))
			}
		}

		private fun createTagChip(context: Context, text: String, tag: MangaTag?): Chip {
			return Chip(context).apply {
				this.text = text
				isCheckable = false
				isClickable = tag != null
				setTextColor(0xFFFFFFFF.toInt())
				textSize = 10f
				minHeight = 20.dp(context)
				setEnsureMinTouchTargetSize(false)
				minWidth = 0
				chipMinHeight = 20.dp(context).toFloat()
				chipStartPadding = 5.dp(context).toFloat()
				chipEndPadding = 5.dp(context).toFloat()
				setChipBackgroundColorResource(R.color.grey)
				if (tag != null) {
					setOnClickListener { onTagClick(tag) }
				}
			}
		}

		private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

		private fun String?.ifNullOrBlank(fallback: String): String = if (isNullOrBlank()) fallback else this

		private companion object {
			const val MAX_TAGS = 6
		}
	}

	companion object {
		const val VIEW_TYPE_ENTRY = 0
		const val VIEW_TYPE_PAGER = 1
	}
}
