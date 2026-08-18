package org.koitharu.kotatsu.settings.sources.quality

import org.koitharu.kotatsu.core.parser.SourceDiagnostics
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaParserSource

data class MetadataQualityItem(
	val source: MangaParserSource,
	val report: SourceDiagnostics?,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is MetadataQualityItem && other.source == source
	}
}
