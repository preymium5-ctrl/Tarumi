package org.koitharu.kotatsu.tracker.ui.feed.adapter

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.databinding.ItemFeedBinding
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem

fun feedItemAD(
	clickListener: OnListItemClickListener<FeedItem>,
) = adapterDelegateViewBinding<FeedItem, ListModel, ItemFeedBinding>(
	{ inflater, parent -> ItemFeedBinding.inflate(inflater, parent, false) },
) {
	itemView.setOnClickListener {
		clickListener.onItemClick(item, it)
	}

	bind {
		binding.imageViewCover.setImageAsync(item.imageUrl, item.manga.source)
		val chapterTitle = item.latestChapter.ifBlank {
			context.resources.getQuantityStringSafe(
				R.plurals.new_chapters,
				item.count,
				item.count,
			)
		}
		binding.textViewTitle.text = if (item.latestChapter.isBlank()) {
			item.title
		} else {
			context.getString(R.string.notification_chapter_of_manga, chapterTitle, item.title)
		}
		binding.textViewSummary.text = itemView.context.getString(R.string.notification_update_available, chapterTitle)
		binding.textViewTime.text = calculateTimeAgo(item.createdAt)?.format(context) ?: context.getString(R.string.unknown)
		binding.textViewNew.isVisible = item.isNew
		binding.textViewType.text = item.manga.detectComicType().label
	}
}
