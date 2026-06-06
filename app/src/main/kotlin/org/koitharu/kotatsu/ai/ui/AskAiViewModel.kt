package org.koitharu.kotatsu.ai.ui

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.distinctById
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.home.ui.ComicType
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class AskAiViewModel @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val historyRepository: HistoryRepository,
	private val localAiLibrarianEngine: LocalAiLibrarianEngine,
	private val cloudAiLibrarianEngine: CloudAiLibrarianEngine,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	private val _state = MutableStateFlow(
		AskAiState(),
	)
	val state: StateFlow<AskAiState> = _state

	private var searchJob: Job? = null
	private val historyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	init {
		refreshTokenState()
		restoreConversation()
		launchJob {
			localAiLibrarianEngine.status.collect { status ->
				_state.update { it.copy(localModelStatus = status) }
			}
		}
	}

	fun setNsfw(enabled: Boolean) {
		_state.update { it.copy(includeNsfw = enabled) }
	}

	fun downloadLocalModel() {
		launchJob(Dispatchers.IO) {
			localAiLibrarianEngine.downloadModel()
		}
	}

	fun localModelSizeBytes(): Long = localAiLibrarianEngine.expectedModelSizeBytes

	fun ask(query: String) {
		val normalized = query.trim()
		if (normalized.isEmpty()) {
			return
		}
		searchJob?.cancel()
		val includeNsfw = _state.value.includeNsfw
		val hasCloudToken = consumeDailyToken()
		val localOnly = !hasCloudToken && _state.value.localModelStatus == LocalAiModelStatus.Ready
		if (!hasCloudToken && !localOnly) {
			appendMessages(AskAiMessage(role = AskAiRole.USER, text = normalized, includeNsfw = includeNsfw))
			val resetText = formatDurationUntil(_state.value.tokenResetAtMillis)
			_state.update { it.copy(isLoading = true) }
			searchJob = launchJob {
				streamAssistantReply(
					message = AskAiMessage(
						role = AskAiRole.ASSISTANT,
						query = normalized,
						includeNsfw = includeNsfw,
					),
					finalText = context.getString(R.string.ask_ai_tokens_empty, resetText),
				)
			}
			return
		}
		appendMessages(AskAiMessage(role = AskAiRole.USER, text = normalized, includeNsfw = includeNsfw))
		_state.update { it.copy(isLoading = true) }
		searchJob = launchJob(Dispatchers.Default) {
			val messagesBeforeCurrent = _state.value.messages.dropLast(1)
			val useReadingHistory = shouldUseReadingHistory(normalized)
			val historyContext = if (useReadingHistory) buildReadingContext(includeNsfw) else ""
			val conversationContext = buildConversationContext(messagesBeforeCurrent, includeNsfw)
			val requestedIntent = parseRecommendationIntent(normalized)
			val previousRecommendation = if (requestedIntent.isMoreRequest) {
				messagesBeforeCurrent.lastOrNull { message ->
					message.role == AskAiRole.ASSISTANT &&
						message.includeNsfw == includeNsfw &&
						(message.results.isNotEmpty() || message.resultCards.isNotEmpty())
				}
			} else {
				null
			}
			val effectiveQuery = if (requestedIntent.isMoreRequest && previousRecommendation?.query?.isNotBlank() == true) {
				previousRecommendation.query
			} else {
				normalized
			}
			val intent = if (effectiveQuery == normalized) {
				requestedIntent
			} else {
				parseRecommendationIntent(effectiveQuery).copy(
					requestedLimit = requestedIntent.requestedLimit,
					isMoreRequest = true,
					isRecommendationRequest = true,
				)
			}
			if (!intent.isRecommendationRequest) {
				val reply = localAiLibrarianEngine.generateConversationReply(
					query = normalized,
					includeNsfw = includeNsfw,
					libraryContext = historyContext,
					conversationContext = conversationContext,
				) ?: if (localOnly) {
					null
				} else {
					cloudAiLibrarianEngine.generateConversationReply(
						query = normalized,
						includeNsfw = includeNsfw,
						libraryContext = historyContext,
						conversationContext = conversationContext,
					)
				}
					?: buildConversationFallback(normalized, includeNsfw)
				streamAssistantReply(
					message = AskAiMessage(
						role = AskAiRole.ASSISTANT,
						query = normalized,
						includeNsfw = includeNsfw,
					),
					finalText = reply,
				)
				return@launchJob
			}
			val sources = if (includeNsfw) {
				NSFW_SOURCE_NAMES.resolveSources()
			} else {
				SAFE_SOURCE_NAMES.resolveSources()
			}
			val history = if (useReadingHistory) loadReadingHistory(includeNsfw) else emptyList()
			val excludedIds = previousRecommendation
				?.let { message -> message.results.map { it.id } + message.resultCards.map { it.id } }
				?.toSet()
				.orEmpty()
			val results = findRecommendations(sources, effectiveQuery, intent, history, includeNsfw, excludedIds)
			val reply = buildRecommendationReply(
				query = normalized,
				effectiveQuery = effectiveQuery,
				includeNsfw = includeNsfw,
				results = results,
				intent = intent,
				hasPreviousRecommendations = previousRecommendation != null,
			)
			appendMessages(
				AskAiMessage(
					role = AskAiRole.ASSISTANT,
					query = effectiveQuery,
					includeNsfw = includeNsfw,
					results = results,
					resultCards = results.map { it.toResultCard() },
				),
				persist = false,
			)
			streamLastAssistantReply(
				finalText = reply,
				results = results,
				resultCards = results.map { it.toResultCard() },
			)
		}
	}

	fun clearConversation() {
		searchJob?.cancel()
		historyPrefs.edit { remove(KEY_MESSAGES) }
		_state.update { it.copy(isLoading = false, messages = emptyList()) }
	}

	fun setComposerExpanded(expanded: Boolean) {
		_state.update { it.copy(isComposerExpanded = expanded) }
	}

	private suspend fun findRecommendations(
		sources: List<MangaParserSource>,
		query: String,
		intent: RecommendationIntent,
		history: List<Manga>,
		includeNsfw: Boolean,
		excludedIds: Set<Long> = emptySet(),
	): List<Manga> {
		val resultLimit = intent.requestedLimit.coerceIn(1, MAX_REQUESTED_RESULTS)
		val candidateLimit = (resultLimit + excludedIds.size + EXTRA_CANDIDATE_BUFFER)
			.coerceIn(resultLimit, MAX_CANDIDATE_POOL)
		val reference = intent.referenceTitle
			?.let { title -> resolveReference(sources, title) }

		val seeds = if (reference != null) {
			loadSimilarityCandidates(sources, reference, candidateLimit)
		} else {
			loadTraitCandidates(sources, intent, candidateLimit)
		}
		val detailed = loadDetails(
			(seeds + listOfNotNull(reference))
				.distinctById()
				.take(candidateLimit),
		)
		return detailed
			.asSequence()
			.filterNot { manga -> manga.id in excludedIds }
			.filterNot { manga -> reference != null && manga.sameTitleAs(reference) }
			.filter { manga -> manga.matchesRequestedType(intent.requestedType) }
			.filter { manga -> manga.matchesSafetyMode(includeNsfw) }
			.filter { manga -> manga.matchesRequiredTraits(intent.traits) }
			.map { manga ->
				manga to manga.recommendationScore(
					query = query,
					reference = reference,
					requestedType = intent.requestedType,
					traits = intent.traits,
					history = history,
				)
			}
			.sortedWith(
				compareByDescending<Pair<Manga, Int>> { it.second }
					.thenByDescending { it.first.rating },
			)
			.map { it.first }
			.toList()
			.distinctById()
			.take(resultLimit)
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
		limit: Int,
	): List<Manga> {
		val candidates = ArrayList<Manga>(limit)
		withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
			runCatchingCancellable {
				mangaRepositoryFactory.create(reference.source).getRelated(reference)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(emptyList())
		}.orEmpty().appendUniqueTo(candidates, limit)
		for (source in sources) {
			if (candidates.size >= limit) {
				break
			}
			browseSource(source, limit - candidates.size).appendUniqueTo(candidates, limit)
		}
		return candidates
	}

	private suspend fun loadTraitCandidates(
		sources: List<MangaParserSource>,
		intent: RecommendationIntent,
		limit: Int,
	): List<Manga> {
		val candidates = ArrayList<Manga>(limit)
		val queries = intent.searchQueries()
		for (searchQuery in queries) {
			if (candidates.size >= limit) {
				break
			}
			searchSources(sources, searchQuery, limit - candidates.size)
				.appendUniqueTo(candidates, limit)
		}
		for (source in sources) {
			if (candidates.size >= limit) {
				break
			}
			browseSource(source, limit - candidates.size).appendUniqueTo(candidates, limit)
		}
		return candidates
	}

	private suspend fun searchSources(
		sources: List<MangaParserSource>,
		query: String,
		limit: Int = RESULT_LIMIT,
	): List<Manga> = supervisorScope {
		sources.map { source ->
			async(Dispatchers.IO) { searchSource(source, query, limit) }
		}.awaitAll().flatten().distinctById().take(limit)
	}

	private suspend fun searchSource(source: MangaParserSource, query: String, limit: Int): List<Manga> {
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
		}.orEmpty().take(limit)
	}

	private suspend fun browseSource(source: MangaParserSource, limit: Int = RESULT_LIMIT): List<Manga> {
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
		val candidates = ArrayList<Manga>(limit)
		for (order in orders) {
			if (candidates.size >= limit) {
				break
			}
			withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
				runCatchingCancellable {
					repository.getList(0, order, MangaListFilter.EMPTY)
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrDefault(emptyList())
			}.orEmpty().appendUniqueTo(candidates, limit)
		}
		return candidates
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

	private fun buildRecommendationReply(
		query: String,
		effectiveQuery: String,
		includeNsfw: Boolean,
		results: List<Manga>,
		intent: RecommendationIntent,
		hasPreviousRecommendations: Boolean,
	): String {
		if (results.isEmpty()) {
			if (intent.isMoreRequest && !hasPreviousRecommendations) {
				return "I can show more comics after I have a previous recommendation list to continue from. Ask me for a genre, tag, or title first."
			}
			if (intent.isMoreRequest) {
				return "I could not find more source-backed comics for \"$effectiveQuery\" yet. Try a broader genre, tag, or title."
			}
			return if (includeNsfw) {
				"Spicy librarian mode is on, but I could not find solid adult matches for \"$query\" yet. Try a broader tag, author, or title."
			} else {
				"I could not find strong matches for \"$query\" yet. Try a broader mood, genre, trope, or title."
			}
		}
		val countText = if (results.size == 1) {
			"I only found 1 comic"
		} else if (results.size < intent.requestedLimit) {
			"I only found ${results.size} comics"
		} else {
			"I found ${results.size} comics"
		}
		val modeText = if (includeNsfw) {
			"from HentaiRead and 18PornComic"
		} else {
			"from Manga Plus English and ManhwaZ"
		}
		val requestText = if (effectiveQuery == query) {
			"for \"$query\""
		} else {
			"as more picks for \"$effectiveQuery\""
		}
		return if (includeNsfw) {
			"Spicy librarian mode is on. $countText $requestText $modeText. The cards below are the source-backed matches."
		} else {
			"$countText $requestText $modeText. The cards below are the source-backed matches."
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
				"I’m here with you. I can talk normally, remember this chat for 2 days, and when you want comics I can crawl the allowed libraries for source-backed picks."
			}
		}
	}

	private suspend fun loadReadingHistory(includeNsfw: Boolean): List<Manga> {
		return runCatchingCancellable {
			historyRepository.getList(0, HISTORY_CONTEXT_LIMIT)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(emptyList())
			.filter { it.isNsfw() == includeNsfw }
	}

	private suspend fun buildReadingContext(includeNsfw: Boolean): String {
		val history = loadReadingHistory(includeNsfw)
		val popularTags = history
			.flatMap { it.tags }
			.distinctBy { it.key.ifBlank { it.title } }
			.take(HISTORY_TAG_LIMIT)
		val popularSources = history
			.map { it.source }
			.distinctBy { it.name }
			.take(HISTORY_SOURCE_LIMIT)
		return buildString {
			if (history.isNotEmpty()) {
				append("Recent reading history: ")
				append(history.take(8).joinToString("; ") { manga ->
					val tags = manga.tags.take(4).joinToString { it.title }
					"${manga.title} (${manga.detectComicType().label}${if (tags.isBlank()) "" else ", $tags"})"
				})
				appendLine()
			}
			if (popularTags.isNotEmpty()) {
				append("Frequent tags: ")
				append(popularTags.joinToString { it.title })
				appendLine()
			}
			if (popularSources.isNotEmpty()) {
				append("Frequent sources: ")
				append(popularSources.joinToString { it.getTitle(context) })
			}
		}.trim()
	}

	private fun restoreConversation() {
		val messages = readStoredMessages()
		_state.update { it.copy(messages = messages) }
		persistMessages(messages)
	}

	private fun appendMessages(vararg messages: AskAiMessage, persist: Boolean = true) {
		_state.update { state ->
			val updated = (state.messages + messages).takeLast(MAX_STORED_MESSAGES)
			if (persist) {
				persistMessages(updated)
			}
			state.copy(messages = updated)
		}
	}

	private suspend fun streamAssistantReply(message: AskAiMessage, finalText: String) {
		appendMessages(message.copy(isStreaming = true), persist = false)
		streamLastAssistantReply(
			finalText = finalText,
			results = message.results,
			resultCards = message.resultCards,
		)
	}

	private suspend fun streamLastAssistantReply(
		finalText: String,
		results: List<Manga>,
		resultCards: List<AskAiResultCard>,
	) {
		val words = finalText.split(Regex("\\s+")).filter { it.isNotBlank() }
		if (words.isEmpty()) {
			finishLastAssistantMessage(finalText, results, resultCards)
			return
		}
		val builder = StringBuilder()
		for ((index, word) in words.withIndex()) {
			if (index > 0) {
				builder.append(' ')
			}
			builder.append(word)
			updateLastAssistantMessage(
				text = builder.toString(),
				results = results,
				resultCards = resultCards,
				isStreaming = true,
				persist = false,
			)
			delay(STREAM_WORD_DELAY_MS)
		}
		finishLastAssistantMessage(finalText, results, resultCards)
	}

	private fun finishLastAssistantMessage(
		finalText: String,
		results: List<Manga>,
		resultCards: List<AskAiResultCard>,
	) {
		updateLastAssistantMessage(
			text = finalText,
			results = results,
			resultCards = resultCards,
			isStreaming = false,
			persist = true,
		)
		_state.update { it.copy(isLoading = false) }
	}

	private fun updateLastAssistantMessage(
		text: String,
		results: List<Manga>,
		resultCards: List<AskAiResultCard>,
		isStreaming: Boolean,
		persist: Boolean,
	) {
		_state.update { state ->
			val index = state.messages.indexOfLast { it.role == AskAiRole.ASSISTANT }
			if (index < 0) {
				return@update state
			}
			val updated = state.messages.toMutableList()
			updated[index] = updated[index].copy(
				text = text,
				results = results,
				resultCards = resultCards,
				isStreaming = isStreaming,
			)
			if (persist) {
				persistMessages(updated)
			}
			state.copy(messages = updated)
		}
	}

	private fun readStoredMessages(): List<AskAiMessage> {
		val raw = historyPrefs.getString(KEY_MESSAGES, null) ?: return emptyList()
		val cutoff = System.currentTimeMillis() - CHAT_RETENTION_MS
		return try {
			json.decodeFromString<List<StoredAskAiMessage>>(raw)
				.asSequence()
				.filter { it.createdAt >= cutoff }
				.map {
					AskAiMessage(
						role = it.role,
						text = it.text,
						query = it.query,
						includeNsfw = it.includeNsfw,
						createdAt = it.createdAt,
						resultCards = it.resultCards,
						results = it.resultCards.mapNotNull { card -> card.toManga() },
					)
				}
				.toList()
		} catch (_: SerializationException) {
			emptyList()
		} catch (_: IllegalArgumentException) {
			emptyList()
		}
	}

	private fun persistMessages(messages: List<AskAiMessage>) {
		val now = System.currentTimeMillis()
		val cutoff = now - CHAT_RETENTION_MS
		val stored = messages
			.takeLast(MAX_STORED_MESSAGES)
			.map {
				StoredAskAiMessage(
					role = it.role,
					text = it.text,
					query = it.query,
					includeNsfw = it.includeNsfw,
					resultCards = it.resultCards.ifEmpty { it.results.map { manga -> manga.toResultCard() } },
					createdAt = it.createdAt,
				)
			}
			.filter { it.createdAt >= cutoff }
		historyPrefs.edit {
			if (stored.isEmpty()) {
				remove(KEY_MESSAGES)
			} else {
				putString(KEY_MESSAGES, json.encodeToString(stored))
			}
		}
	}

	private fun refreshTokenState() {
		val window = readTokenWindow()
		_state.update {
			it.copy(
				remainingTokens = DAILY_TOKEN_LIMIT - window.used,
				maxTokens = DAILY_TOKEN_LIMIT,
				tokenResetAtMillis = window.resetAtMillis,
			)
		}
	}

	private fun consumeDailyToken(): Boolean {
		val window = readTokenWindow()
		if (window.used >= DAILY_TOKEN_LIMIT) {
			refreshTokenState()
			return false
		}
		val used = window.used + 1
		historyPrefs.edit {
			putInt(KEY_TOKEN_USED, used)
			putLong(KEY_TOKEN_RESET_AT, window.resetAtMillis)
		}
		_state.update {
			it.copy(
				remainingTokens = DAILY_TOKEN_LIMIT - used,
				maxTokens = DAILY_TOKEN_LIMIT,
				tokenResetAtMillis = window.resetAtMillis,
			)
		}
		return true
	}

	private fun readTokenWindow(): TokenWindow {
		val now = System.currentTimeMillis()
		val storedResetAt = historyPrefs.getLong(KEY_TOKEN_RESET_AT, 0L)
		val resetAt = storedResetAt.takeIf { it > now } ?: (now + DAILY_TOKEN_RESET_MS)
		val used = if (storedResetAt > now) {
			historyPrefs.getInt(KEY_TOKEN_USED, 0).coerceIn(0, DAILY_TOKEN_LIMIT)
		} else {
			0
		}
		if (storedResetAt <= now) {
			historyPrefs.edit {
				putInt(KEY_TOKEN_USED, 0)
				putLong(KEY_TOKEN_RESET_AT, resetAt)
			}
		}
		return TokenWindow(used = used, resetAtMillis = resetAt)
	}

	private fun formatDurationUntil(targetMillis: Long): String {
		val remaining = (targetMillis - System.currentTimeMillis()).coerceAtLeast(0L)
		val totalMinutes = (remaining + 59_999L) / 60_000L
		val hours = totalMinutes / 60L
		val minutes = totalMinutes % 60L
		return when {
			hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
			hours > 0L -> "${hours}h"
			minutes > 0L -> "${minutes}m"
			else -> context.getString(R.string.less_than_minute)
		}
	}

	private fun buildConversationContext(messages: List<AskAiMessage>, includeNsfw: Boolean): String {
		val recent = messages
			.filter { it.includeNsfw == includeNsfw }
			.takeLast(CONVERSATION_CONTEXT_LIMIT)
		if (recent.isEmpty()) {
			return ""
		}
		return recent.joinToString(separator = "\n") { message ->
			val role = when (message.role) {
				AskAiRole.USER -> "User"
				AskAiRole.ASSISTANT -> "Tarumi"
			}
			"$role: ${message.text.take(MAX_CONTEXT_MESSAGE_CHARS)}"
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

	private fun Manga.toResultCard(): AskAiResultCard {
		return AskAiResultCard(
			id = id,
			title = title,
			url = url,
			publicUrl = publicUrl,
			rating = rating,
			contentRating = contentRating?.name ?: ContentRating.SAFE.name,
			coverUrl = coverUrl.orEmpty(),
			largeCoverUrl = largeCoverUrl,
			description = description.orEmpty(),
			sourceName = (source as? MangaParserSource)?.name ?: source.name,
			typeLabel = detectComicType().label,
			state = state?.name ?: MangaState.ONGOING.name,
			authors = authors.toList(),
			altTitles = altTitles.toList(),
			tags = tags.map { AskAiResultTag(title = it.title, key = it.key) },
		)
	}

	private fun AskAiResultCard.toManga(): Manga? {
		val parserSource = MangaParserSource.entries.firstOrNull { it.name == sourceName } ?: return null
		return Manga(
			id = id,
			title = title,
			altTitles = altTitles.toSet(),
			url = url,
			publicUrl = publicUrl,
			rating = rating,
			contentRating = runCatching { ContentRating.valueOf(contentRating) }.getOrDefault(ContentRating.SAFE),
			coverUrl = coverUrl,
			tags = tags.mapTo(linkedSetOf()) { MangaTag(it.title, it.key, parserSource) },
			state = runCatching { MangaState.valueOf(state) }.getOrDefault(MangaState.ONGOING),
			authors = authors.toSet(),
			largeCoverUrl = largeCoverUrl,
			description = description,
			chapters = emptyList(),
			source = parserSource,
		)
	}

	private fun List<Manga>.appendUniqueTo(destination: MutableList<Manga>, limit: Int) {
		val existingIds = destination.mapTo(hashSetOf()) { it.id }
		for (manga in this) {
			if (destination.size >= limit) {
				break
			}
			if (existingIds.add(manga.id)) {
				destination.add(manga)
			}
		}
	}

	private fun Manga.recommendationScore(
		query: String,
		reference: Manga?,
		requestedType: ComicType?,
		traits: Set<String>,
		history: List<Manga>,
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
		val tagText = normalizedTags()
		for (trait in traits) {
			if (tagText.any { it.contains(trait) || trait.contains(it) }) {
				score += TAG_GENRE_MATCH_SCORE
			}
			for (alias in TRAIT_ALIASES[trait].orEmpty()) {
				val normalizedAlias = alias.normalizedMetadata()
				if (tagText.any { it.contains(normalizedAlias) || normalizedAlias.contains(it) }) {
					score += TAG_GENRE_ALIAS_MATCH_SCORE
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
			if (tagText.any { it.contains(word) }) {
				score += TAG_WORD_SCORE
			}
		}
		val historyTags = history.flatMapTo(linkedSetOf()) { it.normalizedTags() }
		if (historyTags.isNotEmpty()) {
			score += normalizedTags().intersect(historyTags).size.coerceAtMost(MAX_HISTORY_TAG_MATCHES) *
				HISTORY_TAG_MATCH_SCORE
		}
		if (history.any { it.inferredComicType() == inferredComicType() }) {
			score += HISTORY_TYPE_SCORE
		}
		score += (rating * RATING_MULTIPLIER).toInt().coerceAtLeast(0)
		return score
	}

	private fun Manga.matchesRequestedType(requestedType: ComicType?): Boolean {
		return requestedType == null || inferredComicType() == requestedType
	}

	private fun Manga.matchesSafetyMode(includeNsfw: Boolean): Boolean {
		return isNsfw() == includeNsfw
	}

	private fun Manga.matchesRequiredTraits(traits: Set<String>): Boolean {
		val required = traits.intersect(REQUIRED_TRAITS)
		if (required.isEmpty()) {
			return true
		}
		val tagText = normalizedTags()
		val haystack = searchableText().normalizedMetadata()
		return required.all { trait ->
			val aliases = TRAIT_ALIASES[trait].orEmpty() + trait
			aliases.any { alias ->
				val normalizedAlias = alias.normalizedMetadata()
				tagText.any { tag -> tag.contains(normalizedAlias) || normalizedAlias.contains(tag) } ||
					haystack.contains(normalizedAlias)
			}
		}
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

	private fun shouldUseReadingHistory(query: String): Boolean {
		return Regex(
			"""(?i)\b(?:my\s+history|reading\s+history|my\s+library|what\s+i\s+read|what\s+i'?ve\s+read|based\s+on\s+(?:my\s+)?(?:history|library|reading)|from\s+my\s+(?:history|library))\b""",
		).containsMatchIn(query)
	}

	private fun parseRecommendationIntent(query: String): RecommendationIntent {
		val words = searchableWords(query)
		val requestedLimit = REQUESTED_LIMIT_REGEX.find(query)
			?.groupValues
			?.getOrNull(1)
			?.toIntOrNull()
			?.coerceIn(1, MAX_REQUESTED_RESULTS)
			?: RESULT_LIMIT
		val isMoreRequest = MORE_REQUEST_REGEX.containsMatchIn(query)
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
			.replace(QUERY_FILLER_REGEX, " ")
			.replace(Regex("\\s+"), " ")
			.trim()
			.ifBlank { query }
		val traits = detectTraits(query)
		val asksForComics = Regex(
			"""(?i)\b(?:manga|manhwa|manhua|comic|comics|doujin|doujinshi|hentai|webtoon|read|reading)\b""",
		).containsMatchIn(query)
		val asksForRecommendations = Regex(
			"""(?i)\b(?:recommend|recommendation|suggest|suggestion|similar|same|find|show|looking|pick|picks|story|trope|genre)\b""",
		).containsMatchIn(query)
		val isRecommendationRequest = referenceTitle != null ||
			requestedType != null ||
			asksForComics ||
			asksForRecommendations ||
			traits.isNotEmpty() ||
			words.any { it in RECOMMENDATION_WORDS }
		return RecommendationIntent(
			requestedType = requestedType,
			referenceTitle = referenceTitle,
			searchQuery = searchQuery,
			traits = traits,
			requestedLimit = requestedLimit,
			isMoreRequest = isMoreRequest,
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
			if (Regex("""(?i)\b(?:colored|colour|coloured|full\s*color|full\s*colour|full-color|full-colour|colorized|colourized)\b""")
				.containsMatchIn(query)
			) {
				add("colored")
			}
			if (Regex("""(?i)\b(?:cultivat(?:e|es|ed|ing|ion|or|ors)|xianxia|xuanhuan|immortal|immortality|dao|qi|sect)\b""")
				.containsMatchIn(query)
			) {
				add("cultivation")
			}
			if (Regex("""(?i)\b(?:system|systems|level(?:ing|ling|s| up)?|status window|stats?|quest|quests|hunter rank|ranker)\b""")
				.containsMatchIn(query)
			) {
				add("system")
			}
		}
	}

	private fun RecommendationIntent.searchQueries(): List<String> {
		val traitQueries = traits.flatMap { trait -> listOf(trait) + TRAIT_ALIASES[trait].orEmpty() }
		val titleHintQueries = traits.flatMap { trait -> TRAIT_TITLE_HINTS[trait].orEmpty() }
		val pairedTraitQueries = traits.toList()
			.take(MAX_TRAITS_FOR_PAIR_SEARCH)
			.flatMapIndexed { index, first ->
				traits.toList().drop(index + 1).flatMap { second ->
					listOf("$first $second", "$second $first")
				}
			}
		val typeQueries = requestedType?.let { type ->
			val typeName = type.name.lowercase()
			(listOf(searchQuery) + traits + pairedTraitQueries + titleHintQueries).map { query -> "$query $typeName" }
		}.orEmpty()
		return (listOf(searchQuery) + pairedTraitQueries + typeQueries + titleHintQueries + traitQueries)
			.map { it.trim() }
			.filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
			.distinct()
			.take(MAX_SEARCH_QUERIES)
	}

	companion object {

		private const val RESULT_LIMIT = 10
		private const val MAX_REQUESTED_RESULTS = 20
		private const val EXTRA_CANDIDATE_BUFFER = 12
		private const val MAX_CANDIDATE_POOL = 60
		private const val MAX_SEARCH_QUERIES = 18
		private const val MAX_TRAITS_FOR_PAIR_SEARCH = 5
		private const val HISTORY_CONTEXT_LIMIT = 30
		private const val HISTORY_TAG_LIMIT = 12
		private const val HISTORY_SOURCE_LIMIT = 6
		private const val CONVERSATION_CONTEXT_LIMIT = 12
		private const val MAX_CONTEXT_MESSAGE_CHARS = 600
		private const val MAX_STORED_MESSAGES = 80
		private const val CHAT_RETENTION_MS = 2L * 24L * 60L * 60L * 1000L
		private const val DAILY_TOKEN_LIMIT = 10
		private const val DAILY_TOKEN_RESET_MS = 24L * 60L * 60L * 1000L
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
		private const val TAG_WORD_SCORE = 14
		private const val TRAIT_MATCH_SCORE = 18
		private const val TRAIT_ALIAS_MATCH_SCORE = 12
		private const val TAG_GENRE_MATCH_SCORE = 42
		private const val TAG_GENRE_ALIAS_MATCH_SCORE = 28
		private const val HISTORY_TAG_MATCH_SCORE = 5
		private const val HISTORY_TYPE_SCORE = 12
		private const val MAX_HISTORY_TAG_MATCHES = 6
		private const val RATING_MULTIPLIER = 2f
		private const val PREFS_NAME = "ask_ai_history"
		private const val KEY_MESSAGES = "messages"
		private const val KEY_TOKEN_USED = "tokens_used"
		private const val KEY_TOKEN_RESET_AT = "tokens_reset_at"
		private const val STREAM_WORD_DELAY_MS = 34L
		private val WORD_SPLIT_REGEX = Regex("[\\s,./:;!?()\\[\\]{}'\"_-]+")
		private val REFERENCE_REGEX = Regex(
			"""(?i)\b(?:same\s+as|similar\s+to|like)\s+(.+?)(?:[?.!,;]|$)""",
		)
		private val QUERY_FILLER_REGEX = Regex(
			"""(?i)\b(?:that|is|are|was|were|has|have|had|and|or|with|about|where|there|it|its|to|for|in)\b""",
		)
		private val REQUESTED_LIMIT_REGEX = Regex(
			"""(?i)\b([1-9]\d?)\s*(?:more|recommendations?|suggestions?|picks?|comics?|manga|manhwa|manhua|titles?)\b""",
		)
		private val MORE_REQUEST_REGEX = Regex("""(?i)\b(?:more|another|next|continue)\b""")
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
			"murim" to setOf("murim", "murin", "martial arts", "martial", "wuxia", "jianghu"),
			"cultivation" to setOf(
				"cultivation",
				"cultivating",
				"cultivator",
				"cultivators",
				"xianxia",
				"xuanhuan",
				"immortal",
				"immortality",
				"dao",
				"qi",
				"sect",
			),
			"system" to setOf(
				"system",
				"leveling",
				"levelling",
				"level up",
				"status window",
				"stats",
				"quest",
				"quests",
				"ranker",
				"hunter rank",
			),
			"action" to setOf("action", "fight", "battle", "combat"),
			"sexy" to setOf("sexy", "ecchi", "smut", "adult", "mature", "erotic"),
			"incest" to setOf("incest", "sibling", "sister", "brother", "family"),
			"young protagonist" to setOf("young protagonist", "young mc", "young male lead", "young female lead", "boy protagonist", "girl protagonist", "child protagonist"),
			"zombie" to setOf("zombie", "zombies", "undead", "infection", "infected", "apocalypse"),
			"female lead" to setOf(
				"female lead",
				"female mc",
				"female protagonist",
				"girl protagonist",
				"girl mc",
				"girl main character",
				"female main character",
				"female main lead",
				"woman protagonist",
				"heroine",
			),
			"regression" to setOf("regression", "regressor", "returner", "second chance"),
			"reincarnation" to setOf("reincarnation", "reincarnated", "isekai", "transmigration"),
			"romance" to setOf("romance", "romantic", "love", "couple", "relationship"),
			"comedy" to setOf("comedy", "funny", "humor", "humour", "gag"),
			"drama" to setOf("drama", "melodrama", "emotional"),
			"horror" to setOf("horror", "scary", "ghost", "monster", "survival horror"),
			"slice of life" to setOf("slice of life", "school life", "daily life", "school"),
			"sports" to setOf("sports", "sport", "basketball", "football", "soccer", "baseball"),
			"mystery" to setOf("mystery", "detective", "crime", "investigation", "thriller"),
			"sci fi" to setOf("sci fi", "sci-fi", "science fiction", "future", "cyberpunk"),
			"colored" to setOf("colored", "colour", "coloured", "full color", "full colour", "full-color", "colorized"),
		)

		private val TRAIT_TITLE_HINTS = mapOf(
			"cultivation" to setOf(
				"murim",
				"martial peak",
				"nano machine",
				"murim login",
				"heavenly demon",
				"chronicles of heavenly demon",
				"all hail the sect leader",
			),
			"system" to setOf(
				"leveling",
				"solo leveling",
				"player",
				"ranker",
				"quest supremacy",
				"murim login",
				"nano machine",
			),
		)

		private val SAFE_SOURCE_NAMES = listOf(
			"MANGAPLUSPARSER_EN",
			"MANHWAZ",
		)

		private val NSFW_SOURCE_NAMES = listOf(
			"HentaiRead",
			"18PornComic",
		)

		private val REQUIRED_TRAITS = setOf(
			"colored",
			"incest",
			"sexy",
			"young protagonist",
		)
	}
}

private data class RecommendationIntent(
	val requestedType: ComicType?,
	val referenceTitle: String?,
	val searchQuery: String,
	val traits: Set<String>,
	val requestedLimit: Int,
	val isMoreRequest: Boolean,
	val isRecommendationRequest: Boolean,
)

private data class TokenWindow(
	val used: Int,
	val resetAtMillis: Long,
)

data class AskAiState(
	val includeNsfw: Boolean = false,
	val isLoading: Boolean = false,
	val isComposerExpanded: Boolean = true,
	val localModelStatus: LocalAiModelStatus = LocalAiModelStatus.NotDownloaded,
	val remainingTokens: Int = 10,
	val maxTokens: Int = 10,
	val tokenResetAtMillis: Long = System.currentTimeMillis() + 24L * 60L * 60L * 1000L,
	val messages: List<AskAiMessage> = emptyList(),
)

data class AskAiMessage(
	val role: AskAiRole,
	val text: String = "",
	val query: String = "",
	val includeNsfw: Boolean = false,
	val results: List<Manga> = emptyList(),
	val resultCards: List<AskAiResultCard> = emptyList(),
	val isStreaming: Boolean = false,
	val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
enum class AskAiRole {
	USER,
	ASSISTANT,
}

@Serializable
private data class StoredAskAiMessage(
	val role: AskAiRole,
	val text: String,
	val query: String = "",
	val includeNsfw: Boolean = false,
	val resultCards: List<AskAiResultCard> = emptyList(),
	val createdAt: Long,
)

@Serializable
data class AskAiResultCard(
	val id: Long,
	val title: String,
	val url: String,
	val publicUrl: String,
	val rating: Float,
	val contentRating: String,
	val coverUrl: String,
	val largeCoverUrl: String?,
	val description: String,
	val sourceName: String,
	val typeLabel: String,
	val state: String,
	val authors: List<String> = emptyList(),
	val altTitles: List<String> = emptyList(),
	val tags: List<AskAiResultTag> = emptyList(),
)

@Serializable
data class AskAiResultTag(
	val title: String,
	val key: String,
)
