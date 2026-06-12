package org.koitharu.kotatsu.parsers.site.en

import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.*
import java.io.IOException

class HentaiMangaParser(
	private val context: MangaLoaderContextImpl,
	private val delegate: MangaParser
) : MangaParser by delegate {

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		throw IOException("This website is permanently offline.")
	}

	override suspend fun getDetails(manga: Manga): Manga {
		throw IOException("This website is permanently offline.")
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		throw IOException("This website is permanently offline.")
	}
}
