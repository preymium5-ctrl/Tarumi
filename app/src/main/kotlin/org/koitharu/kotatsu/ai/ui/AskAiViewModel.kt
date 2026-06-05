package org.koitharu.kotatsu.ai.ui

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.distinctById
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.home.ui.ComicType
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class AskAiViewModel @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val cloudAiLibrarianEngine: CloudAiLibrarianEngine,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	private val _state = MutableStateFlow(
		AskAiState(),
	)
	val state: StateFlow<AskAiState> = _state

	private var searchJob: Job? = null

	fun setNsfw(enabled: Boolean) {
		_state.update { it.copy(includeNsfw = enabled) }
	}

	fun ask(query: String) {
		val normalized = query.trim()
		if (normalized.isEmpty()) {
			return
		}
		searchJob?.cancel()
		val includeNsfw = _state.value.includeNsfw
		_state.update {
			it.copy(
				isLoading = true,
				messages = it.messages + AskAiMessage(role = AskAiRole.USER, text = normalized),
			)
		}
		searchJob = launchJob(Dispatchers.Default) {
			val intent = parseRecommendationIntent(normalized)
			if (!intent.isRecommendationRequest) {
				val reply = cloudAiLibrarianEngine.generateConversationReply(normalized, includeNsfw)
					?: buildConversationFallback(normalized, includeNsfw)
				_state.update {
					it.copy(
						isLoading = false,
						messages = it.messages + AskAiMessage(
							role = AskAiRole.ASSISTANT,
							text = reply,
							query = normalized,
							includeNsfw = includeNsfw,
						),
					)
				}
				return@launchJob
			}
			val sources = if (includeNsfw) {
				NSFW_SOURCE_NAMES.resolveSources()
			} else {
				SAFE_SOURCE_NAMES.resolveSources()
			}
			val results = findRecommendations(sources, normalized, intent)
			val reply = cloudAiLibrarianEngine.generateReply(normalized, includeNsfw, results)
				?: buildFallbackReply(normalized, includeNsfw, results)
			_state.update {
				it.copy(
					isLoading = false,
					messages = it.messages + AskAiMessage(
						role = AskAiRole.ASSISTANT,
						text = reply,
						query = normalized,
						includeNsfw = includeNsfw,
						results = results,
					),
				)
			}
		}
	}

	private suspend fun findRecommendations(
		sources: List<MangaParserSource>,
		query: String,
		intent: RecommendationIntent,
	): List<Manga> {
		val reference = intent.referenceTitle
			?.let { title -> resolveReference(sources, title) }

		val seeds = if (reference != null) {
			loadSimilarityCandidates(sources, reference)
		} else {
			loadTraitCandidates(sources, intent)
		}
		val detailed = loadDetails(
			(seeds + listOfNotNull(reference))
				.distinctById()
				.take(MAX_DETAIL_CANDIDATES),
		)
		return detailed
			.asSequence()
			.filterNot { manga -> reference != null && manga.sameTitleAs(reference) }
			.filter { manga -> manga.matchesRequestedType(intent.requestedType) }
			.map { manga ->
				manga to manga.recommendationScore(
					query = query,
					reference = reference,
					requestedType = intent.requestedType,
					traits = intent.traits,
				)
			}
			.sortedWith(
				compareByDescending<Pair<Manga, Int>> { it.second }
					.thenByDescending { it.first.rating },
			)
			.map { it.first }
			.toList()
			.distinctById()
			.take(RESULT_LIMIT)
	}

	private suspend fun resolveReference(
		sources: List<MangaParserSource>,
		title: String,
	): Manga? {
		val matches = searchSources(sources, title)
		val bestMatch = matches.maxByOrNull { it.titleMatchScore(title) } ?: return null
		return loadDetails(listOf(bestMatch)).firstOrNull()
	}

	private suspend fun loadSimilarityCandidates(
		sources: List<MangaParserSource>,
		reference: Manga,
	): List<Manga> = supervisorScope {
		val related = async(Dispatchers.IO) {
			withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
				runCatchingCancellable {
					mangaRepositoryFactory.create(reference.source).getRelated(reference)
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrDefault(emptyList())
			}.orEmpty()
		}
		val catalogPages = sources.map { source ->
			async(Dispatchers.IO) { browseSource(source) }
		}
		(related.await() + catalogPages.awaitAll().flatten()).distinctById()
	}

	private suspend fun loadTraitCandidates(
		sources: List<MangaParserSource>,
		intent: RecommendationIntent,
	): List<Manga> = supervisorScope {
		val queries = intent.searchQueries()
		val searched = queries.map { searchQuery ->
			async(Dispatchers.IO) { searchSources(sources, searchQuery) }
		}
		val catalogPages = sources.map { source ->
			async(Dispatchers.IO) { browseSource(source) }
		}
		(searched.awaitAll().flatten() + catalogPages.awaitAll().flatten()).distinctById()
	}

	private suspend fun searchSources(
		sources: List<MangaParserSource>,
		query: String,
	): List<Manga> = supervisorScope {
		sources.map { source ->
			async(Dispatchers.IO) { searchSource(source, query) }
		}.awaitAll().flatten().distinctById()
	}

	private suspend fun searchSource(source: MangaParserSource, query: String): List<Manga> {
		val repository = mangaRepositoryFactory.create(source)
		val order = when {
			SortOrder.RELEVANCE in repository.sortOrders -> SortOrder.RELEVANCE
			SortOrder.POPULARITY in repository.sortOrders -> SortOrder.POPULARITY
			SortOrder.UPDATED in repository.sortOrders -> SortOrder.UPDATED
			else -> repository.defaultSortOrder
		}
		val filter = if (repository.filterCapabilities.isSearchSupported) {
			MangaListFilter(query = query)
		} else {
			MangaListFilter.EMPTY
		}
		return withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
			runCatchingCancellable {
				repository.getList(0, order, filter)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(emptyList())
		}.orEmpty()
	}

	private suspend fun browseSource(source: MangaParserSource): List<Manga> {
		val repository = mangaRepositoryFactory.create(source)
		val orders = buildList {
			if (SortOrder.POPULARITY in repository.sortOrders) {
				add(SortOrder.POPULARITY)
			}
			if (SortOrder.UPDATED in repository.sortOrders) {
				add(SortOrder.UPDATED)
			}
			if (isEmpty()) {
				add(repository.defaultSortOrder)
			}
		}.distinct()
		return orders.flatMap { order ->
			withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
				runCatchingCancellable {
					repository.getList(0, order, MangaListFilter.EMPTY)
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrDefault(emptyList())
			}.orEmpty()
		}.distinctById()
	}

	private suspend fun loadDetails(items: List<Manga>): List<Manga> {
		return items.chunked(DETAIL_BATCH_SIZE).flatMap { batch ->
			supervisorScope {
				batch.map { manga ->
					async(Dispatchers.IO) {
						withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
							runCatchingCancellable {
								mangaRepositoryFactory.create(manga.source).getDetails(manga)
							}.onFailure {
								it.printStackTraceDebug()
							}.getOrDefault(manga)
						} ?: manga
					}
				}.awaitAll()
			}
		}
	}

	private fun buildFallbackReply(query: String, includeNsfw: Boolean, results: List<Manga>): String {
		if (results.isEmpty()) {
			return if (includeNsfw) {
				"Spicy librarian mode is on, but I could not find solid adult matches for \"$query\" yet. Try a broader tag, author, or title."
			} else {
				"I could not find strong matches for \"$query\" yet. Try a broader mood, genre, trope, or title."
			}
		}
		return if (includeNsfw) {
			"Spicy librarian mode is on. I found adult picks that match \"$query\", and the cards below are the strongest matches from HentaiRead and 18PornComic."
		} else {
			"I found some good matches for \"$query\". Start with these picks because their titles, tags, or source results line up best with what you asked."
		}
	}

	private fun buildConversationFallback(query: String, includeNsfw: Boolean): String {
		val words = searchableWords(query)
		return when {
			words.isEmpty() || words.any { it in GREETING_WORDS } -> {
				if (includeNsfw) {
					"Hi, I’m Tarumi’s 18+ librarian. Tell me a spicy trope, mood, tag, author, or title, and I’ll look for matching adult comics from your sources."
				} else {
					"Hi, I’m Tarumi’s smart librarian. Tell me a mood, trope, genre, or a title you like, and I’ll recommend manga, manhwa, or manhua that fit."
				}
			}
			else -> {
				"I can chat, but I’m best when you ask for recommendations. Try something like: “recommend me a manhwa where the weak MC becomes powerful with romance.”"
			}
		}
	}

	private fun List<String>.resolveSources(): List<MangaParserSource> {
		val requested = map { it.normalizedSourceName() }.toSet()
		return MangaParserSource.entries.filter { source ->
			source.name.normalizedSourceName() in requested ||
				source.getTitle(context).normalizedSourceName() in requested
		}.distinct()
	}

	private fun String.normalizedSourceName(): String = lowercase().filter { it.isLetterOrDigit() }

	private fun Manga.recommendationScore(
		query: String,
		reference: Manga?,
		requestedType: ComicType?,
		traits: Set<String>,
	): Int {
		var score = 0
		if (requestedType != null && inferredComicType() == requestedType) {
			score += TYPE_MATCH_SCORE
		}
		if (reference != null) {
			val sharedTags = normalizedTags().intersect(reference.normalizedTags())
			score += sharedTags.size * TAG_MATCH_SCORE
			val sharedSynopsisWords = searchableWords(description.orEmpty())
				.intersect(searchableWords(reference.description.orEmpty()))
			score += sharedSynopsisWords.size.coerceAtMost(MAX_SYNOPSIS_MATCHES) * SYNOPSIS_MATCH_SCORE
			if (inferredComicType() == reference.inferredComicType()) {
				score += REFERENCE_TYPE_SCORE
			}
		}
		val haystack = searchableText()
		for (trait in traits) {
			if (haystack.contains(trait)) {
				score += TRAIT_MATCH_SCORE
			}
			for (alias in TRAIT_ALIASES[trait].orEmpty()) {
				if (haystack.contains(alias)) {
					score += TRAIT_ALIAS_MATCH_SCORE
				}
			}
		}
		for (word in searchableWords(query)) {
			if (title.contains(word, ignoreCase = true)) {
				score += TITLE_WORD_SCORE
			}
			if (haystack.contains(word)) {
				score += QUERY_WORD_SCORE
			}
		}
		score += (rating * RATING_MULTIPLIER).toInt().coerceAtLeast(0)
		return score
	}

	private fun Manga.matchesRequestedType(requestedType: ComicType?): Boolean {
		return requestedType == null || inferredComicType() == requestedType
	}

	private fun Manga.inferredComicType(): ComicType {
		val detected = detectComicType()
		if (detected != ComicType.COMIC) {
			return detected
		}
		return when (source as? MangaParserSource) {
			MangaParserSource.FLAMECOMICS -> ComicType.MANHWA
			MangaParserSource.MANHUAFAST -> ComicType.MANHUA
			MangaParserSource.MANGAPLUSPARSER_EN -> ComicType.MANGA
			else -> ComicType.COMIC
		}
	}

	private fun Manga.normalizedTags(): Set<String> = tags.flatMapTo(linkedSetOf()) { tag ->
		listOf(tag.title.normalizedMetadata(), tag.key.normalizedMetadata())
	}.filterTo(linkedSetOf()) { it.isNotBlank() }

	private fun Manga.searchableText(): String = buildString {
		append(title.lowercase())
		append(' ')
		append(altTitles.joinToString(" ").lowercase())
		append(' ')
		append(description?.lowercase().orEmpty())
		append(' ')
		append(authors.joinToString(" ").lowercase())
		append(' ')
		tags.joinTo(this, separator = " ") { "${it.title.lowercase()} ${it.key.lowercase()}" }
	}

	private fun Manga.sameTitleAs(other: Manga): Boolean {
		val ownTitles = (listOf(title) + altTitles).map { it.normalizedTitle() }.filter { it.isNotBlank() }
		val otherTitles = (listOf(other.title) + other.altTitles).map { it.normalizedTitle() }.filter { it.isNotBlank() }
		return ownTitles.any(otherTitles::contains)
	}

	private fun Manga.titleMatchScore(referenceTitle: String): Int {
		val target = referenceTitle.normalizedTitle()
		val candidates = (listOf(title) + altTitles).map { it.normalizedTitle() }
		return candidates.maxOfOrNull { candidate ->
			when {
				candidate == target -> 100
				candidate.startsWith(target) || target.startsWith(candidate) -> 80
				candidate.contains(target) || target.contains(candidate) -> 60
				else -> searchableWords(candidate).intersect(searchableWords(target)).size * 10
			}
		} ?: 0
	}

	private fun String.normalizedTitle(): String = lowercase()
		.replace(NON_ALPHANUMERIC_REGEX, " ")
		.replace(MULTIPLE_SPACES_REGEX, " ")
		.trim()
		.removePrefix("the ")

	private fun String.normalizedMetadata(): String = lowercase()
		.replace(NON_ALPHANUMERIC_REGEX, " ")
		.replace(MULTIPLE_SPACES_REGEX, " ")
		.trim()

	private fun searchableWords(value: String): Set<String> = value.lowercase()
		.split(WORD_SPLIT_REGEX)
		.asSequence()
		.map { it.filter(Char::isLetterOrDigit) }
		.filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
		.toSet()

	private fun parseRecommendationIntent(query: String): RecommendationIntent {
		val words = searchableWords(query)
		val requestedType = when {
			Regex("""(?i)\bmanhwa(?:s)?\b""").containsMatchIn(query) -> ComicType.MANHWA
			Regex("""(?i)\bmanhua(?:s)?\b""").containsMatchIn(query) -> ComicType.MANHUA
			Regex("""(?i)\bmanga\b""").containsMatchIn(query) -> ComicType.MANGA
			else -> null
		}
		val referenceTitle = REFERENCE_REGEX.find(query)
			?.groupValues
			?.getOrNull(1)
			?.trim()
			?.removePrefix("the ")
			?.removePrefix("The ")
			?.takeIf { it.isNotBlank() }
		val searchQuery = referenceTitle ?: query
			.replace(Regex("""(?i)\b(?:recommend|suggest|show|find|give|me|please|a|an|some)\b"""), " ")
			.replace(Regex("""(?i)\b(?:manga|manhwa|manhua|comic|comics)\b"""), " ")
			.replace(Regex("\\s+"), " ")
			.trim()
			.ifBlank { query }
		val traits = detectTraits(query)
		val isRecommendationRequest = referenceTitle != null ||
			traits.isNotEmpty() ||
			words.any { it in RECOMMENDATION_WORDS }
		return RecommendationIntent(
			requestedType = requestedType,
			referenceTitle = referenceTitle,
			searchQuery = searchQuery,
			traits = traits,
			isRecommendationRequest = isRecommendationRequest,
		)
	}

	private fun detectTraits(query: String): Set<String> {
		val normalized = query.lowercase()
		return buildSet {
			for ((trait, aliases) in TRAIT_ALIASES) {
				if (aliases.any { alias -> normalized.contains(alias) }) {
					add(trait)
				}
			}
			if (normalized.contains("hero to zero") || normalized.contains("zero to hero") ||
				normalized.contains("trash to hero") || normalized.contains("from trash")
			) {
				add("underdog")
				add("weak to strong")
			}
			if (normalized.contains("multiple wives")) {
				add("harem")
			}
		}
	}

	private fun RecommendationIntent.searchQueries(): List<String> {
		val traitQueries = traits.flatMap { trait -> listOf(trait) + TRAIT_ALIASES[trait].orEmpty() }
		return (listOf(searchQuery) + traitQueries)
			.map { it.trim() }
			.filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
			.distinct()
			.take(MAX_SEARCH_QUERIES)
	}

	companion object {

		private const val RESULT_LIMIT = 10
		private const val MAX_DETAIL_CANDIDATES = 72
		private const val MAX_SEARCH_QUERIES = 12
		private const val DETAIL_BATCH_SIZE = 8
		private const val SOURCE_TIMEOUT_MS = 8_000L
		private const val DETAIL_TIMEOUT_MS = 6_000L
		private const val MIN_WORD_LENGTH = 3
		private const val TYPE_MATCH_SCORE = 100
		private const val REFERENCE_TYPE_SCORE = 30
		private const val TAG_MATCH_SCORE = 24
		private const val SYNOPSIS_MATCH_SCORE = 3
		private const val MAX_SYNOPSIS_MATCHES = 12
		private const val TITLE_WORD_SCORE = 8
		private const val QUERY_WORD_SCORE = 2
		private const val TRAIT_MATCH_SCORE = 18
		private const val TRAIT_ALIAS_MATCH_SCORE = 12
		private const val RATING_MULTIPLIER = 2f
		private val WORD_SPLIT_REGEX = Regex("[\\s,./:;!?()\\[\\]{}'\"_-]+")
		private val REFERENCE_REGEX = Regex(
			"""(?i)\b(?:same\s+as|similar\s+to|like)\s+(.+?)(?:[?.!,;]|$)""",
		)
		private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
		private val MULTIPLE_SPACES_REGEX = Regex("\\s+")
		private val STOP_WORDS = setOf(
			"about", "after", "also", "and", "are", "because", "been", "before", "but", "can",
			"comic", "comics", "find", "for", "from", "give", "has", "have", "like", "manga",
			"manhua", "manhwa", "recommend", "recommendation", "same", "show", "similar", "that",
			"the", "their", "them", "this", "with", "you",
		)
		private val GREETING_WORDS = setOf("hello", "hi", "hey", "yo", "sup", "morning", "evening")
		private val RECOMMENDATION_WORDS = setOf(
			"recommend", "suggest", "similar", "same", "like", "find", "show", "manga", "manhwa",
			"manhua", "comic", "comics", "read", "looking", "story", "trope", "genre",
		)
		private val TRAIT_ALIASES = mapOf(
			"underdog" to setOf("weak", "trash", "loser", "bullied", "low rank", "zero", "underdog"),
			"weak to strong" to setOf("weak to strong", "becomes strong", "growth", "leveling", "level up", "overpowered"),
			"harem" to setOf("harem", "wives", "wife", "multiple wives", "concubine", "romance"),
			"revenge" to setOf("revenge", "betrayal", "payback"),
			"fantasy" to setOf("fantasy", "magic", "tower", "dungeon", "martial", "murim"),
			"action" to setOf("action", "fight", "battle", "combat"),
			"zombie" to setOf("zombie", "zombies", "undead", "infection", "infected", "apocalypse"),
			"female lead" to setOf(
				"female lead",
				"female protagonist",
				"girl protagonist",
				"girl main character",
				"female main character",
				"heroine",
			),
			"regression" to setOf("regression", "regressor", "returner", "second chance"),
			"reincarnation" to setOf("reincarnation", "reincarnated", "isekai", "transmigration"),
		)

		private val SAFE_SOURCE_NAMES = listOf(
			"WEEBCENTRAL",
			"FLAMECOMICS",
			"MANGAFIRE_EN",
			"AQUAMANGA",
			"MANHUAFAST",
			"MANGAPLUSPARSER_EN",
		)

		private val NSFW_SOURCE_NAMES = listOf(
			"HENTAIREAD",
			"18PORNCOMIC",
			"PORNCOMIC18",
		)
	}
}

private data class RecommendationIntent(
	val requestedType: ComicType?,
	val referenceTitle: String?,
	val searchQuery: String,
	val traits: Set<String>,
	val isRecommendationRequest: Boolean,
)

data class AskAiState(
	val includeNsfw: Boolean = false,
	val isLoading: Boolean = false,
	val messages: List<AskAiMessage> = emptyList(),
)

data class AskAiMessage(
	val role: AskAiRole,
	val text: String,
	val query: String = "",
	val includeNsfw: Boolean = false,
	val results: List<Manga> = emptyList(),
)

enum class AskAiRole {
	USER,
	ASSISTANT,
}
