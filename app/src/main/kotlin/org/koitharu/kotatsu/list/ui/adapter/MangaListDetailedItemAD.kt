package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.util.ext.resolveDp
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
		binding.textViewAuthor.text = item.latestChapterTitle?.let { "Latest $it" }
			?: item.latestChapterNumber?.let { "Latest $it" }
			?: "Latest unknown"
		binding.textViewCurrentChapter.text = when {
			item.currentChapterNumber != null && item.totalChapterLabel != null ->
				"Current ${item.currentChapterNumber} / ${item.totalChapterLabel}"
			item.currentChapterNumber != null -> "Current ${item.currentChapterNumber}"
			item.currentChapterTitle != null -> "Current ${item.currentChapterTitle}"
			item.trackerChapter != null -> {
				if (item.totalChapterLabel != null) {
					"Current ${item.trackerChapter} / ${item.totalChapterLabel}"
				} else {
					"Current ${item.trackerChapter}"
				}
			}
			else -> "Not started"
		}

		val context = binding.root.context
		val colors = org.koitharu.kotatsu.favourites.ui.categories.FavoriteStatusColors.getStyle(context, item.statusTitle)

		val ratingText = item.trackerRating?.let { rating ->
			if (rating > 0f) {
				val score = rating * 10f
				val formattedScore = if (score % 1f == 0f) {
					score.toInt().toString()
				} else {
					String.format(java.util.Locale.US, "%.1f", score)
				}
				" • ★ $formattedScore"
			} else {
				""
			}
		} ?: ""

		val statusColor = org.koitharu.kotatsu.favourites.ui.categories.FavoriteStatusColors.getColor(item.statusTitle ?: "Planned")

		binding.textViewStatus.text = (item.statusTitle ?: "Planned") + ratingText

		val bg = binding.textViewStatus.background?.mutate() as? android.graphics.drawable.GradientDrawable
		if (bg != null) {
			bg.setColor(statusColor)
			bg.setStroke(context.resources.resolveDp(1), statusColor)
		}
		binding.textViewStatus.setTextColor(Color.WHITE)

		val drawables = binding.textViewStatus.compoundDrawablesRelative
		drawables[2]?.mutate()?.setTint(Color.WHITE)

		binding.textViewStatus.setOnClickListener { view ->
			clickListener.onFavoriteClick(item.manga, view)
		}
		binding.buttonRemoveBookmark.isVisible = item.showRemoveAction
		binding.buttonRemoveBookmark.setOnClickListener { view ->
			clickListener.onRemoveFromFavoritesClick(item.manga, view)
		}
		binding.buttonContinueReading.isVisible = true
		binding.buttonContinueReading.text = when {
			item.currentChapterNumber != null && item.totalChapterLabel != null ->
				"${item.currentChapterNumber} / ${item.totalChapterLabel}"
			item.currentChapterNumber != null -> item.currentChapterNumber
			item.trackerChapter != null -> {
				if (item.totalChapterLabel != null) {
					"${item.trackerChapter} / ${item.totalChapterLabel}"
				} else {
					item.trackerChapter.toString()
				}
			}
			else -> "Start"
		}
		binding.buttonContinueReading.setOnClickListener { view ->
			clickListener.onReadClick(item.manga, view)
		}
		binding.progressView.isVisible = false
		with(binding.iconsView) {
			clearIcons()
			if (item.counter > 0) addIcon(R.drawable.ic_updated)
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
