package org.koitharu.kotatsu.settings.sources.health

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.databinding.ItemSourceHealthBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.text.DateFormat
import java.util.Date

class SourceHealthAdapter(
	listener: OnListItemClickListener<SourceHealthItem>,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.CHAPTER_LIST, sourceHealthItemAD(listener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}
}

private fun sourceHealthItemAD(
	listener: OnListItemClickListener<SourceHealthItem>,
) = adapterDelegateViewBinding<SourceHealthItem, ListModel, ItemSourceHealthBinding>(
	{ inflater, parent -> ItemSourceHealthBinding.inflate(inflater, parent, false) },
) {
	binding.root.setOnClickListener { view ->
		listener.onItemClick(item, view)
	}

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewDescription.text = item.buildDescription(context)
		binding.imageViewIcon.setImageAsync(item.source)
		binding.textViewStatus.setText(item.status.titleResId)
		binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(
			ContextCompat.getColor(context, item.status.colorResId),
		)
	}
}

private fun SourceHealthItem.buildDescription(context: Context): String {
	val base = source.getSummary(context).orEmpty()
	val state = context.getString(
		if (isEnabled) R.string.source_health_enabled else R.string.source_health_disabled,
	)
	return buildString {
		append(base)
		append(" - ")
		append(state)
		if (isPinned) {
			append(" - ")
			append(context.getString(R.string.source_health_pinned))
		}
		if (source.isNsfw()) {
			append(" - 18+")
		}
		diagnostics?.let { report ->
			if (report.lastCheckedAt > 0L) {
				append(" - ")
				append(
					context.getString(
						R.string.source_health_last_checked_pattern,
						DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.lastCheckedAt)),
					),
				)
			}
			if (report.recentChecks > 0) {
				append(" - ")
				append(
					context.getString(
						R.string.source_health_recent_log_pattern,
						report.recentItemsFound,
						report.recentFailures,
						report.consecutiveFailures,
					),
				)
			}
		}
	}
}
