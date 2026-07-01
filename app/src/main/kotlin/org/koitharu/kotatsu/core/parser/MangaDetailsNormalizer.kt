package org.koitharu.kotatsu.core.parser

import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.util.Locale

fun Manga.normalizeDetailsMetadata(): Manga {
	val normalizedRating = rating.normalizeRating()
	val normalizedAuthors = normalizeAuthors()
	val normalizedDescription = description?.cleanDescription()?.nullIfEmpty()
	val normalizedState = state ?: inferState()
	val normalizedContentRating = contentRating ?: if (source.isNsfw()) ContentRating.ADULT else null
	val normalizedTags = normalizeTags(normalizedDescription)
	if (
		normalizedRating == rating &&
		normalizedAuthors == authors &&
		normalizedDescription == description &&
		normalizedState == state &&
		normalizedContentRating == contentRating &&
		normalizedTags == tags
	) {
		return this
	}
	return copy(
		rating = normalizedRating,
		authors = normalizedAuthors,
		description = normalizedDescription,
		state = normalizedState,
		contentRating = normalizedContentRating,
		tags = normalizedTags,
	)
}

private fun Float.normalizeRating(): Float {
	return when {
		this < 0f -> this
		this <= 1f -> this
		this <= 5f -> this / 5f
		this <= 10f -> this / 10f
		this <= 100f -> this / 100f
		else -> -1f
	}
}

private fun Manga.normalizeAuthors(): Set<String> {
	if (authors.isEmpty()) {
		return emptySet()
	}
	val result = LinkedHashSet<String>()
	val tagStopWords = tags.mapTo(HashSet()) { it.title.lowercase(Locale.ENGLISH) }
	for (raw in authors) {
		raw.extractNamedPeople("Author", tagStopWords)?.let(result::addAll)
		raw.extractNamedPeople("Artist", tagStopWords)?.let(result::addAll)
		if (result.isNotEmpty()) {
			continue
		}
		raw.splitPeople().filterTo(result) { it.isUsefulPersonName() }
	}
	return result
}

private fun String.extractNamedPeople(label: String, extraStopWords: Set<String>): List<String>? {
	val labelMatch = Regex("""\b${Regex.escape(label)}\b""", RegexOption.IGNORE_CASE).find(this) ?: return null
	val start = labelMatch.range.last + 1
	var end = length
	for (stopWord in METADATA_LABELS + extraStopWords) {
		if (stopWord.equals(label, ignoreCase = true) || stopWord.isBlank()) {
			continue
		}
		val match = Regex("""\b${Regex.escape(stopWord)}\b""", RegexOption.IGNORE_CASE).find(this, start) ?: continue
		if (match.range.first < end) {
			end = match.range.first
		}
	}
	return substring(start, end)
		.trim(' ', ':', '-', '•', ',', '|')
		.splitPeople()
		.filter { it.isUsefulPersonName() }
		.takeIf { it.isNotEmpty() }
}

private fun String.splitPeople(): List<String> {
	return split(PERSON_SEPARATOR_REGEX)
		.asSequence()
		.map { it.replace(Regex("""\s+"""), " ").trim(' ', ':', '-', '•', ',', '|') }
		.filter { it.isNotBlank() }
		.distinctBy { it.lowercase(Locale.ENGLISH) }
		.toList()
}

private fun String.isUsefulPersonName(): Boolean {
	val value = trim()
	if (value.length !in 2..PERSON_NAME_MAX_LENGTH) {
		return false
	}
	val lower = value.lowercase(Locale.ENGLISH)
	if (lower in EMPTY_PERSON_VALUES) {
		return false
	}
	if (METADATA_LABELS.any { lower.contains(it.lowercase(Locale.ENGLISH)) }) {
		return false
	}
	if (CHAPTER_LINE_REGEX.containsMatchIn(value) || RATING_LINE_REGEX.containsMatchIn(value)) {
		return false
	}
	return true
}

private fun String.cleanDescription(): String {
	val normalized = replace("\r\n", "\n")
		.replace('\r', '\n')
		.replace(Regex("""(?i)<br\s*/?>"""), "\n")
	val lines = normalized
		.split('\n')
		.map { it.replace(Regex("""\s+"""), " ").trim() }
		.filter { it.isNotBlank() }
	val dirtyScore = lines.count { it.isDescriptionJunkLine() }
	if (dirtyScore == 0 && lines.size <= CLEAN_DESCRIPTION_LINE_LIMIT) {
		return trim()
	}
	val cleaned = ArrayList<String>(lines.size)
	for (line in lines) {
		if (line.isDescriptionJunkLine()) {
			continue
		}
		cleaned += line
	}
	return cleaned.joinToString("\n").trim()
}

private fun String.isDescriptionJunkLine(): Boolean {
	val lower = lowercase(Locale.ENGLISH)
	if (lower in DESCRIPTION_JUNK_EXACT) {
		return true
	}
	if (DESCRIPTION_JUNK_PREFIXES.any { lower.startsWith(it) }) {
		return true
	}
	if (CHAPTER_LINE_REGEX.matches(this) || RATING_LINE_REGEX.matches(this)) {
		return true
	}
	if (lower.contains("first chapter") || lower.contains("newest chapter")) {
		return true
	}
	return false
}

private fun Manga.inferState(): MangaState? {
	val labels = metadataLabels()
	return when {
		labels.any { it.contains("completed") || it.contains("finished") } -> MangaState.FINISHED
		labels.any { it.contains("ongoing") || it.contains("publishing") } -> MangaState.ONGOING
		labels.any { it.contains("hiatus") || it.contains("paused") } -> MangaState.PAUSED
		labels.any { it.contains("dropped") || it.contains("abandoned") } -> MangaState.ABANDONED
		labels.any { it.contains("upcoming") || it.contains("coming soon") } -> MangaState.UPCOMING
		else -> null
	}
}

private fun Manga.normalizeTags(description: String?): Set<MangaTag> {
	val comicType = inferComicType(description) ?: return tags
	if (tags.any { it.isTypeTag() }) {
		return tags
	}
	return LinkedHashSet<MangaTag>(tags.size + 1).apply {
		add(MangaTag(comicType.title, comicType.key, source))
		addAll(tags)
	}
}

private fun Manga.inferComicType(description: String?): ComicTypeHint? {
	val labels = metadataLabels(description)
	return when {
		labels.any { it.hasWholeWord("manhua") || it.contains("chinese webcomic") } -> ComicTypeHint.MANHUA
		labels.any { it.hasWholeWord("manhwa") || it.hasWholeWord("webtoon") || it.contains("korean webcomic") } -> ComicTypeHint.MANHWA
		labels.any { it.hasWholeWord("manga") || it.hasWholeWord("one-shot") || it.hasWholeWord("doujinshi") } -> ComicTypeHint.MANGA
		else -> inferComicTypeFromSource()
	}
}

private fun Manga.inferComicTypeFromSource(): ComicTypeHint? {
	val parserSource = source as? MangaParserSource ?: return null
	if (parserSource == MangaParserSource.DEMONICSCANS) {
		return ComicTypeHint.MANHWA
	}
	return when (parserSource.contentType) {
		ContentType.MANHUA -> ComicTypeHint.MANHUA
		ContentType.MANHWA -> ComicTypeHint.MANHWA
		ContentType.MANGA,
		ContentType.ONE_SHOT,
		ContentType.DOUJINSHI -> ComicTypeHint.MANGA
		else -> SOURCE_TYPE_DEFAULTS[parserSource.name.normalizedSourceKey()]
	}
}

private fun Manga.metadataLabels(description: String? = this.description): List<String> = buildList {
	add(title)
	add(source.name)
	val cleanDescription = if (source == MangaParserSource.DEMONICSCANS) {
		description?.removeDemonicGenericTypeText()
	} else {
		description
	}
	cleanDescription?.let(::add)
	for (tag in tags) {
		add(tag.title.removeDemonicGenericTypeText())
		add(tag.key.removeDemonicGenericTypeText())
	}
}.map { it.lowercase(Locale.ENGLISH) }

private fun String.removeDemonicGenericTypeText(): String {
	return replace(DEMONIC_GENERIC_TYPE_REGEX, " ")
}

private fun MangaTag.isTypeTag(): Boolean {
	val normalized = title.normalizedSourceKey()
	return normalized == "manga" || normalized == "manhwa" || normalized == "manhua"
}

private fun String.hasWholeWord(word: String): Boolean {
	return Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(this)
}

private fun String.normalizedSourceKey(): String = lowercase(Locale.ENGLISH).filter(Char::isLetterOrDigit)

private enum class ComicTypeHint(val title: String, val key: String) {
	MANGA("Manga", "manga"),
	MANHWA("Manhwa", "manhwa"),
	MANHUA("Manhua", "manhua"),
}

private const val PERSON_NAME_MAX_LENGTH = 72
private const val CLEAN_DESCRIPTION_LINE_LIMIT = 8

private val PERSON_SEPARATOR_REGEX = Regex("""\s*(?:,|/|\||&|\band\b)\s*""", RegexOption.IGNORE_CASE)
private val CHAPTER_LINE_REGEX = Regex("""(?i)^\s*(?:chapter|episode)\s+\d+(?:\.\d+)?\b.*""")
private val RATING_LINE_REGEX = Regex("""(?i)^\s*(?:rating|score)\s+[0-9]+(?:\.[0-9]+)?.*""")
private val DEMONIC_GENERIC_TYPE_REGEX = Regex("""(?i)\bManga\s*/\s*Manhwa\s*/\s*Manhua\b""")

private val EMPTY_PERSON_VALUES = setOf(
	"unknown",
	"updating",
	"updated",
	"n/a",
	"na",
	"none",
	"-",
)

private val METADATA_LABELS = setOf(
	"Rating",
	"Score",
	"Rank",
	"Chapters",
	"Bookmarks",
	"Bookmark",
	"Status",
	"Type",
	"Author",
	"Artist",
	"Genres",
	"Themes",
	"Demographic",
	"Format",
	"Read or Buy",
	"Show more",
	"First Chapter",
	"Premium",
	"Download",
	"Offline",
	"Newest Chapter",
	"Latest Chapter",
	"Comments",
)

private val DESCRIPTION_JUNK_EXACT = setOf(
	"bookmark",
	"download",
	"download offline",
	"premium",
	"first chapter",
	"newest chapter",
	"show more",
	"read first",
)

private val DESCRIPTION_JUNK_PREFIXES = setOf(
	"rating ",
	"score ",
	"bookmarks ",
	"status ",
	"type ",
	"author ",
	"artist ",
	"premium download",
)

private val SOURCE_TYPE_DEFAULTS = mapOf(
	"asurascans" to ComicTypeHint.MANHWA,
	"asurascansus" to ComicTypeHint.MANHWA,
	"asurascansgg" to ComicTypeHint.MANHWA,
	"aquamanga" to ComicTypeHint.MANHWA,
	"flamecomics" to ComicTypeHint.MANHWA,
	"mangaplusparseren" to ComicTypeHint.MANGA,
	"mangaplus" to ComicTypeHint.MANGA,
	"mangafireen" to ComicTypeHint.MANGA,
	"mangafire" to ComicTypeHint.MANGA,
	"manhuafast" to ComicTypeHint.MANHUA,
	"manhwaz" to ComicTypeHint.MANHWA,
	"demonicscans" to ComicTypeHint.MANHWA,
)
