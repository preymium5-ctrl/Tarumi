package org.koitharu.kotatsu.details.ui.adapter

import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.databinding.ItemChapterBinding
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun chapterListItemAD(
	clickListener: OnListItemClickListener<ChapterListItem>,
	onDownloadClick: (ChapterListItem) -> Unit,
) = adapterDelegateViewBinding<ChapterListItem, ListModel, ItemChapterBinding>(
	viewBinding = { inflater, parent -> ItemChapterBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is ChapterListItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)

	bind { payloads ->
		if (payloads.isEmpty()) {
			binding.textViewTitle.text = item.getTitle(context.resources)
			binding.textViewDate.text = item.formattedDate()
			binding.textViewSource.text = "${item.chapter.source.getTitle(context).uppercase(Locale.getDefault())} | EN"
		}
		val currentProgress = item.progressPercent.takeIf { item.isCurrent && it >= 0f }?.coerceIn(0f, 1f)
		val read = currentProgress == 1f
		binding.progressChapter.progress = when {
			currentProgress != null -> (currentProgress * 100f).toInt().coerceIn(0, 100)
			else -> 0
		}
		binding.textViewStatus.text = when {
			currentProgress != null && currentProgress < 1f -> "READING"
			read -> "READ"
			else -> "UNREAD"
		}
		when {
			item.isCurrent -> {
				binding.textViewTitle.drawableStart = ContextCompat.getDrawable(context, R.drawable.ic_current_chapter)
				binding.textViewTitle.setTextColor(ContextCompat.getColor(context, R.color.taru_text_primary))
				binding.textViewDate.setTextColor(ContextCompat.getColor(context, R.color.taru_text_secondary))
				binding.textViewSource.setTextColor(ContextCompat.getColor(context, R.color.taru_text_muted))
				binding.textViewTitle.typeface = Typeface.DEFAULT_BOLD
				binding.textViewStatus.alpha = 1f
			}

			item.isUnread -> {
				binding.textViewTitle.drawableStart = null
				binding.textViewTitle.setTextColor(ContextCompat.getColor(context, R.color.taru_text_primary))
				binding.textViewDate.setTextColor(ContextCompat.getColor(context, R.color.taru_text_secondary))
				binding.textViewSource.setTextColor(ContextCompat.getColor(context, R.color.taru_text_muted))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewStatus.alpha = 0.92f
			}

			else -> {
				binding.textViewTitle.drawableStart = null
				binding.textViewTitle.setTextColor(ContextCompat.getColor(context, R.color.taru_text_secondary))
				binding.textViewDate.setTextColor(ContextCompat.getColor(context, R.color.taru_text_muted))
				binding.textViewSource.setTextColor(ContextCompat.getColor(context, R.color.taru_text_soft))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewStatus.alpha = 0.78f
			}
		}
		binding.textViewNew.isVisible = item.isNew && item.isUnread
		binding.imageViewPlay.isVisible = !item.isDownloaded
		binding.imageViewPlay.setOnClickListener {
			onDownloadClick(item)
		}
		binding.imageViewBookmarked.isVisible = item.isBookmarked
		binding.imageViewDownloaded.isVisible = item.isDownloaded
	}
}

private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

private fun ChapterListItem.formattedDate(): String {
	return if (chapter.uploadDate > 0L) {
		chapterDateFormat.format(Date(chapter.uploadDate))
	} else {
		"Unknown date"
	}
}
