package org.koitharu.kotatsu.settings.sources.quality

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.MetadataOrigin
import org.koitharu.kotatsu.core.parser.SourceDiagnostics
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.databinding.ItemSourceHealthBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel

class MetadataQualityAdapter(
	listener: OnListItemClickListener<MetadataQualityItem>,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.CHAPTER_LIST, metadataQualityItemAD(listener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}
}

private fun metadataQualityItemAD(
	listener: OnListItemClickListener<MetadataQualityItem>,
) = adapterDelegateViewBinding<MetadataQualityItem, ListModel, ItemSourceHealthBinding>(
	{ inflater, parent -> ItemSourceHealthBinding.inflate(inflater, parent, false) },
) {
	binding.root.setOnClickListener { view -> listener.onItemClick(item, view) }

	bind {
		val report = item.report
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewDescription.text = report?.summary(context) ?: context.getString(R.string.metadata_quality_no_data)
		binding.imageViewIcon.setImageAsync(item.source)
		binding.textViewStatus.text = report?.let { "${it.qualityPercent}%" } ?: "--"
		binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(
			ContextCompat.getColor(context, report.qualityColor()),
		)
	}
}

private fun SourceDiagnostics.summary(context: Context): String {
	return context.getString(
		R.string.metadata_quality_summary_pattern,
		detailLoads,
		missingRating,
		missingAuthor,
		missingArtist,
		missingStatus,
		missingType,
		missingDescription,
		missingChapters,
		missingChapterDates,
		ratingOrigin.shortName(),
		authorOrigin.shortName(),
		typeOrigin.shortName(),
	)
}

private fun SourceDiagnostics?.qualityColor(): Int = when {
	this == null -> R.color.grey
	qualityPercent >= 85 -> R.color.common_green
	qualityPercent >= 60 -> R.color.warning
	else -> R.color.common_red
}

private fun MetadataOrigin.shortName(): String = when (this) {
	MetadataOrigin.SOURCE_PARSER -> "parser"
	MetadataOrigin.SOURCE_PAGE_FALLBACK -> "fallback"
	MetadataOrigin.INFERRED -> "inferred"
	MetadataOrigin.SMART_MATCH -> "matched"
	MetadataOrigin.UNKNOWN -> "unknown"
}
