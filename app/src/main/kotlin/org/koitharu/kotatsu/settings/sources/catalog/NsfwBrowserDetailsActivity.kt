package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.databinding.ActivityNsfwBrowserDetailsBinding
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.reader.ui.ReaderState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class NsfwBrowserDetailsActivity : BaseActivity<ActivityNsfwBrowserDetailsBinding>() {

	private val viewModel by viewModels<NsfwBrowserDetailsViewModel>()
	private lateinit var pagesAdapter: BrowserPageAdapter
	private lateinit var relatedAdapter: BrowserRelatedAdapter

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityNsfwBrowserDetailsBinding.inflate(layoutInflater))
		pagesAdapter = BrowserPageAdapter { pageIndex -> openReaderAtPage(pageIndex) }
		relatedAdapter = BrowserRelatedAdapter { manga -> router.openNsfwBrowserDetails(manga) }
		viewBinding.recyclerViewPages.layoutManager = GridLayoutManager(this, 3)
		viewBinding.recyclerViewPages.adapter = pagesAdapter
		viewBinding.recyclerViewRelated.layoutManager = LinearLayoutManager(this)
		viewBinding.recyclerViewRelated.adapter = relatedAdapter
		viewBinding.buttonReadOnline.setOnClickListener {
			val chapterId = viewModel.state.value.manga.chapters.orEmpty().firstOrNull()?.id
			val intent = ReaderIntent.Builder(this)
				.manga(viewModel.state.value.manga)
				.state(chapterId?.let { ReaderState(it, 0, 0) })
				.browserMode()
				.build()
			router.openReader(intent)
		}
		viewBinding.buttonDownload.setOnClickListener {
			router.showDownloadDialog(viewModel.state.value.manga, viewBinding.root)
		}
		viewBinding.textEmpty.setOnClickListener {
			viewModel.retry()
		}
		viewModel.state.observe(this, this::render)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.scrollView.updatePadding(
			left = bars.left + 18.dp(),
			right = bars.right + 18.dp(),
			top = bars.top + 18.dp(),
			bottom = bars.bottom + 116.dp(),
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	private fun render(state: NsfwBrowserDetailsState) {
		val manga = state.manga
		val source = manga.source as? MangaParserSource
		val sourceTitle = source?.getTitle(this) ?: manga.source.getTitle(this)
		val coverUrl = manga.largeCoverUrl?.ifEmpty { manga.coverUrl } ?: manga.coverUrl
		val typeText = inferEntryType(manga, source)
		val languageText = source?.locale?.toLocale()?.getDisplayName(this).ifNullOrBlank("English")
		val seriesText = findSeries(manga).ifNullOrBlank(manga.altTitles.firstOrNull().ifNullOrBlank("N/A"))

		viewBinding.imageViewCover.setImageAsync(coverUrl, manga)
		viewBinding.textViewTitle.text = manga.title
		val authorText = manga.authors.joinToString(", ").ifNullOrBlank("N/A")
		viewBinding.textViewSubtitle.text = authorText
		viewBinding.textViewGroup.text = sourceTitle
		viewBinding.textViewType.text = typeText
		viewBinding.textViewLanguage.text = languageText
		viewBinding.textViewSeries.text = seriesText
		viewBinding.textViewFooter.text = formatDate(manga.chapters.orEmpty().firstOrNull()?.uploadDate)
		viewBinding.textViewSubtitle.setOnClickListener {
			if (authorText != "N/A") {
				router.openNsfwBrowserMode(manga.source, authorText)
			}
		}
		viewBinding.textViewSeries.setOnClickListener {
			if (seriesText != "N/A") {
				router.openNsfwBrowserMode(manga.source, seriesText)
			}
		}
		bindChips(viewBinding.chipGroupCharacters, extractTags(manga, CHARACTER_KEYS).take(MAX_CHARACTERS))
		bindChips(
			chipGroup = viewBinding.chipGroupTags,
			tags = manga.tags
				.map { it.title }
				.filter { it.isNotBlank() && it !in setOf(typeText, languageText, seriesText) }
				.distinct()
				.take(MAX_TAGS)
				.ifEmpty { listOf(typeText, languageText) },
		)
		pagesAdapter.submitItems(state.pages)
		relatedAdapter.submitItems(state.related)
		viewBinding.layoutRelated.isVisible = state.related.isNotEmpty()
		viewBinding.progress.isVisible = state.isLoading
		viewBinding.textEmpty.isVisible = !state.isLoading && state.pages.isEmpty()
		viewBinding.layoutInfo.isGone = !state.isLoading && state.error != null && state.pages.isEmpty()
		viewBinding.textEmpty.text = when {
			state.error != null -> state.error.localizedMessage ?: state.error.javaClass.simpleName
			else -> getString(R.string.no_browser_pages)
		}
	}

	private fun openReaderAtPage(pageIndex: Int) {
		val chapterId = viewModel.state.value.manga.chapters.orEmpty().firstOrNull()?.id ?: return
		val intent = ReaderIntent.Builder(this)
			.manga(viewModel.state.value.manga)
			.state(ReaderState(chapterId, pageIndex, 0))
			.browserMode()
			.build()
		router.openReader(intent)
	}

	private fun bindChips(chipGroup: ChipGroup, tags: List<String>) {
		chipGroup.removeAllViews()
		if (tags.isEmpty()) {
			chipGroup.addView(createTagChip("N/A"))
			return
		}
		for (tag in tags) {
			chipGroup.addView(createTagChip(tag))
		}
	}

	private fun createTagChip(text: String): Chip {
		return Chip(this).apply {
			this.text = text
			isCheckable = false
			isClickable = true
			setTextColor(0xFFFFFFFF.toInt())
			textSize = 13f
			minHeight = 24.dp()
			setEnsureMinTouchTargetSize(false)
			minWidth = 0
			chipMinHeight = 24.dp().toFloat()
			chipStartPadding = 6.dp().toFloat()
			chipEndPadding = 6.dp().toFloat()
			setChipBackgroundColorResource(R.color.grey)
			setOnClickListener {
				router.openNsfwBrowserMode(viewModel.state.value.manga.source, text)
			}
		}
	}

	private fun inferEntryType(manga: Manga, source: MangaParserSource?): String {
		val preferred = listOf("Doujinshi", "Manga", "Manhwa", "Manhua", "Artist CG", "Game CG", "Image Set")
		return manga.tags.firstOrNull { tag ->
			preferred.any { tag.title.equals(it, ignoreCase = true) }
		}?.title.ifNullOrBlank(source?.contentType?.let { getString(it.titleResId) }.ifNullOrBlank("N/A"))
	}

	private fun findSeries(manga: Manga): String? = extractTags(manga, SERIES_KEYS).firstOrNull()

	private fun extractTags(manga: Manga, keys: Set<String>): List<String> = manga.tags
		.filter { tag -> tag.key.lowercase(Locale.ROOT) in keys }
		.map { it.title }
		.filter { it.isNotBlank() }
		.distinct()

	private fun formatDate(uploadDate: Long?): String {
		val time = uploadDate?.takeIf { it > 0L } ?: return ""
		return SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date(time))
	}

	private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

	private fun String?.ifNullOrBlank(fallback: String): String = if (isNullOrBlank()) fallback else this

	private companion object {
		val SERIES_KEYS = setOf("series", "parody", "parodies", "franchise", "original")
		val CHARACTER_KEYS = setOf("character", "characters")
		const val MAX_TAGS = 12
		const val MAX_CHARACTERS = 6
	}
}

private class BrowserPageAdapter(
	private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<BrowserPageAdapter.Holder>() {

	private val items = ArrayList<MangaPage>()

	fun submitItems(newItems: List<MangaPage>) {
		items.clear()
		items.addAll(newItems)
		notifyDataSetChanged()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_nsfw_browser_page, parent, false)
		return Holder(view, onClick)
	}

	override fun onBindViewHolder(holder: Holder, position: Int) {
		holder.bind(items[position], position)
	}

	override fun getItemCount(): Int = items.size

	class Holder(
		view: View,
		private val onClick: (Int) -> Unit,
	) : RecyclerView.ViewHolder(view) {
		private val image: CoverImageView = view.findViewById(R.id.imageView_page)
		private val pageNumber: TextView = view.findViewById(R.id.textView_page_number)

		fun bind(page: MangaPage, position: Int) {
			image.setImageAsync(page)
			pageNumber.text = (position + 1).toString()
			itemView.setOnClickListener { onClick(position) }
			itemView.contentDescription = itemView.context.getString(R.string.page_pattern, position + 1)
		}
	}
}

private class BrowserRelatedAdapter(
	private val onClick: (Manga) -> Unit,
) : RecyclerView.Adapter<BrowserRelatedAdapter.Holder>() {

	private val items = ArrayList<Manga>()

	fun submitItems(newItems: List<Manga>) {
		items.clear()
		items.addAll(newItems.take(MAX_ITEMS))
		notifyDataSetChanged()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_nsfw_browser_entry, parent, false)
		return Holder(view, onClick)
	}

	override fun onBindViewHolder(holder: Holder, position: Int) {
		holder.bind(items[position])
	}

	override fun getItemCount(): Int = items.size

	class Holder(
		view: View,
		private val onClick: (Manga) -> Unit,
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

		fun bind(manga: Manga) {
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
			footer.text = sourceTitle
			cover.setImageAsync(coverUrl, manga)
			backCover.setImageAsync(coverUrl, manga)
			bindTags(context, manga, typeText, languageText)
			itemView.contentDescription = manga.title
			itemView.setOnClickListener { onClick(manga) }
		}

		private fun findSeries(manga: Manga): String? {
			val keys = setOf("series", "parody", "parodies", "franchise", "original")
			return manga.tags.firstOrNull { tag ->
				tag.key.lowercase(Locale.ROOT) in keys || tag.title.equals("Original", ignoreCase = true)
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
			val tagTitles = manga.tags
				.map { it.title }
				.filter { it.isNotBlank() }
				.distinct()
				.take(MAX_TAGS)
				.ifEmpty { listOf(typeText, languageText, "NSFW") }
			for (tag in tagTitles) {
				chips.addView(createTagChip(context, tag))
			}
		}

		private fun createTagChip(context: Context, text: String): Chip {
			return Chip(context).apply {
				this.text = text
				isCheckable = false
				isClickable = false
				setTextColor(0xFFFFFFFF.toInt())
				textSize = 10f
				minHeight = 20.dp(context)
				setEnsureMinTouchTargetSize(false)
				minWidth = 0
				chipMinHeight = 20.dp(context).toFloat()
				chipStartPadding = 5.dp(context).toFloat()
				chipEndPadding = 5.dp(context).toFloat()
				setChipBackgroundColorResource(R.color.grey)
			}
		}

		private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

		private fun String?.ifNullOrBlank(fallback: String): String = if (isNullOrBlank()) fallback else this
	}

	private companion object {
		const val MAX_ITEMS = 12
		const val MAX_TAGS = 6
	}
}
