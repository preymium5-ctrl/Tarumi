package org.koitharu.kotatsu.home.ui

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.Locale

enum class ComicType(val label: String) {
	MANHUA("Manhua"),
	MANHWA("Manhwa"),
	MANGA("Manga"),
	CGI("CGI"),
	COMIC("Comic"),
}

/**
 * Infer comic format for UI chips.
 *
 * Priority:
 * 1) Known source defaults / [MangaParserSource.contentType]
 * 2) Explicit type tags
 * 3) Whole-word labels in metadata (manhwa/webtoon before manhua — manhua was winning incorrectly)
 */
fun Manga.detectComicType(): ComicType {
	val parserSource = source as? MangaParserSource
	val sourceType = parserSource?.contentType

	// Strong source defaults first (don't let noisy description SEO override).
	val sourceKey = parserSource?.name?.normalizedKey().orEmpty()
	when {
		sourceKey.contains("asura") ||
			sourceKey.contains("demonic") ||
			sourceKey.contains("flame") ||
			sourceKey.contains("manhwaz") ||
			sourceKey == "aquamanga" ||
			sourceKey.contains("manhuafast") -> return ComicType.MANHWA
		sourceKey.contains("manhuaplus") ||
			sourceKey.contains("manhuaga") ||
			(sourceKey.contains("manhua") && !sourceKey.contains("manhwa") && !sourceKey.contains("manhuafast")) ->
			return ComicType.MANHUA
		// MangaPlus / MangaFire are mixed catalogs — fall through to tags/description.
	}

	when (sourceType) {
		ContentType.MANHWA -> return ComicType.MANHWA
		ContentType.MANHUA -> return ComicType.MANHUA
		// Gallery CG types (artist/game CGI + image sets) — surface as CGI in the type chip.
		ContentType.ARTIST_CG,
		ContentType.GAME_CG,
		ContentType.IMAGE_SET,
		-> return ComicType.CGI
		ContentType.MANGA,
		ContentType.ONE_SHOT,
		ContentType.DOUJINSHI,
		-> {
			// Fall through — some "manga" sources host manhwa too.
		}
		else -> Unit
	}

	val typeFromTags = tags.firstNotNullOfOrNull { tag ->
		val t = tag.title.lowercase(Locale.ROOT).trim()
		val k = tag.key.lowercase(Locale.ROOT).trim()
		when {
			t == "manhwa" || k == "manhwa" || t == "webtoon" || k == "webtoon" -> ComicType.MANHWA
			t == "manhua" || k == "manhua" -> ComicType.MANHUA
			t == "manga" || k == "manga" -> ComicType.MANGA
			// CGI / motion / 3D-generated comic tags from parsers
			t == "cgi" || k == "cgi" ||
				t == "artist cg" || k == "artist cg" || k == "artist_cg" ||
				t == "game cg" || k == "game cg" || k == "game_cg" ||
				t.contains("3d") && (t.contains("cgi") || t.contains("render")) ||
				t.contains("motion comic") || t.contains("animated") && t.contains("cgi") ||
				k.contains("artistcg") || k.contains("gamecg")
				-> ComicType.CGI
			else -> null
		}
	}
	if (typeFromTags != null) {
		return typeFromTags
	}

	val labels = buildList {
		for (tag in tags) {
			add(tag.title.cleanTypeNoise(source))
			add(tag.key.cleanTypeNoise(source))
		}
		// Do NOT add raw source.name — names like MANHUAFAsT / manhuaplus mis-classify manhwa.
		add(title)
		description?.cleanTypeNoise(source)?.let(::add)
	}.map { it.lowercase(Locale.ROOT) }

	val hasCgi = labels.any {
		it.hasWholeWord("cgi") ||
			it.contains("artist cg") ||
			it.contains("game cg") ||
			it.contains("motion comic") ||
			it.contains("3d cgi") ||
			it.contains("3d render")
	}
	val hasManhwa = labels.any {
		it.hasWholeWord("manhwa") ||
			it.hasWholeWord("webtoon") ||
			it.contains("korean webcomic") ||
			it.contains("korean comic")
	}
	val hasManhua = labels.any {
		(it.hasWholeWord("manhua") || it.contains("chinese webcomic") || it.contains("chinese comic")) &&
			!it.contains("manhuafast")
	}
	val hasManga = labels.any {
		it.hasWholeWord("manga") ||
			it.hasWholeWord("shounen") ||
			it.hasWholeWord("seinen") ||
			it.hasWholeWord("shoujo") ||
			it.hasWholeWord("josei")
	}

	// Manhwa before manhua — previously manhua matched first and mislabeled manhwa titles.
	return when {
		hasCgi -> ComicType.CGI
		hasManhwa -> ComicType.MANHWA
		hasManhua -> ComicType.MANHUA
		hasManga -> ComicType.MANGA
		sourceType == ContentType.MANHWA -> ComicType.MANHWA
		sourceType == ContentType.MANHUA -> ComicType.MANHUA
		sourceType == ContentType.ARTIST_CG ||
			sourceType == ContentType.GAME_CG ||
			sourceType == ContentType.IMAGE_SET -> ComicType.CGI
		sourceType == ContentType.MANGA ||
			sourceType == ContentType.ONE_SHOT ||
			sourceType == ContentType.DOUJINSHI -> ComicType.MANGA
		else -> SOURCE_TYPE_HINTS[parserSource?.name?.normalizedKey()] ?: ComicType.COMIC
	}
}

private fun String.cleanTypeNoise(source: org.koitharu.kotatsu.parsers.model.MangaSource): String {
	return if (source == MangaParserSource.DEMONICSCANS) {
		replace(Regex("""(?i)\bManga\s*/\s*Manhwa\s*/\s*Manhua\b"""), " ")
	} else {
		this
	}
}

private fun String.hasWholeWord(word: String): Boolean {
	return Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(this)
}

private fun String.normalizedKey(): String =
	lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private val SOURCE_TYPE_HINTS = mapOf(
	"asurascans" to ComicType.MANHWA,
	"asurascansus" to ComicType.MANHWA,
	"asurascansgg" to ComicType.MANHWA,
	"flamecomics" to ComicType.MANHWA,
	"demonicscans" to ComicType.MANHWA,
	"manhwaz" to ComicType.MANHWA,
	"aquamanga" to ComicType.MANHWA,
	"manhuafast" to ComicType.MANHWA,
	"manhuaplus" to ComicType.MANHUA,
	"manhuaga" to ComicType.MANHUA,
	"mangaplusparseren" to ComicType.MANGA,
	"mangaplus" to ComicType.MANGA,
	"mangafireen" to ComicType.MANGA,
	"mangafire" to ComicType.MANGA,
)
