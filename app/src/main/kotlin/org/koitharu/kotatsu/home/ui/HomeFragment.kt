package org.koitharu.kotatsu.home.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentHomeBinding
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.ReaderState
import java.time.Instant
import java.time.temporal.ChronoUnit

@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding>() {

	private val viewModel by viewModels<HomeViewModel>()
	private var featuredComics = emptyList<Manga>()
	private var featuredIndex = 0
	private var smartAdapter: HomeRailAdapter? = null
	private var manhuaAdapter: HomeRailAdapter? = null
	private var mangaAdapter: HomeRailAdapter? = null
	private val lazySectionsLoaded = HashSet<HomeSection>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeBinding {
		return FragmentHomeBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentHomeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.buttonContinueReadingAll.setOnClickListener { router.openContinueReading() }
		binding.buttonContinueReadingSeeAll.setOnClickListener { router.openContinueReading() }

		smartAdapter = HomeRailAdapter { manga -> router.openDetails(manga) }.also {
			setupRail(binding.smartRecommendationList, it)
		}
		manhuaAdapter = HomeRailAdapter { manga -> router.openDetails(manga) }.also {
			setupRail(binding.manhuaRecommendationList, it)
		}
		mangaAdapter = HomeRailAdapter { manga -> router.openDetails(manga) }.also {
			setupRail(binding.mangaRecommendationList, it)
		}

		// Lazy-load off-screen rails when the user scrolls near them.
		binding.homeRoot.setOnScrollChangeListener { _, _, _, _, _ ->
			maybeLoadVisibleSections(binding)
		}
		binding.homeRoot.post { maybeLoadVisibleSections(binding) }

		viewModel.continueReadingComics.observe(viewLifecycleOwner, ::renderContinueReading)
		viewModel.featuredComics.observe(viewLifecycleOwner, ::renderFeaturedComics)
		viewModel.trendingComics.observe(viewLifecycleOwner, ::renderTrendingComics)
		viewModel.smartRecommendationsLoading.observe(viewLifecycleOwner) { isLoading ->
			binding.smartRecommendationLoading.isVisible = isLoading && viewModel.smartRecommendations.value.isEmpty()
		}
		viewModel.smartRecommendations.observe(viewLifecycleOwner) { comics ->
			renderRecommendationRail(binding.smartRecommendationList, binding.smartRecommendationLoading, smartAdapter, comics)
		}
		viewModel.manhuaRecommendationsLoading.observe(viewLifecycleOwner) { isLoading ->
			binding.manhuaRecommendationLoading.isVisible = isLoading && viewModel.manhuaRecommendations.value.isEmpty()
		}
		viewModel.manhuaRecommendations.observe(viewLifecycleOwner) { comics ->
			renderRecommendationRail(binding.manhuaRecommendationList, binding.manhuaRecommendationLoading, manhuaAdapter, comics)
		}
		viewModel.mangaRecommendationsLoading.observe(viewLifecycleOwner) { isLoading ->
			binding.mangaRecommendationLoading.isVisible = isLoading && viewModel.mangaRecommendations.value.isEmpty()
		}
		viewModel.mangaRecommendations.observe(viewLifecycleOwner) { comics ->
			renderRecommendationRail(binding.mangaRecommendationList, binding.mangaRecommendationLoading, mangaAdapter, comics)
		}
		// Header + switch always stay (except Performance mode). Body hides when off
		// so the toggle remains reachable and does not sit under the bottom nav alone.
		binding.switchRecentUpdates.setOnCheckedChangeListener(null)
		viewModel.isRecentUpdatesEnabled.observe(viewLifecycleOwner) { enabled ->
			if (viewModel.isPerformanceMode) {
				binding.recentUpdatesSection.isVisible = false
				return@observe
			}
			binding.recentUpdatesSection.isVisible = true
			binding.switchRecentUpdates.isChecked = enabled
			binding.recentUpdatesBody.isVisible = enabled
			binding.textRecentUpdatesTitle.alpha = if (enabled) 1f else 0.55f
			if (!enabled) {
				binding.recentUpdatesLoading.isVisible = false
				binding.recentUpdatesList.removeAllViews()
				binding.recentUpdatesPagination.removeAllViews()
			}
		}
		binding.switchRecentUpdates.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setRecentUpdatesEnabled(isChecked)
		}
		if (!viewModel.isPerformanceMode) {
			viewModel.recentUpdatesLoading.observe(viewLifecycleOwner) { isLoading ->
				if (!viewModel.isRecentUpdatesEnabled.value) {
					binding.recentUpdatesBody.isVisible = false
					return@observe
				}
				val hasUpdates = viewModel.recentUpdates.value.isNotEmpty()
				binding.recentUpdatesLoading.isVisible = isLoading && !hasUpdates
				binding.recentUpdatesList.isVisible = hasUpdates
				binding.recentUpdatesPagination.isVisible = hasUpdates
			}
			combine(viewModel.recentUpdates, viewModel.recentUpdatesPage, ::Pair)
				.observe(viewLifecycleOwner) { (updates, page) ->
					if (viewModel.isRecentUpdatesEnabled.value) {
						renderRecentUpdates(updates, page)
					}
				}
		} else {
			binding.recentUpdatesSection.isVisible = false
		}
	}

	private fun setupRail(list: RecyclerView, adapter: HomeRailAdapter) {
		list.layoutManager = LinearLayoutManager(list.context, RecyclerView.HORIZONTAL, false)
		list.setHasFixedSize(true)
		list.itemAnimator = null
		list.adapter = adapter
	}

	private fun maybeLoadVisibleSections(binding: FragmentHomeBinding) {
		fun nearVisible(section: View): Boolean {
			if (!section.isShown) return false
			val loc = IntArray(2)
			section.getLocationOnScreen(loc)
			val screenH = resources.displayMetrics.heightPixels
			// Load a bit before the section enters the viewport.
			return loc[1] < screenH + (screenH / 3)
		}
		if (nearVisible(binding.smartRecommendationSection)) {
			requestSection(HomeSection.SMART)
		}
		if (nearVisible(binding.manhuaRecommendationSection)) {
			requestSection(HomeSection.MANHUA)
		}
		if (nearVisible(binding.mangaRecommendationSection)) {
			requestSection(HomeSection.MANGA)
		}
		if (!viewModel.isPerformanceMode &&
			viewModel.isRecentUpdatesEnabled.value &&
			nearVisible(binding.recentUpdatesSection)
		) {
			requestSection(HomeSection.RECENT)
		}
	}

	private fun requestSection(section: HomeSection) {
		if (!lazySectionsLoaded.add(section)) return
		viewModel.ensureSectionLoaded(section)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding?.homeRoot?.updatePadding(
			left = barsInsets.left + 14.dp(v),
			right = barsInsets.right + 14.dp(v),
			bottom = barsInsets.bottom + HOME_BOTTOM_CONTENT_SPACE_DP.dp(v),
		)
		return insets.consumeAllSystemBarsInsets()
	}

	private fun renderContinueReading(items: List<MangaWithHistory>) {
		val binding = viewBinding ?: return
		binding.continueReadingSection.isVisible = items.isNotEmpty()
		binding.buttonContinueReadingSeeAll.isVisible = items.size >= CONTINUE_READING_LIMIT
		if (items.isEmpty()) {
			binding.continueReadingList.removeAllViews()
			return
		}
		val context = binding.continueReadingList.context
		val inflater = LayoutInflater.from(context)
		val rowSpacingPx = 6.dp(binding.root)
		binding.continueReadingList.removeAllViews()
		for ((index, item) in items.take(CONTINUE_READING_LIMIT).withIndex()) {
			val manga = item.manga
			val itemView = inflater.inflate(R.layout.item_home_continue_reading, binding.continueReadingList, false)
			itemView.layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				if (index > 0) topMargin = rowSpacingPx
			}
			itemView.findViewById<CoverImageView>(R.id.imageView_cover).setHomeCoverAsync(manga)
			itemView.findViewById<TextView>(R.id.textView_title).text = manga.title
			itemView.findViewById<TextView>(R.id.textView_subtitle).text = item.continueReadingSubtitle()
			itemView.contentDescription = manga.title
			itemView.setOnClickListener {
				router.openReader(
					ReaderIntent.Builder(context)
						.manga(manga)
						.state(ReaderState(item.history))
						.build(),
				)
			}
			binding.continueReadingList.addView(itemView)
		}
	}

	private fun renderFeaturedComics(comics: List<Manga>) {
		val binding = viewBinding ?: return
		if (comics.isEmpty()) {
			return
		}
		featuredComics = comics.take(FEATURED_LIMIT)
		featuredIndex = 0
		val inflater = LayoutInflater.from(binding.comicsCarousel.context)
		binding.comicsCarousel.removeAllViews()
		for ((index, manga) in featuredComics.withIndex()) {
			val itemView = inflater.inflate(R.layout.item_home_carousel_cover, binding.comicsCarousel, false)
			itemView.findViewById<CoverImageView>(R.id.imageView_cover).setHomeCoverAsync(manga)
			itemView.contentDescription = manga.title
			itemView.setOnClickListener {
				if (index == featuredIndex) {
					router.openDetails(manga)
				} else {
					selectFeaturedComic(index, smoothScroll = true)
				}
			}
			itemView.setOnLongClickListener {
				router.openDetails(manga)
				true
			}
			binding.comicsCarousel.addView(itemView)
		}
		selectFeaturedComic(0, smoothScroll = false)
		configureFeaturedCarouselSelection()
	}

	private fun selectFeaturedComic(index: Int, smoothScroll: Boolean) {
		val binding = viewBinding ?: return
		if (featuredComics.isEmpty()) {
			return
		}
		featuredIndex = index.coerceIn(0, featuredComics.lastIndex)
		val manga = featuredComics[featuredIndex]
		binding.textFeaturedType.text = manga.detectComicType().label.uppercase()
		binding.textFeaturedTitle.text = manga.title
		binding.textFeaturedDescription.text = manga.featuredDescription()
		val selectedCover = binding.comicsCarousel.getChildAt(featuredIndex) ?: return
		binding.featuredCarouselScroll.post {
			val targetX = (selectedCover.left - 8.dp(binding.root)).coerceAtLeast(0)
			if (smoothScroll) {
				binding.featuredCarouselScroll.smoothScrollTo(targetX, 0)
			} else {
				binding.featuredCarouselScroll.scrollTo(targetX, 0)
			}
		}
	}

	private fun Manga.featuredDescription(): String {
		val cleaned = description
			?.replace(HTML_TAG_REGEX, " ")
			?.replace(WHITESPACE_REGEX, " ")
			?.trim()
			?.takeIf { it.isNotEmpty() }
		return cleaned?.limitWords(FEATURED_DESCRIPTION_WORD_LIMIT)
			?: "$title is a featured comic currently available on Tarumi."
	}

	private fun String.limitWords(limit: Int): String {
		val words = split(' ').filter { it.isNotBlank() }
		return if (words.size <= limit) {
			this
		} else {
			words.take(limit).joinToString(" ") + "..."
		}
	}

	private fun configureFeaturedCarouselSelection() {
		val binding = viewBinding ?: return
		binding.featuredCarouselScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
			if (featuredComics.size < 2) {
				return@setOnScrollChangeListener
			}
			updateFeaturedSelectionFromScroll(scrollX)
		}
	}

	private fun updateFeaturedSelectionFromScroll(scrollX: Int) {
		val binding = viewBinding ?: return
		if (featuredComics.isEmpty() || binding.comicsCarousel.childCount == 0) {
			return
		}
		val viewportCenter = scrollX + binding.featuredCarouselScroll.width / 2
		var closestIndex = featuredIndex
		var closestDistance = Int.MAX_VALUE
		for (i in 0 until binding.comicsCarousel.childCount) {
			val child = binding.comicsCarousel.getChildAt(i)
			val childCenter = child.left + child.width / 2
			val distance = kotlin.math.abs(childCenter - viewportCenter)
			if (distance < closestDistance) {
				closestDistance = distance
				closestIndex = i
			}
		}
		if (closestIndex != featuredIndex && closestIndex in featuredComics.indices) {
			featuredIndex = closestIndex
			val manga = featuredComics[closestIndex]
			binding.textFeaturedType.text = manga.detectComicType().label.uppercase()
			binding.textFeaturedTitle.text = manga.title
			binding.textFeaturedDescription.text = manga.featuredDescription()
		}
	}

	private fun renderTrendingComics(comics: List<Manga>) {
		val binding = viewBinding ?: return
		val visibleComics = comics.take(6)
		val isLoading = visibleComics.isEmpty()
		binding.trendingLoading.isVisible = isLoading
		binding.trendingGrid.isVisible = !isLoading
		if (isLoading) {
			return
		}
		val context = binding.trendingGrid.context
		val inflater = LayoutInflater.from(context)
		val rowSpacingPx = (26 * context.resources.displayMetrics.density).toInt()
		val colSpacingPx = (18 * context.resources.displayMetrics.density).toInt()
		binding.trendingGrid.removeAllViews()

		val rows = (visibleComics.size + COLUMNS - 1) / COLUMNS
		for (rowIndex in 0 until rows) {
			val row = LinearLayout(context).apply {
				orientation = LinearLayout.HORIZONTAL
				layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				).apply {
					if (rowIndex > 0) topMargin = rowSpacingPx
				}
			}
			for (col in 0 until COLUMNS) {
				val index = rowIndex * COLUMNS + col
				if (index >= visibleComics.size) {
					val spacer = View(context)
					val lp = LinearLayout.LayoutParams(0, 0, 1f)
					if (col > 0) lp.marginStart = colSpacingPx
					spacer.layoutParams = lp
					row.addView(spacer)
					continue
				}
				val manga = visibleComics[index]
				val itemView = inflater.inflate(R.layout.item_home_trending_cover, row, false)
				val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
				if (col > 0) lp.marginStart = colSpacingPx
				itemView.layoutParams = lp
				itemView.findViewById<CoverImageView>(R.id.imageView_cover).setHomeCoverAsync(manga)
				itemView.findViewById<android.widget.TextView>(R.id.textView_title).text = manga.title

				val chapterView = itemView.findViewById<android.widget.TextView>(R.id.textView_chapter)
				val latestChapter = manga.chapters?.sortedWith(HomeViewModel.CHAPTER_COMPARATOR)?.firstOrNull()
				if (latestChapter != null) {
					chapterView.visibility = View.VISIBLE
					chapterView.text = latestChapter.title
				} else {
					chapterView.visibility = View.GONE
				}

				val ratingView = itemView.findViewById<android.widget.TextView>(R.id.textView_rating)
				if (manga.hasRating) {
					ratingView.visibility = View.VISIBLE
					val ratingVal = manga.rating * 10f
					val ratingText = String.format(java.util.Locale.US, "%.1f", ratingVal)
					val starsStr = "★★★★★"
					val fullText = "$starsStr  $ratingText"
					val spannable = android.text.SpannableString(fullText)
					spannable.setSpan(
						android.text.style.ForegroundColorSpan(ContextCompat.getColor(context, R.color.common_yellow)),
						0, starsStr.length,
						android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
					)
					ratingView.text = spannable
				} else {
					ratingView.visibility = View.GONE
				}

				itemView.contentDescription = manga.title
				itemView.setOnClickListener { router.openDetails(manga) }
				row.addView(itemView)
			}
			binding.trendingGrid.addView(row)
		}
	}

	private fun renderRecommendationRail(
		list: RecyclerView,
		loadingView: View,
		adapter: HomeRailAdapter?,
		comics: List<Manga>,
	) {
		list.isVisible = comics.isNotEmpty()
		if (comics.isNotEmpty()) {
			loadingView.isVisible = false
		}
		adapter?.submitList(comics)
	}

	private fun renderRecentUpdates(updates: List<RecentUpdateGroup>, page: Int) {
		val binding = viewBinding ?: return
		val context = binding.recentUpdatesList.context
		val inflater = LayoutInflater.from(context)
		val rowSpacingPx = 10.dp(binding.root)
		binding.recentUpdatesList.removeAllViews()

		val items = updates
			.drop(page * RECENT_PAGE_SIZE)
			.take(RECENT_PAGE_SIZE)
		for ((index, item) in items.withIndex()) {
			val itemView = inflater.inflate(R.layout.item_home_recent_update, binding.recentUpdatesList, false)
			itemView.layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				if (index > 0) topMargin = rowSpacingPx
			}
			itemView.findViewById<CoverImageView>(R.id.imageView_cover).setHomeCoverAsync(item.manga)
			itemView.findViewById<TextView>(R.id.textView_title).text = item.manga.title
			itemView.findViewById<LinearLayout>(R.id.layoutChapters).apply {
				removeAllViews()
				for (chapter in item.chapters) {
					addView(createChapterRow(chapter))
				}
			}
			itemView.contentDescription = item.manga.title
			itemView.setOnClickListener { router.openDetails(item.manga) }
			binding.recentUpdatesList.addView(itemView)
		}
		renderRecentUpdatesPagination(page, updates.size)
	}

	private fun createChapterRow(chapter: MangaChapter): View {
		val context = requireContext()
		return LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				topMargin = 8.dp(requireView())
			}
			addView(TextView(context).apply {
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
				typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
				text = chapter.getLocalizedTitle(resources)
				setTextColor(ContextCompat.getColor(context, R.color.taru_text_primary))
				textSize = 15f
				maxLines = 1
				ellipsize = android.text.TextUtils.TruncateAt.END
			})
			addView(TextView(context).apply {
				layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				)
				typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
				text = chapter.formatRelativeTime()
				setTextColor(ContextCompat.getColor(context, R.color.taru_text_soft))
				textSize = 13f
				maxLines = 1
			})
		}
	}

	private fun renderRecentUpdatesPagination(page: Int, totalItems: Int) {
		val binding = viewBinding ?: return
		val context = binding.recentUpdatesPagination.context
		binding.recentUpdatesPagination.removeAllViews()
		// Dynamic pages across the full library (up to 2000); only inflate compact controls.
		val pageCount = if (totalItems <= 0) {
			1
		} else {
			((totalItems + RECENT_PAGE_SIZE - 1) / RECENT_PAGE_SIZE).coerceAtLeast(1)
		}
		val canGoBack = page > 0
		val canGoNext = page < pageCount - 1

		binding.recentUpdatesPagination.addView(createRecentNavButton(
			text = context.getString(R.string.recent_back),
			enabled = canGoBack,
		) {
			viewModel.setRecentUpdatesPage(page - 1)
		})

		// Compact "Page X / Y" instead of one button per page (200 pages would explode RAM).
		binding.recentUpdatesPagination.addView(
			TextView(context).apply {
				layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				).apply {
					topMargin = 12.dp(binding.root)
					bottomMargin = 4.dp(binding.root)
				}
				gravity = Gravity.CENTER
				text = "Page ${page + 1} / $pageCount  ·  $totalItems titles"
				setTextColor(ContextCompat.getColor(context, R.color.taru_text_secondary))
				textSize = 13f
			},
		)

		// Optional nearby page chips (window of 5) for quick jumps without 200 views.
		val pagesRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				topMargin = 8.dp(binding.root)
			}
		}
		val windowStart = (page - 2).coerceAtLeast(0)
		val windowEnd = (windowStart + 4).coerceAtMost(pageCount - 1)
		for (i in windowStart..windowEnd) {
			val isSelected = page == i
			val button = TextView(context).apply {
				layoutParams = LinearLayout.LayoutParams(54.dp(binding.root), 54.dp(binding.root)).apply {
					if (i > windowStart) marginStart = 8.dp(binding.root)
				}
				background = ContextCompat.getDrawable(
					context,
					if (isSelected) R.drawable.bg_taru_page_active else R.drawable.bg_taru_page_button,
				)
				gravity = Gravity.CENTER
				isEnabled = true
				alpha = 1f
				text = (i + 1).toString()
				typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
				setTextColor(
					ContextCompat.getColor(
						context,
						if (isSelected) android.R.color.white else R.color.taru_text_primary,
					),
				)
				textSize = 16f
				setOnClickListener { viewModel.setRecentUpdatesPage(i) }
			}
			pagesRow.addView(button)
		}
		binding.recentUpdatesPagination.addView(pagesRow)
		binding.recentUpdatesPagination.addView(createRecentNavButton(
			text = context.getString(R.string.recent_next),
			enabled = canGoNext,
			topMargin = 14.dp(binding.root),
		) {
			viewModel.setRecentUpdatesPage(page + 1)
		})
	}

	private fun createRecentNavButton(
		text: String,
		enabled: Boolean,
		topMargin: Int = 0,
		onClick: () -> Unit,
	): TextView {
		val binding = viewBinding ?: error("Home view is not attached")
		val context = binding.recentUpdatesPagination.context
		return TextView(context).apply {
			layoutParams = LinearLayout.LayoutParams(132.dp(binding.root), 50.dp(binding.root)).apply {
				this.topMargin = topMargin
			}
			background = ContextCompat.getDrawable(context, R.drawable.bg_taru_page_button)
			gravity = Gravity.CENTER
			isEnabled = enabled
			alpha = if (enabled) 1f else 0.42f
			this.text = text
			typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
			setTextColor(ContextCompat.getColor(context, R.color.taru_text_primary))
			textSize = 15f
			setOnClickListener { onClick() }
		}
	}

	private fun MangaWithHistory.continueReadingSubtitle(): String {
		val progress = continueReadingProgress()
		val chapterTitle = progress.currentChapterLabel
			?.let { getString(R.string.chapter_number, it) }
			?: getString(R.string.unknown)
		val totalTitle = progress.totalChapterLabel ?: getString(R.string.unknown)
		return getString(
			R.string.continue_reading_meta_pattern,
			chapterTitle,
			totalTitle,
			progress.percent,
			history.updatedAt.formatRelativeTime(),
		)
	}

	private fun MangaChapter.formatRelativeTime(): String {
		if (uploadDate <= 0L) {
			return getString(R.string.updated)
		}
		return Instant.ofEpochMilli(uploadDate).formatRelativeTime()
	}

	private fun Instant.formatRelativeTime(): String {
		val minutes = until(Instant.now(), ChronoUnit.MINUTES)
		return when {
			minutes < 3 -> getString(R.string.just_now)
			minutes < 60 -> resources.getQuantityStringSafe(R.plurals.minutes_ago, minutes.toInt(), minutes.toInt())
			minutes < 60 * 24 -> {
				val hours = (minutes / 60).toInt().coerceAtLeast(1)
				resources.getQuantityStringSafe(R.plurals.hours_ago, hours, hours)
			}

			minutes < 60 * 24 * 14 -> {
				val days = (minutes / (60 * 24)).toInt().coerceAtLeast(1)
				resources.getQuantityStringSafe(R.plurals.days_ago, days, days)
			}

			else -> getString(R.string.long_ago)
		}
	}

	private fun Int.dp(view: View): Int = (this * view.resources.displayMetrics.density).toInt()

	private companion object {
		const val COLUMNS = 2
		const val FEATURED_LIMIT = 8
		const val CONTINUE_READING_LIMIT = 4
		const val HOME_BOTTOM_CONTENT_SPACE_DP = 178
		const val RECENT_PAGE_SIZE = 10
		val HTML_TAG_REGEX = Regex("<[^>]+>")
		val WHITESPACE_REGEX = Regex("\\s+")
		const val FEATURED_DESCRIPTION_WORD_LIMIT = 26
	}
}
