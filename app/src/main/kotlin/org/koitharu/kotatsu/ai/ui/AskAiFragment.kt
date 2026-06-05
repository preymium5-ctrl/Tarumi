package org.koitharu.kotatsu.ai.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentAskAiBinding
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.parsers.model.Manga

@AndroidEntryPoint
class AskAiFragment : BaseFragment<FragmentAskAiBinding>() {

	private val viewModel by viewModels<AskAiViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): FragmentAskAiBinding = FragmentAskAiBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentAskAiBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.buttonAsk.setOnClickListener { submit() }
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
		viewBinding?.askAiRoot?.updatePadding(
			left = bars.left + 14.dp(v),
			right = bars.right + 14.dp(v),
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

	private fun renderState(state: AskAiState) {
		val binding = viewBinding ?: return
		binding.progress.isVisible = state.isLoading
		binding.textMode.setText(if (state.includeNsfw) R.string.ask_ai_nsfw_mode else R.string.ask_ai_safe_mode)
		if (binding.switchNsfw.isChecked != state.includeNsfw) {
			binding.switchNsfw.isChecked = state.includeNsfw
		}
		renderMessages(binding.messagesList, state.messages)
		binding.messagesScroll.post { binding.messagesScroll.fullScroll(View.FOCUS_DOWN) }
	}

	private fun renderMessages(container: LinearLayout, messages: List<AskAiMessage>) {
		container.removeAllViews()
		for ((index, message) in messages.withIndex()) {
			when (message.role) {
				AskAiRole.USER -> addUserBubble(container, message.text, index)
				AskAiRole.ASSISTANT -> addAssistantMessage(container, message, index)
			}
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
			this.text = message.text
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
		if (message.results.isNotEmpty()) {
			addResults(container, message.query, message.results)
		}
	}

	private fun addResults(container: LinearLayout, query: String, results: List<Manga>) {
		val inflater = LayoutInflater.from(container.context)
		for ((index, manga) in results.withIndex()) {
			val view = inflater.inflate(R.layout.item_ask_ai_result, container, false)
			view.findViewById<CoverImageView>(R.id.imageView_cover)
				.setImageAsync(manga.largeCoverUrl?.ifEmpty { manga.coverUrl } ?: manga.coverUrl, manga)
			view.findViewById<TextView>(R.id.textView_title).text = manga.title
			view.findViewById<TextView>(R.id.textView_type).text = manga.detectComicType().label
			view.findViewById<TextView>(R.id.textView_source).text = manga.source.getTitle(view.context)
			view.findViewById<TextView>(R.id.textView_reason).text = manga.buildReason(query)
			view.setOnClickListener { router.openDetails(manga) }
			container.addView(
				view,
				LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				).apply {
					topMargin = if (index == 0) 10.dp(container) else 12.dp(container)
				},
			)
		}
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

	private fun Int.dp(view: View): Int = (this * view.resources.displayMetrics.density).toInt()
}
