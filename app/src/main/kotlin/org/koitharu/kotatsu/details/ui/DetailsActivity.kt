package org.koitharu.kotatsu.details.ui

import android.app.assist.AssistContent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannedString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.transition.TransitionManager
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.request.transformations
import coil3.size.Precision
import coil3.target.ImageViewTarget
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.core.image.CoilMemoryCacheKey
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.ui.image.TextDrawable
import org.koitharu.kotatsu.core.ui.image.TextViewTarget
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.LocaleUtils
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.isTextTruncated
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.parentView
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.databinding.ActivityDetailsBinding
import org.koitharu.kotatsu.databinding.LayoutDetailsTableBinding
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReadingTime
import org.koitharu.kotatsu.details.service.MangaPrefetchService
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.details.ui.scrobbling.ScrobblingItemDecoration
import org.koitharu.kotatsu.details.ui.scrobbling.ScrollingInfoAdapter
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.StaticItemSizeResolver
import org.koitharu.kotatsu.main.ui.owners.BottomSheetOwner
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.jsoup.Jsoup
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

@AndroidEntryPoint
class DetailsActivity :
	BaseActivity<ActivityDetailsBinding>(),
	View.OnClickListener,
	View.OnLayoutChangeListener,
	ViewTreeObserver.OnDrawListener,
	ChipsView.OnChipClickListener,
	OnListItemClickListener<Bookmark>,
	SwipeRefreshLayout.OnRefreshListener,
	AuthorSpan.OnAuthorClickListener,
	BottomSheetOwner {

	@Inject
	lateinit var shortcutManager: AppShortcutManager

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	@MangaHttpClient
	lateinit var okHttpClient: OkHttpClient

	@Inject
	lateinit var settings: AppSettings

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider
	private lateinit var infoBinding: LayoutDetailsTableBinding
	private var sourceStatsRequestUrl: String? = null

	override val bottomSheet: View?
		get() = viewBinding.containerBottomSheet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsBinding.inflate(layoutInflater))
		infoBinding = LayoutDetailsTableBinding.bind(viewBinding.root)
		configureTransparentAppBar()
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		viewBinding.chipFavorite.setOnClickListener(this)
		viewBinding.buttonStartReading?.setOnClickListener(this)
		infoBinding.textViewLocal.setOnClickListener(this)
		infoBinding.textViewSource.setOnClickListener(this)
		viewBinding.imageViewCover.setOnClickListener(this)
		viewBinding.textViewTitle.setOnClickListener(this)
		viewBinding.buttonDescriptionMore.setOnClickListener(this)
		viewBinding.buttonScrobblingMore.setOnClickListener(this)
		viewBinding.buttonRelatedMore.setOnClickListener(this)
		viewBinding.textViewDescription.addOnLayoutChangeListener(this)
		viewBinding.swipeRefreshLayout.setOnRefreshListener(this)
		viewBinding.textViewDescription.viewTreeObserver.addOnDrawListener(this)
		infoBinding.textViewAuthor.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.chipsTags.onChipClickListener = this
		TitleScrollCoordinator(viewBinding.textViewTitle).attach(viewBinding.scrollView)
		viewBinding.textViewDescription.maxLines = Int.MAX_VALUE - 1

		val appRouter = router
		viewModel.mangaDetails.filterNotNull().observe(this, ::onMangaUpdated)
		viewModel.coverUrl.observe(this, ::loadCover)
		viewModel.onMangaRemoved.observeEvent(this, ::onMangaRemoved)
		viewModel.onError
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DetailsErrorObserver(this, viewModel, exceptionResolver))
		viewModel.onActionDone
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.scrollView))
		combine(viewModel.historyInfo, viewModel.isLoading, ::Pair).observe(this) {
			onHistoryChanged(it.first, it.second)
		}
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.scrobblingInfo.observe(this, ::onScrobblingInfoChanged)
		viewModel.localSize.observe(this, ::onLocalSizeChanged)
		viewModel.relatedManga.observe(this, ::onRelatedMangaChanged)
		viewModel.favouriteCategories.observe(this, ::onFavoritesChanged)
		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteManga.observe(this, menuInvalidator)
		viewModel.tags.observe(this, ::onTagsChanged)
		viewModel.chapters.observe(this, PrefetchObserver(this))
		viewModel.onDownloadStarted
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.scrollView))
		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.scrollView,
			appShortcutManager = shortcutManager,
		)
		addMenuProvider(menuProvider)
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> = viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	override fun onClick(v: View) {
		when (v.id) {
			R.id.textView_source -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openList(manga.source, null, null)
			}

			R.id.textView_local -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showLocalInfoDialog(manga)
			}

			R.id.chip_favorite -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showFavoriteDialog(manga)
			}

			R.id.buttonStartReading -> {
				openReader()
			}

			R.id.imageView_cover -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openImage(
					url = viewModel.coverUrl.value ?: return,
					source = manga.source,
					preview = CoilMemoryCacheKey.from(viewBinding.imageViewCover),
					anchor = v,
				)
			}

			R.id.button_description_more -> {
				val tv = viewBinding.textViewDescription
				if (tv.context.isAnimationsEnabled) {
					tv.parentView?.let {
						TransitionManager.beginDelayedTransition(it)
					}
				}
				if (tv.maxLines in 1 until Integer.MAX_VALUE) {
					tv.maxLines = Integer.MAX_VALUE
				} else {
					tv.maxLines = resources.getInteger(R.integer.details_description_lines)
				}
			}

			R.id.button_scrobbling_more -> {
				router.showScrobblingSelectorSheet(
					manga = viewModel.getMangaOrNull() ?: return,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			}

			R.id.button_related_more -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openRelated(manga)
			}

			R.id.textView_title -> {
				val title = viewModel.getMangaOrNull()?.title?.nullIfEmpty() ?: return
				buildAlertDialog(this) {
					setMessage(title)
					setNegativeButton(R.string.close, null)
					setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
						copyToClipboard(getString(R.string.content_type_manga), title)
					}
				}.show()
			}
		}
	}

	override fun onAuthorClick(author: String) {
		router.showAuthorDialog(author, viewModel.getMangaOrNull()?.source ?: return)
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		val tag = data as? MangaTag ?: return
		router.showTagDialog(tag)
	}

	override fun onItemClick(item: Bookmark, view: View) {
		router.openReader(ReaderIntent.Builder(view.context).bookmark(item).incognito().build())
		Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
	}

	override fun onRefresh() {
		viewModel.reload()
	}

	override fun onDraw() {
		viewBinding.run {
			buttonDescriptionMore.isGone = true
		}
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int
	) {
		// The chapters/pages/bookmarks panel is inline below related manga, so no overlay padding is needed.
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		if (viewBinding.cardChapters != null) {
			// landscape
			viewBinding.cardChapters?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
				marginEnd = barsInsets.end(v) + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
				bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			}
			viewBinding.scrollView.updatePaddingRelative(
				bottom = barsInsets.bottom,
				start = barsInsets.start(v),
			)
			viewBinding.appbar.updatePaddingRelative(
				start = barsInsets.start(v),
			)
			return insets.consume(v, typeMask, bottom = true, end = true)
		} else {
			viewBinding.navbarDim?.updateLayoutParams {
				height = barsInsets.bottom
			}
			return insets
		}
	}

	private fun onFavoritesChanged(categories: Set<FavouriteCategory>) {
		val chip = viewBinding.chipFavorite
		val status = categories
			.firstOrNull { it.title in FavouritesRepository.TARUMI_STATUS_TITLES }
			?.title
		chip.setChipIconResource(if (status == null) R.drawable.ic_bookmark else R.drawable.ic_bookmark_added)
		chip.text = status ?: getString(R.string.save)
		applyFavoriteChipStyle(chip, status)
	}

	private fun applyFavoriteChipStyle(chip: Chip, status: String?) {
		val colors = when (status?.trim()?.lowercase()) {
			"reading" -> FavoriteStatusColors(
				background = 0xFFE7F1FF.toInt(),
				stroke = 0xFF74A7FF.toInt(),
				foreground = 0xFF0B56B3.toInt(),
			)
			"planned" -> FavoriteStatusColors(
				background = 0xFFF0ECFF.toInt(),
				stroke = 0xFF8D7BFF.toInt(),
				foreground = 0xFF5142C4.toInt(),
			)
			"completed" -> FavoriteStatusColors(
				background = 0xFFEAF8EF.toInt(),
				stroke = 0xFF62BE7B.toInt(),
				foreground = 0xFF1F7A3F.toInt(),
			)
			else -> FavoriteStatusColors(
				background = getColor(R.color.taru_surface_button),
				stroke = getColor(R.color.taru_outline),
				foreground = getColor(R.color.taru_text_primary),
			)
		}
		chip.chipBackgroundColor = ColorStateList.valueOf(colors.background)
		chip.chipStrokeColor = ColorStateList.valueOf(colors.stroke)
		chip.setTextColor(colors.foreground)
		chip.chipIconTint = ColorStateList.valueOf(colors.foreground)
	}

	private fun onLocalSizeChanged(size: Long) {
		if (size == 0L) {
			infoBinding.textViewLocal.isVisible = false
			infoBinding.textViewLocalLabel.isVisible = false
		} else {
			infoBinding.textViewLocal.text = FileSize.BYTES.format(this, size)
			infoBinding.textViewLocal.isVisible = true
			infoBinding.textViewLocalLabel.isVisible = true
		}
	}

	private fun onRelatedMangaChanged(related: List<MangaListModel>) {
		if (related.isEmpty()) {
			viewBinding.groupRelated.isVisible = false
			return
		}
		val rv = viewBinding.recyclerViewRelated

		@Suppress("UNCHECKED_CAST")
		val adapter = (rv.adapter as? BaseListAdapter<ListModel>) ?: BaseListAdapter<ListModel>()
			.addDelegate(
				ListItemType.MANGA_GRID,
				mangaGridItemAD(
					sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width)),
				) { item, view ->
					router.openDetails(item.toMangaWithOverride())
				},
			).also { rv.adapter = it }
		adapter.items = related
		viewBinding.groupRelated.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.swipeRefreshLayout.isRefreshing = isLoading
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		var adapter = viewBinding.recyclerViewScrobbling.adapter as? ScrollingInfoAdapter
		viewBinding.groupScrobbling.isGone = scrobblings.isEmpty()
		if (adapter != null) {
			adapter.items = scrobblings
		} else {
			adapter = ScrollingInfoAdapter(router)
			adapter.items = scrobblings
			viewBinding.recyclerViewScrobbling.adapter = adapter
			viewBinding.recyclerViewScrobbling.addItemDecoration(ScrobblingItemDecoration())
		}
	}

	private fun onMangaUpdated(details: MangaDetails) {
		val manga = details.toManga()
		with(viewBinding) {
			textViewTitle.text = manga.title
			textViewSubtitle.text = getString(
				R.string.by_author_pattern,
				manga.authors.joinToString(", ").ifBlank { getString(R.string.unknown) },
			)
			textViewNsfw16.isVisible = manga.contentRating == ContentRating.SUGGESTIVE
			textViewNsfw18.isVisible = manga.contentRating == ContentRating.ADULT
			textViewDescription.text = details.description.ifNullOrEmpty { getString(R.string.no_description) }
			textChipType?.text = manga.detectComicType().label.uppercase()
			textChipStatus?.text = manga.state?.let { resources.getString(it.titleResId).uppercase() } ?: getString(R.string.unknown).uppercase()
			textChipSource?.text = manga.source.getTitle(this@DetailsActivity).uppercase(Locale.getDefault())
			textChipSource?.isVisible = true
			textViewRating?.text = manga.formatSourceRating()
			textViewFollows?.text = manga.formatSourceFollows()
			textViewChaptersCount?.text = details.formatChapterCount()
		}
		updateSourceStatsFromPage(manga)
		with(infoBinding) {
			val translation = details.getLocale()
			infoBinding.textViewTranslation.textAndVisible = translation?.getDisplayLanguage(translation)
				?.toTitleCase(translation)
			infoBinding.textViewTranslation.drawableStart = translation?.let {
				LocaleUtils.getEmojiFlag(it)
			}?.let {
				TextDrawable.compound(infoBinding.textViewTranslation, it)
			}
			infoBinding.textViewTranslationLabel.isVisible = infoBinding.textViewTranslation.isVisible
			textViewAuthor.textAndVisible = manga.getAuthorsString()
			textViewAuthorLabel.isVisible = textViewAuthor.isVisible
			if (manga.hasRating) {
				ratingBarRating.rating = manga.rating * ratingBarRating.numStars
				ratingBarRating.isVisible = true
				textViewRatingLabel.isVisible = true
			} else {
				ratingBarRating.isVisible = false
				textViewRatingLabel.isVisible = false
			}
			manga.state?.let { state ->
				textViewState.textAndVisible = resources.getString(state.titleResId)
				textViewStateLabel.isVisible = textViewState.isVisible
			} ?: run {
				textViewState.isVisible = false
				textViewStateLabel.isVisible = false
			}

			if (manga.source == LocalMangaSource || manga.source == UnknownMangaSource) {
				textViewSource.isVisible = false
				textViewSourceLabel.isVisible = false
			} else {
				textViewSource.textAndVisible = manga.source.getTitle(this@DetailsActivity)
				textViewSource.setTooltipCompat(manga.source.getSummary(this@DetailsActivity))
				textViewSourceLabel.isVisible = textViewSource.isVisible == true
			}
			val faviconPlaceholderFactory = FaviconDrawable.Factory(R.style.FaviconDrawable_Chip)
			ImageRequest.Builder(this@DetailsActivity)
				.data(manga.source.faviconUri())
				.lifecycle(this@DetailsActivity)
				.crossfade(false)
				.precision(Precision.EXACT)
				.size(resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
				.target(TextViewTarget(textViewSource, Gravity.START))
				.placeholder(faviconPlaceholderFactory)
				.error(faviconPlaceholderFactory)
				.fallback(faviconPlaceholderFactory)
				.mangaSourceExtra(manga.source)
				.transformations(RoundedCornersTransformation(resources.getDimension(R.dimen.chip_icon_corner)))
				.allowRgb565(true)
				.enqueueWith(coil)
		}
		hideLegacyDetailsTable()
		title = manga.title
		invalidateOptionsMenu()
	}

	private fun onMangaRemoved(manga: Manga) {
		Toast.makeText(
			this,
			getString(R.string._s_deleted_from_local_storage, manga.title),
			Toast.LENGTH_SHORT,
		).show()
		finishAfterTransition()
	}

	private fun Manga.formatSourceRating(): String {
		if (!hasRating) {
			return getString(R.string.unknown)
		}
		val ratingValue = (rating * 5f).coerceIn(0f, 5f)
		return String.format(Locale.US, "%.1f", ratingValue)
	}

	private fun Manga.formatSourceFollows(): String {
		val value = readNumericProperty(
			"getFollows",
			"getFollowers",
			"getFollowCount",
			"getFollowersCount",
			"getFollowedCount",
		) ?: return getString(R.string.unknown)
		if (value <= 0L) {
			return getString(R.string.unknown)
		}
		return NumberFormat.getIntegerInstance(Locale.US).format(value)
	}

	private fun updateSourceStatsFromPage(manga: Manga) {
		val url = manga.publicUrl.takeIf { it.isHttpUrl() } ?: return
		sourceStatsRequestUrl = url
		lifecycleScope.launch {
			val stats = runCatching {
				fetchSourcePageStats(url)
			}.getOrNull() ?: return@launch
			if (sourceStatsRequestUrl != url || viewModel.getMangaOrNull()?.id != manga.id) {
				return@launch
			}
			stats.rating?.let { viewBinding.textViewRating?.text = it }
			stats.follows?.let { viewBinding.textViewFollows?.text = it }
		}
	}

	private suspend fun fetchSourcePageStats(url: String): SourcePageStats = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(url)
			.get()
			.build()
		okHttpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				return@withContext SourcePageStats()
			}
			parseSourcePageStats(response.body?.string().orEmpty(), url)
		}
	}

	private fun parseSourcePageStats(html: String, url: String): SourcePageStats {
		if (html.isBlank()) {
			return SourcePageStats()
		}
		val document = Jsoup.parse(html, url)
		val text = document.body()?.text().orEmpty()
		return SourcePageStats(
			rating = extractRating(document.selectFirst("[itemprop=ratingValue]")?.textOrContent())
				?: extractRating(document.selectFirst("meta[itemprop=ratingValue]")?.textOrContent())
				?: extractRating(document.selectFirst(".rating, .score, .post-rating, .series-rating")?.textOrContent())
				?: extractRating(RATING_TEXT_REGEX.find(text)?.groupValues?.getOrNull(2))
				?: extractRating(RATING_JSON_REGEX.find(html)?.groupValues?.getOrNull(1)),
			follows = extractFollowCount(FOLLOW_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1))
				?: extractFollowCount(FOLLOW_JSON_REGEX.find(html)?.groupValues?.getOrNull(1)),
		)
	}

	private fun org.jsoup.nodes.Element.textOrContent(): String {
		return attr("content").ifBlank { text() }
	}

	private fun extractRating(raw: String?): String? {
		val match = raw?.let { RATING_VALUE_REGEX.find(it) } ?: return null
		val value = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
		val scale = match.groupValues.getOrNull(2)?.toFloatOrNull()
		val normalized = when {
			scale != null && scale > 0f -> value / scale * 5f
			value > 5f && value <= 10f -> value / 10f * 5f
			value > 10f && value <= 100f -> value / 100f * 5f
			else -> value
		}.coerceIn(0f, 5f)
		return String.format(Locale.US, "%.1f", normalized)
	}

	private fun extractFollowCount(raw: String?): String? {
		val match = raw?.let { COMPACT_NUMBER_REGEX.find(it.trim()) } ?: return null
		val base = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
		val multiplier = when (match.groupValues.getOrNull(2)?.uppercase(Locale.US)) {
			"K" -> 1_000.0
			"M" -> 1_000_000.0
			"B" -> 1_000_000_000.0
			else -> 1.0
		}
		val count = (base * multiplier).toLong()
		return count.takeIf { it > 0L }?.let {
			NumberFormat.getIntegerInstance(Locale.US).format(it)
		}
	}

	private fun Any.readNumericProperty(vararg methodNames: String): Long? {
		for (methodName in methodNames) {
			val method = javaClass.methods.firstOrNull { method ->
				method.name == methodName && method.parameterTypes.isEmpty()
			} ?: continue
			val value = method.invoke(this)
			return when (value) {
				is Number -> value.toLong()
				is String -> value.filter(Char::isDigit).toLongOrNull()
				else -> null
			}
		}
		return null
	}

	private fun MangaDetails.formatChapterCount(): String {
		val count = chapters.values.flatten().distinctBy { it.id }.size
		return if (count > 0) {
			NumberFormat.getIntegerInstance(Locale.US).format(count)
		} else {
			getString(R.string.unknown)
		}
	}

	private fun onHistoryChanged(info: HistoryInfo, isLoading: Boolean) = with(infoBinding) {
		viewBinding.buttonStartReading?.setText(
			when {
				isLoading -> R.string.loading_
				info.canContinue -> R.string._continue
				else -> R.string.start_reading
			},
		)
		viewBinding.buttonStartReading?.isEnabled = !isLoading && info.isValid
		textViewChapters.text = when {
			isLoading -> getString(R.string.loading_)
			info.currentChapter >= 0 -> getString(
				R.string.chapter_d_of_d,
				info.currentChapter + 1,
				info.totalChapters,
			).withEstimatedTime(info.estimatedTime)

			info.totalChapters == 0 -> getString(R.string.no_chapters)
			info.totalChapters == -1 -> getString(R.string.error_occurred)
			else -> resources.getQuantityStringSafe(R.plurals.chapters, info.totalChapters, info.totalChapters)
				.withEstimatedTime(info.estimatedTime)
		}
		textViewProgress.textAndVisible = if (info.percent <= 0f) {
			null
		} else {
			val displayPercent = if (ReadingProgress.isCompleted(info.percent)) 100 else (info.percent * 100f).toInt()
			getString(R.string.percent_string_pattern, displayPercent.toString())
		}

		progress.setProgressCompat(
			(progress.max * info.percent.coerceIn(0f, 1f)).roundToInt(),
			true,
		)
		textViewProgressLabel.isVisible = info.history != null
		textViewProgress.isVisible = info.history != null
		progress.isVisible = info.history != null
		hideLegacyDetailsTable()
	}

	private fun onTagsChanged(tags: Collection<ChipsView.ChipModel>) {
		viewBinding.chipsTags.isGone = true
		val summary = tags
			.asSequence()
			.mapNotNull { tag ->
				tag.title?.toString()?.takeIf { it.isNotBlank() }
					?: tag.titleResId.takeIf { it != 0 }?.let(resources::getString)
			}
			.take(4)
			.joinToString(separator = " / ") { it.uppercase(Locale.getDefault()) }
		viewBinding.textChipGenres?.text = summary
		viewBinding.textChipGenres?.isVisible = summary.isNotEmpty()
	}

	private fun loadCover(imageUrl: String?) {
		viewBinding.imageViewCover.setImageAsync(imageUrl, viewModel.getMangaOrNull())
		loadCoverBackground(imageUrl)
	}

	private fun openReader() {
		val manga = viewModel.getMangaOrNull() ?: return
		if (viewModel.historyInfo.value.isChapterMissing) {
			Toast.makeText(this, R.string.chapter_is_missing, Toast.LENGTH_SHORT).show()
			return
		}
		router.openReader(
			ReaderIntent.Builder(this)
				.manga(manga)
				.branch(viewModel.selectedBranchValue)
				.build(),
		)
	}

	private fun hideLegacyDetailsTable() = with(infoBinding) {
		cardDetails.isGone = true
		textViewSourceLabel.isGone = true
		textViewSource.isGone = true
		textViewAuthorLabel.isGone = true
		textViewAuthor.isGone = true
		textViewTranslationLabel.isGone = true
		textViewTranslation.isGone = true
		textViewRatingLabel.isGone = true
		ratingBarRating.isGone = true
		textViewStateLabel.isGone = true
		textViewState.isGone = true
		textViewChaptersLabel.isGone = true
		textViewChapters.isGone = true
		textViewLocalLabel.isGone = true
		textViewLocal.isGone = true
		textViewProgressLabel.isGone = true
		progress.isGone = true
		textViewProgress.isGone = true
	}

	private fun loadCoverBackground(imageUrl: String?) {
		val bgView = viewBinding.imageViewCoverBg
		if (imageUrl == null) {
			bgView.isVisible = false
			return
		}
		val manga = viewModel.getMangaOrNull()
		ImageRequest.Builder(this)
			.data(imageUrl)
			.lifecycle(this)
			.crossfade(true)
			.allowRgb565(true)
			.target(ImageViewTarget(bgView))
			.mangaSourceExtra(manga?.source)
			.enqueueWith(coil)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			bgView.setRenderEffect(
				RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP),
			)
		}
		val surfaceColor = getThemeColor(materialR.attr.colorSurface)
		val halfAlpha = ColorUtils.setAlphaComponent(surfaceColor, 128)
		bgView.foreground = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(surfaceColor, halfAlpha, Color.TRANSPARENT, Color.TRANSPARENT, halfAlpha, surfaceColor),
		)
		configureTransparentAppBar()
		viewBinding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
			bgView.translationY = -scrollY.toFloat()
		}
		bgView.isVisible = true
	}

	private fun configureTransparentAppBar() {
		viewBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
		viewBinding.appbar.setStatusBarForegroundColor(Color.TRANSPARENT)
		viewBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)
	}

	private fun String.withEstimatedTime(time: ReadingTime?): String {
		if (time == null) {
			return this
		}
		val timeFormatted = time.formatShort(resources)
		return getString(R.string.chapters_time_pattern, this, timeFormatted)
	}

	private fun Manga.getAuthorsString(): SpannedString? {
		if (authors.isEmpty()) {
			return null
		}
		return buildSpannedString {
			authors.forEach { a ->
				if (a.isNotEmpty()) {
					if (isNotEmpty()) {
						append(", ")
					}
					inSpans(AuthorSpan(this@DetailsActivity)) {
						append(a)
					}
				}
			}
		}.nullIfEmpty()
	}

	private data class FavoriteStatusColors(
		val background: Int,
		val stroke: Int,
		val foreground: Int,
	)

	private class PrefetchObserver(
		private val context: Context,
	) : FlowCollector<List<ChapterListItem>?> {

		private var isCalled = false

		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty()) {
				return
			}
			if (!isCalled) {
				isCalled = true
				val item = value.find { it.isCurrent } ?: value.first()
				MangaPrefetchService.prefetchPages(context, item.chapter)
			}
		}
	}

	companion object {

		private const val FAV_LABEL_LIMIT = 16
		private val RATING_VALUE_REGEX = Regex(
			pattern = """([0-9]+(?:\.[0-9]+)?)\s*(?:/|out\s+of)?\s*([0-9]+(?:\.[0-9]+)?)?""",
			option = RegexOption.IGNORE_CASE,
		)
		private val RATING_TEXT_REGEX = Regex(
			pattern = """\b(rating|score)\b[^\d]{0,24}([0-9]+(?:\.[0-9]+)?(?:\s*/\s*[0-9]+(?:\.[0-9]+)?)?)""",
			option = RegexOption.IGNORE_CASE,
		)
		private val RATING_JSON_REGEX = Regex(
			pattern = """"(?:rating|score|ratingValue)"\s*:\s*"?([0-9]+(?:\.[0-9]+)?(?:\s*/\s*[0-9]+(?:\.[0-9]+)?)?)"?""",
			option = RegexOption.IGNORE_CASE,
		)
		private val FOLLOW_TEXT_REGEX = Regex(
			pattern = """\b(?:follows|followers|followed\s+by)\b[^\d]{0,28}([0-9][0-9,]*(?:\.[0-9]+)?\s*[kmb]?)""",
			option = RegexOption.IGNORE_CASE,
		)
		private val FOLLOW_JSON_REGEX = Regex(
			pattern = """"(?:follows|followers|followCount|followersCount)"\s*:\s*"?([0-9][0-9,]*(?:\.[0-9]+)?\s*[kmb]?)"?""",
			option = RegexOption.IGNORE_CASE,
		)
		private val COMPACT_NUMBER_REGEX = Regex(
			pattern = """([0-9][0-9,]*(?:\.[0-9]+)?)\s*([KMB])?""",
			option = RegexOption.IGNORE_CASE,
		)
	}

	private data class SourcePageStats(
		val rating: String? = null,
		val follows: String? = null,
	)
}
