package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.databinding.ItemMangaListDetailsBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel

fun mangaListDetailedItemAD(
	clickListener: MangaDetailsClickListener,
) = adapterDelegateViewBinding<MangaDetailedListModel, ListModel, ItemMangaListDetailsBinding>(
	{ inflater, parent -> ItemMangaListDetailsBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener)
		.attach(itemView)

	bind { payloads ->
		binding.textViewTitle.text = item.title
		binding.textViewAuthor.text = item.latestChapterNumber?.let { "Latest $it" }
			?: item.latestChapterTitle?.let { "Latest $it" }
			?: "Latest unknown"
		binding.textViewCurrentChapter.text = when {
			item.currentChapterNumber != null && item.totalChapters > 0 ->
				"Current ${item.currentChapterNumber} / ${item.totalChapters}"
			item.currentChapterNumber != null -> "Current ${item.currentChapterNumber}"
			item.currentChapterTitle != null -> "Current ${item.currentChapterTitle}"
			else -> "Not started"
		}
		binding.textViewStatus.text = item.statusTitle ?: "Planned"
		binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(statusColor(item.statusTitle))
		binding.textViewStatus.setOnClickListener { view ->
			clickListener.onFavoriteClick(item.manga, view)
		}
		binding.buttonContinueReading.isVisible = true
		binding.buttonContinueReading.text = when {
			item.currentChapterNumber != null && item.totalChapters > 0 ->
				"${item.currentChapterNumber} / ${item.totalChapters}"
			item.currentChapterNumber != null -> item.currentChapterNumber
			else -> "Start"
		}
		binding.buttonContinueReading.setOnClickListener { view ->
			clickListener.onReadClick(item.manga, view)
		}
		binding.progressView.isVisible = false
		with(binding.iconsView) {
			clearIcons()
			if (item.isPinned) addIcon(R.drawable.ic_pin_small)
			if (item.isSaved) addIcon(R.drawable.ic_storage)
			if (item.isFavorite) addIcon(R.drawable.ic_heart_outline)
			isVisible = iconsCount > 0
		}
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
		binding.badge.number = item.counter
		binding.badge.isVisible = item.counter > 0
	}
}

private fun statusColor(title: String?): Int = when (title?.trim()?.lowercase()) {
	"completed" -> Color.parseColor("#16854C")
	"reading" -> Color.parseColor("#246B8A")
	else -> Color.parseColor("#5F58B6")
}
