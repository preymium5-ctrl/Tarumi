package org.koitharu.kotatsu.ai.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentAskAiBinding
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag

@AndroidEntryPoint
class AskAiFragment : BaseFragment<FragmentAskAiBinding>() {

	private val viewModel by viewModels<AskAiViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): FragmentAskAiBinding = FragmentAskAiBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentAskAiBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.buttonBack.setOnClickListener {
			requireActivity().onBackPressedDispatcher.onBackPressed()
		}
		binding.buttonAsk.setOnClickListener { submit() }
		binding.buttonClearChat.setOnClickListener { viewModel.clearConversation() }
		binding.buttonComposerToggle.setOnClickListener {
			viewModel.setComposerExpanded(!viewModel.state.value.isComposerExpanded)
		}
		binding.buttonLocalModel.setOnClickListener { confirmLocalModelDownload() }
		binding.modeControls.setOnClickListener {
			binding.switchNsfw.isChecked = !binding.switchNsfw.isChecked
		}
		binding.textMode.setOnClickListener {
			binding.switchNsfw.isChecked = !binding.switchNsfw.isChecked
		}
		binding.switchNsfw.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setNsfw(isChecked)
			binding.textMode.setText(if (isChecked) R.string.ask_ai_nsfw_mode else R.string.ask_ai_safe_mode)
		}
		binding.editQuery.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_SEARCH) {
				submit()
				true
			} else {
				false
			}
		}
		viewModel.state.observe(viewLifecycleOwner, ::renderState)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.systemBarsInsets
		val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
		val visibleBottomNavHeight = (activity as? BottomNavOwner)
			?.bottomNav
			?.takeIf { it.isShownOrShowing }
			?.height ?: 0
		viewBinding?.askAiRoot?.updatePadding(
			left = bars.left + 14.dp(v),
			top = bars.top + 12.dp(v),
			right = bars.right + 14.dp(v),
			bottom = maxOf(bars.bottom, ime.bottom) + visibleBottomNavHeight + 14.dp(v),
		)
		return insets.consumeAllSystemBarsInsets()
	}

	private fun submit() {
		val binding = viewBinding ?: return
		val query = binding.editQuery.text?.toString().orEmpty()
		if (query.isBlank()) {
			return
		}
		binding.editQuery.text?.clear()
		viewModel.ask(query)
	}

	private fun confirmLocalModelDownload() {
		val context = context ?: return
		val size = FileSize.BYTES.format(context, viewModel.localModelSizeBytes())
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.ask_ai_local_model_confirm_title)
			.setMessage(getString(R.string.ask_ai_local_model_confirm_message, size))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.ask_ai_local_model_confirm_download) { _, _ -> viewModel.downloadLocalModel() }
			.show()
	}

	private fun renderState(state: AskAiState) {
		val binding = viewBinding ?: return
		binding.progress.isVisible = false
		binding.thinkingRow.isVisible = false
		binding.tokenProgress.isVisible = true
		binding.tokenProgress.max = state.maxTokens
		binding.tokenProgress.progress = state.remainingTokens
		binding.textTokenLabel.setText(R.string.ask_ai_tokens_label)
		binding.textTokenUsage.text = getString(
			R.string.ask_ai_tokens,
			state.remainingTokens,
			state.maxTokens,
		)
		binding.textTokenReset.text = getString(
			R.string.ask_ai_tokens_reset,
			formatDurationUntil(state.tokenResetAtMillis),
		) + " • " + getString(R.string.ask_ai_memory_note)
		binding.tokenCard.isVisible = state.isComposerExpanded
		binding.introRow.isVisible = state.isComposerExpanded
		binding.modeControls.isVisible = state.isComposerExpanded
		binding.localModelCard.isVisible = state.isComposerExpanded
		binding.buttonComposerToggle.setIconResource(
			if (state.isComposerExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up,
		)
		binding.buttonComposerToggle.setContentDescription(
			getString(if (state.isComposerExpanded) R.string.ask_ai_minimize else R.string.ask_ai_expand),
		)
		binding.textMode.setText(if (state.includeNsfw) R.string.ask_ai_nsfw_mode else R.string.ask_ai_safe_mode)
		if (binding.switchNsfw.isChecked != state.includeNsfw) {
			binding.switchNsfw.isChecked = state.includeNsfw
		}
		renderLocalModelStatus(state.localModelStatus)
		renderMessages(binding.messagesList, state.messages, state.isLoading, state.includeNsfw)
		binding.messagesScroll.post { binding.messagesScroll.fullScroll(View.FOCUS_DOWN) }
	}

	private fun renderLocalModelStatus(status: LocalAiModelStatus) {
		val binding = viewBinding ?: return
		when (status) {
			LocalAiModelStatus.NotConfigured -> {
				binding.textLocalModelStatus.setText(R.string.ask_ai_local_model_not_configured)
				binding.buttonLocalModel.isVisible = false
			}
			LocalAiModelStatus.NotDownloaded -> {
				binding.textLocalModelStatus.text = getString(
					R.string.ask_ai_local_model_not_downloaded,
				) + "\n" + getString(R.string.ask_ai_cloud_fallback)
				binding.buttonLocalModel.isVisible = true
				binding.buttonLocalModel.setText(R.string.ask_ai_download_local_model)
				binding.buttonLocalModel.isEnabled = true
			}
			is LocalAiModelStatus.Downloading -> {
				binding.textLocalModelStatus.text = getString(
					R.string.ask_ai_local_model_downloading,
					status.progress,
				)
				binding.buttonLocalModel.isVisible = true
				binding.buttonLocalModel.isEnabled = false
			}
			LocalAiModelStatus.Ready -> {
				binding.textLocalModelStatus.setText(R.string.ask_ai_local_model_ready)
				binding.buttonLocalModel.isVisible = false
			}
			is LocalAiModelStatus.Error -> {
				binding.textLocalModelStatus.text = getString(
					R.string.ask_ai_local_model_error,
					status.message,
				) + "\n" + getString(R.string.ask_ai_cloud_fallback)
				binding.buttonLocalModel.isVisible = true
				binding.buttonLocalModel.setText(R.string.ask_ai_retry_local_model)
				binding.buttonLocalModel.isEnabled = true
			}
		}
	}

	private fun renderMessages(
		container: LinearLayout,
		messages: List<AskAiMessage>,
		isLoading: Boolean,
		includeNsfw: Boolean,
	) {
		container.removeAllViews()
		for ((index, message) in messages.withIndex()) {
			when (message.role) {
				AskAiRole.USER -> addUserBubble(container, message.text, index)
				AskAiRole.ASSISTANT -> addAssistantMessage(container, message, index)
			}
		}
		if (isLoading && messages.lastOrNull()?.isStreaming != true) {
			addTypingBubble(container, includeNsfw, messages.size)
		}
	}

	private fun addUserBubble(container: LinearLayout, text: String, index: Int) {
		val view = TextView(container.context).apply {
			setBackgroundResource(R.drawable.bg_taru_page_active)
			setTextColor(ContextCompat.getColor(context, android.R.color.white))
			textSize = 15f
			typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
			setPadding(16.dp(this), 12.dp(this), 16.dp(this), 12.dp(this))
			maxWidth = 292.dp(this)
			this.text = text
		}
		container.addView(
			view,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				gravity = Gravity.END
				topMargin = if (index == 0) 0 else 12.dp(container)
			},
		)
	}

	private fun addAssistantMessage(container: LinearLayout, message: AskAiMessage, index: Int) {
		val row = LinearLayout(container.context).apply {
			gravity = Gravity.TOP
			orientation = LinearLayout.HORIZONTAL
		}
		val avatar = ImageView(container.context).apply {
			setImageResource(if (message.includeNsfw) R.drawable.ask_ai_nsfw_avatar else R.drawable.ask_ai_safe_avatar)
			scaleType = ImageView.ScaleType.CENTER_CROP
			setBackgroundResource(R.drawable.bg_taru_home_chip)
			setPadding(4.dp(this), 4.dp(this), 4.dp(this), 4.dp(this))
		}
		row.addView(
			avatar,
			LinearLayout.LayoutParams(42.dp(container), 42.dp(container)).apply {
				marginEnd = 10.dp(container)
			},
		)
		val messageColumn = LinearLayout(container.context).apply {
			orientation = LinearLayout.VERTICAL
		}
		val bubble = TextView(container.context).apply {
			setBackgroundResource(R.drawable.bg_taru_home_feature)
			setTextColor(ContextCompat.getColor(context, R.color.taru_text_primary))
			textSize = 15f
			typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
			setLineSpacing(2.dp(this).toFloat(), 1f)
			setPadding(18.dp(this), 14.dp(this), 18.dp(this), 14.dp(this))
			this.text = if (message.isStreaming) "${message.text} |" else message.text
		}
		messageColumn.addView(
			bubble,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			),
		)
		row.addView(
			messageColumn,
			LinearLayout.LayoutParams(
				0,
				LinearLayout.LayoutParams.WRAP_CONTENT,
				1f,
			),
		)
		container.addView(
			row,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				topMargin = if (index == 0) 0 else 12.dp(container)
			},
		)
		when {
			message.results.isNotEmpty() -> addResults(container, message.results)
			message.resultCards.isNotEmpty() -> addResultCards(container, message.resultCards)
		}
	}

	private fun addTypingBubble(container: LinearLayout, includeNsfw: Boolean, index: Int) {
		val row = LinearLayout(container.context).apply {
			gravity = Gravity.TOP
			orientation = LinearLayout.HORIZONTAL
		}
		val avatar = ImageView(container.context).apply {
			setImageResource(if (includeNsfw) R.drawable.ask_ai_nsfw_avatar else R.drawable.ask_ai_safe_avatar)
			scaleType = ImageView.ScaleType.CENTER_CROP
			setBackgroundResource(R.drawable.bg_taru_home_chip)
			setPadding(4.dp(this), 4.dp(this), 4.dp(this), 4.dp(this))
		}
		row.addView(
			avatar,
			LinearLayout.LayoutParams(42.dp(container), 42.dp(container)).apply {
				marginEnd = 10.dp(container)
			},
		)
		val bubble = TextView(container.context).apply {
			setBackgroundResource(R.drawable.bg_taru_home_feature)
			setTextColor(ContextCompat.getColor(context, R.color.taru_text_secondary))
			textSize = 14f
			typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
			setPadding(18.dp(this), 12.dp(this), 18.dp(this), 12.dp(this))
			setText(R.string.ask_ai_typing)
		}
		row.addView(
			bubble,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			),
		)
		container.addView(
			row,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				topMargin = if (index == 0) 0 else 12.dp(container)
			},
		)
	}

	private fun addResults(container: LinearLayout, results: List<Manga>) {
		val inflater = LayoutInflater.from(container.context)
		val scrollView = HorizontalScrollView(container.context).apply {
			isHorizontalScrollBarEnabled = false
			clipToPadding = false
			setPadding(0, 0, 8.dp(this), 0)
		}
		val rail = LinearLayout(container.context).apply {
			orientation = LinearLayout.HORIZONTAL
		}
		for ((index, manga) in results.withIndex()) {
			val view = inflater.inflate(R.layout.item_home_recommendation_cover, rail, false)
			view.findViewById<CoverImageView>(R.id.imageView_cover)
				.setImageAsync(manga.largeCoverUrl?.ifEmpty { manga.coverUrl } ?: manga.coverUrl, manga)
			view.findViewById<TextView>(R.id.textView_title).text = manga.title
			view.findViewById<TextView>(R.id.textView_type).text = manga.detectComicType().label
			view.setOnClickListener { router.openDetails(manga) }
			rail.addView(
				view,
				LinearLayout.LayoutParams(
					128.dp(container),
					LinearLayout.LayoutParams.WRAP_CONTENT,
				).apply {
					marginEnd = if (index == results.lastIndex) 0 else 14.dp(container)
				},
			)
		}
		scrollView.addView(
			rail,
			ViewGroup.LayoutParams(
				WRAP_CONTENT,
				WRAP_CONTENT,
			),
		)
		container.addView(
			scrollView,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				topMargin = 10.dp(container)
			},
		)
	}

	private fun Manga.buildReason(query: String): String {
		val queryWords = query.lowercase().split(Regex("\\s+|,")).filter { it.length >= 2 }
		val matchedTags = tags
			.filter { tag -> queryWords.any { word -> tag.title.contains(word, ignoreCase = true) } }
			.take(3)
			.joinToString { it.title }
		return when {
			matchedTags.isNotEmpty() -> getString(R.string.ask_ai_reason_tags, matchedTags)
			title.contains(query, ignoreCase = true) -> getString(R.string.ask_ai_reason_title)
			else -> getString(R.string.ask_ai_reason_source)
		}
	}

	private fun addResultCards(container: LinearLayout, results: List<AskAiResultCard>) {
		val inflater = LayoutInflater.from(container.context)
		val scrollView = HorizontalScrollView(container.context).apply {
			isHorizontalScrollBarEnabled = false
			clipToPadding = false
			setPadding(0, 0, 8.dp(this), 0)
		}
		val rail = LinearLayout(container.context).apply {
			orientation = LinearLayout.HORIZONTAL
		}
		for ((index, card) in results.withIndex()) {
			val view = inflater.inflate(R.layout.item_home_recommendation_cover, rail, false)
			view.findViewById<CoverImageView>(R.id.imageView_cover)
				.setImageAsync(card.largeCoverUrl?.ifEmpty { card.coverUrl } ?: card.coverUrl, null)
			view.findViewById<TextView>(R.id.textView_title).text = card.title
			view.findViewById<TextView>(R.id.textView_type).text = card.typeLabel
			view.setOnClickListener {
				card.toMangaOrNull()?.let { manga -> router.openDetails(manga) }
			}
			rail.addView(
				view,
				LinearLayout.LayoutParams(
					128.dp(container),
					LinearLayout.LayoutParams.WRAP_CONTENT,
				).apply {
					marginEnd = if (index == results.lastIndex) 0 else 14.dp(container)
				},
			)
		}
		scrollView.addView(
			rail,
			ViewGroup.LayoutParams(
				WRAP_CONTENT,
				WRAP_CONTENT,
			),
		)
		container.addView(
			scrollView,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				topMargin = 10.dp(container)
			},
		)
	}

	private fun Int.dp(view: View): Int = (this * view.resources.displayMetrics.density).toInt()

	private fun AskAiResultCard.toMangaOrNull(): Manga? {
		val parserSource = MangaParserSource.entries.firstOrNull { it.name == sourceName } ?: return null
		return Manga(
			id = id,
			title = title,
			altTitles = altTitles.toSet(),
			url = url,
			publicUrl = publicUrl,
			rating = rating,
			contentRating = runCatching { ContentRating.valueOf(contentRating) }.getOrDefault(ContentRating.SAFE),
			coverUrl = coverUrl,
			tags = tags.mapTo(linkedSetOf()) { MangaTag(it.title, it.key, parserSource) },
			state = runCatching { MangaState.valueOf(state) }.getOrDefault(MangaState.ONGOING),
			authors = authors.toSet(),
			largeCoverUrl = largeCoverUrl,
			description = description,
			chapters = emptyList(),
			source = parserSource,
		)
	}

	private fun formatDurationUntil(targetMillis: Long): String {
		val remaining = (targetMillis - System.currentTimeMillis()).coerceAtLeast(0L)
		val totalMinutes = (remaining + 59_999L) / 60_000L
		val hours = totalMinutes / 60L
		val minutes = totalMinutes % 60L
		return when {
			hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
			hours > 0L -> "${hours}h"
			minutes > 0L -> "${minutes}m"
			else -> getString(R.string.less_than_minute)
		}
	}
}
