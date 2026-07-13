package org.koitharu.kotatsu.details.domain

import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.text.getSpans
import androidx.core.text.parseAsHtml
import coil3.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.MetadataOrigin
import org.koitharu.kotatsu.core.parser.SourceDiagnosticsStore
import org.koitharu.kotatsu.core.parser.normalizeDetailsMetadata
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.explore.domain.RecoverMangaUseCase
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.recoverNotNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

class DetailsLoadUseCase @Inject constructor(
    private val mangaDataRepository: MangaDataRepository,
    private val localMangaRepository: LocalMangaRepository,
    private val mangaRepositoryFactory: MangaRepository.Factory,
    private val recoverUseCase: RecoverMangaUseCase,
    private val imageGetter: Html.ImageGetter,
    private val networkState: NetworkState,
    private val diagnosticsStore: SourceDiagnosticsStore,
) {

    operator fun invoke(intent: MangaIntent, force: Boolean): Flow<MangaDetails> = flow {
        val manga = requireNotNull(mangaDataRepository.resolveIntent(intent, withChapters = true)) {
            "Cannot resolve intent $intent"
        }
        val override = mangaDataRepository.getOverride(manga.id)
        emit(
            MangaDetails(
                manga = manga,
                localManga = null,
                override = override,
                description = manga.descriptionForDisplay()?.parseAsHtml(withImages = false),
                isLoaded = false,
            ),
        )
        if (manga.isLocal) {
            loadLocal(manga, override, force)
        } else {
            loadRemote(manga, override, force)
        }
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * Load local manga + try to load the linked remote one if network is not restricted
     * Suppress any network errors
     */
    private suspend fun FlowCollector<MangaDetails>.loadLocal(manga: Manga, override: MangaOverride?, force: Boolean) {
        val skipNetworkLoad = !force && networkState.isOfflineOrRestricted()
        val localDetails = localMangaRepository.getDetails(manga)
        emit(
            MangaDetails(
                manga = localDetails,
                localManga = null,
                override = override,
                description = localDetails.descriptionForDisplay()?.parseAsHtml(withImages = false),
                isLoaded = skipNetworkLoad,
            ),
        )
        if (skipNetworkLoad) {
            return
        }
        val remoteManga = localMangaRepository.getRemoteManga(manga)
        if (remoteManga == null) {
            emit(
                MangaDetails(
                    manga = localDetails,
                    localManga = null,
                    override = override,
                    description = localDetails.descriptionForDisplay()?.parseAsHtml(withImages = true),
                    isLoaded = true,
                ),
            )
        } else {
            val remoteDetails = getDetails(remoteManga, force).getOrNull()
            val mangaDetails = MangaDetails(
                manga = remoteDetails ?: remoteManga,
                localManga = LocalManga(localDetails),
                override = override,
                description = (remoteDetails ?: localDetails).descriptionForDisplay()?.parseAsHtml(withImages = true),
                isLoaded = true,
            )
            emit(mangaDetails)
            if (remoteDetails != null) {
                // Persist full metadata (tags, description, …) not only chapters.
                mangaDataRepository.storeManga(mangaDetails.toManga(), replaceExisting = true)
            }
        }
    }

    /**
     * Load remote manga + saved one if available
     * Throw network errors after loading local manga only
     */
    private suspend fun FlowCollector<MangaDetails>.loadRemote(
        manga: Manga,
        override: MangaOverride?,
        force: Boolean
    ) = coroutineScope {
        val remoteDeferred = async {
            getDetails(manga, force)
        }
        val localManga = localMangaRepository.findSavedManga(manga, withDetails = true)
        if (localManga != null) {
            emit(
                MangaDetails(
                    manga = manga,
                    localManga = localManga,
                    override = override,
                    description = localManga.manga.descriptionForDisplay()?.parseAsHtml(withImages = true),
                    isLoaded = false,
                ),
            )
        }
        val remoteDetails = remoteDeferred.await().getOrThrow().normalizeDetailsMetadata()
        val mangaDetails = MangaDetails(
            manga = remoteDetails.normalizeSourceMetadata(),
            localManga = localManga,
            override = override,
            description = (remoteDetails.normalizeSourceMetadata().descriptionForDisplay()
                ?: localManga?.manga?.descriptionForDisplay())?.parseAsHtml(withImages = true),
            isLoaded = true,
        )
        emit(mangaDetails)
        // Persist full metadata so tags stay complete the next time this title opens.
        mangaDataRepository.storeManga(mangaDetails.toManga(), replaceExisting = true)
    }

    private suspend fun getDetails(seed: Manga, force: Boolean) = runCatchingCancellable {
        val startedAt = System.currentTimeMillis()
        val repository = mangaRepositoryFactory.create(seed.source)
        // When online (or user forced reload), skip reading the memory cache so
        // incomplete list-page tags are not stuck forever. Offline keeps cache.
        val cachePolicy = if (force || networkState.isOnline()) {
            CachePolicy.WRITE_ONLY
        } else {
            CachePolicy.ENABLED
        }
        val parsedDetails = if (repository is CachingMangaRepository) {
            repository.getDetails(seed, cachePolicy)
        } else {
            repository.getDetails(seed)
        }.normalizeDetailsMetadata().normalizeSourceMetadata()
            .withCompleteTags(seed)
        val smartMatch = parsedDetails.smartMatchMissingMetadata()
        val details = smartMatch.manga
        diagnosticsStore.recordDetails(
            manga = details,
            origin = MetadataOrigin.SOURCE_PARSER,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
        if (smartMatch.hasAnyChange) {
            diagnosticsStore.recordSmartMatch(
                manga = details,
                hasRating = smartMatch.ratingBorrowed,
                hasAuthor = smartMatch.authorBorrowed,
                hasType = smartMatch.typeBorrowed,
            )
        }
        details
    }.recoverNotNull { e ->
        if (e is NotFoundException) {
            recoverUseCase(seed)
        } else {
            null
        }
    }

    /**
     * Many list endpoints return empty/partial tags. Prefer non-empty details tags,
     * and union with seed tags so opening a title still shows a complete set.
     */
    private fun Manga.withCompleteTags(seed: Manga): Manga {
        if (tags.isEmpty() && seed.tags.isEmpty()) {
            return this
        }
        if (tags.isEmpty()) {
            return copy(tags = seed.tags)
        }
        if (seed.tags.isEmpty()) {
            return this
        }
        val merged = LinkedHashMap<String, MangaTag>(tags.size + seed.tags.size)
        fun putBest(tag: MangaTag) {
            val key = tag.key.ifBlank { tag.title }.lowercase(java.util.Locale.ROOT)
            val existing = merged[key]
            if (existing == null) {
                merged[key] = tag
                return
            }
            // Prefer a human title over a raw slug/key.
            val existingIsSlug = existing.title.equals(existing.key, ignoreCase = true)
            val candidateIsSlug = tag.title.equals(tag.key, ignoreCase = true)
            if (existingIsSlug && !candidateIsSlug) {
                merged[key] = tag
            }
        }
        // Details first (freshest), then seed fills gaps.
        tags.forEach(::putBest)
        seed.tags.forEach(::putBest)
        return if (merged.values.toSet() == tags) this else copy(tags = merged.values.toSet())
    }

    private fun Manga.descriptionForDisplay(): String? {
        val text = description ?: return null
        if (source.name != MangaParserSource.DEMONICSCANS.name) {
            return text
        }
        val marker = Regex("""\bThe\s+Summary\s+is\b[:\s]*""", RegexOption.IGNORE_CASE).find(text)
            ?: return text
        val summary = text.substring(marker.range.last + 1).trim()
        return summary.ifBlank { text }
    }

    private fun Manga.normalizeSourceMetadata(): Manga {
        val cleanedAuthors = when {
            source.name == MangaParserSource.DEMONICSCANS.name -> normalizeDemonicScansAuthors()
            source.name.contains("ASURA", ignoreCase = true) -> normalizeAsuraAuthors()
            else -> authors
        }
        return if (cleanedAuthors == authors) this else copy(authors = cleanedAuthors)
    }

    private suspend fun Manga.smartMatchMissingMetadata(): SmartMetadataMatch {
        val isDemonicScans = source.name == MangaParserSource.DEMONICSCANS.name
        val needsRating = rating <= 0f
        val needsAuthor = authors.isEmpty() && !isDemonicScans
        val needsStatus = state == null
        val needsType = !hasTypeTag()
        if (!needsRating && !needsAuthor && !needsStatus && !needsType) {
            return SmartMetadataMatch(this)
        }
        val candidates = mangaDataRepository.findSmartMetadataCandidates(this, SMART_MATCH_LIMIT)
        if (candidates.isEmpty()) {
            return SmartMetadataMatch(this)
        }
        val ratingSource = if (needsRating) candidates.firstOrNull { it.rating > 0f } else null
        val authorSource = if (needsAuthor) candidates.firstOrNull { it.authors.isNotEmpty() } else null
        val statusSource = if (needsStatus) candidates.firstOrNull { it.state != null } else null
        val typeTag = if (needsType) candidates.firstNotNullOfOrNull { it.findTypeTagFor(source) } else null
        val matched = copy(
            rating = ratingSource?.rating ?: rating,
            authors = authorSource?.authors ?: authors,
            state = statusSource?.state ?: state,
            tags = if (typeTag != null) LinkedHashSet<MangaTag>(tags.size + 1).apply {
                add(typeTag)
                addAll(tags)
            } else {
                tags
            },
        )
        return SmartMetadataMatch(
            manga = matched,
            ratingBorrowed = ratingSource != null,
            authorBorrowed = authorSource != null,
            statusBorrowed = statusSource != null,
            typeBorrowed = typeTag != null,
        )
    }

    private fun Manga.hasTypeTag(): Boolean = tags.any { tag ->
        tag.title.equals("Manga", ignoreCase = true) ||
            tag.title.equals("Manhwa", ignoreCase = true) ||
            tag.title.equals("Manhua", ignoreCase = true)
    }

    private fun Manga.findTypeTagFor(targetSource: org.koitharu.kotatsu.parsers.model.MangaSource): MangaTag? {
        val tag = tags.firstOrNull { candidate ->
            candidate.title.equals("Manga", ignoreCase = true) ||
                candidate.title.equals("Manhwa", ignoreCase = true) ||
                candidate.title.equals("Manhua", ignoreCase = true)
        } ?: return null
        return MangaTag(title = tag.title, key = tag.key, source = targetSource)
    }

    private fun Manga.normalizeDemonicScansAuthors(): Set<String> {
        val titleValue = title
        return authors
            .filter { it.isUsefulDemonicScansCreator(titleValue) }
            .ifEmpty {
                tags
                    .filter { tag ->
                        tag.key.contains("author", ignoreCase = true) ||
                            tag.key.contains("artist", ignoreCase = true)
                    }
                    .map { it.title }
                    .filter { it.isUsefulDemonicScansCreator(titleValue) }
            }
            .toSet()
    }

    private fun String.isUsefulDemonicScansCreator(title: String): Boolean {
        val normalized = replace(Regex("""\s+"""), " ").trim()
        if (normalized.length !in 2..64) {
            return false
        }
        if (normalized.equals(title, ignoreCase = true)) {
            return false
        }
        val lower = normalized.lowercase()
        return DEMONIC_CREATOR_JUNK.none { lower.contains(it) }
    }

    private fun Manga.normalizeAsuraAuthors(): Set<String> {
        val result = LinkedHashSet<String>()
        val stopWords = asuraMetadataStopWords()
        for (author in authors) {
            val cleaned = author.cleanAsuraAuthor(stopWords)
            result.addAll(cleaned)
        }
        return result
    }

    private fun Manga.asuraMetadataStopWords(): Set<String> {
        return buildSet {
            addAll(ASURA_AUTHOR_METADATA_LABELS)
            tags.mapTo(this) { it.title }
        }
    }

    private fun String.cleanAsuraAuthor(stopWords: Set<String>): List<String> {
        val normalized = replace(Regex("""\s+"""), " ").trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        val extracted = listOfNotNull(
            normalized.extractAsuraMetadataValue("Author", stopWords),
            normalized.extractAsuraMetadataValue("Artist", stopWords),
        ).filter { it.isUsefulAsuraAuthor() }
        if (extracted.isNotEmpty()) {
            return extracted.distinct()
        }
        return if (normalized.isUsefulAsuraAuthor()) {
            listOf(normalized)
        } else {
            emptyList()
        }
    }

    private fun String.extractAsuraMetadataValue(label: String, stopWords: Set<String>): String? {
        val labelRegex = Regex("""\b${Regex.escape(label)}\b""", RegexOption.IGNORE_CASE)
        val match = labelRegex.find(this) ?: return null
        val start = match.range.last + 1
        var end = length
        for (word in stopWords) {
            if (word.equals(label, ignoreCase = true) || word.isBlank()) {
                continue
            }
            val wordRegex = Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE)
            val next = wordRegex.find(this, start)?.range?.first ?: continue
            if (next < end) {
                end = next
            }
        }
        return substring(start, end)
            .trim(' ', ':', '-', '•', ',', '|')
            .takeIf { it.isNotBlank() }
    }

    private fun String.isUsefulAsuraAuthor(): Boolean {
        if (length > ASURA_AUTHOR_MAX_LENGTH) {
            return false
        }
        return ASURA_AUTHOR_METADATA_LABELS.none { contains(it, ignoreCase = true) }
    }

    private suspend fun String.parseAsHtml(withImages: Boolean): CharSequence? = if (withImages) {
        runInterruptible(Dispatchers.IO) {
            parseAsHtml(imageGetter = imageGetter)
        }.filterSpans()
    } else {
        runInterruptible(Dispatchers.Default) {
            parseAsHtml()
        }.filterSpans().sanitize()
    }.trim().nullIfEmpty()

    private fun Spanned.filterSpans(): Spanned {
        val spannable = SpannableString.valueOf(this)
        val spans = spannable.getSpans<ForegroundColorSpan>()
        for (span in spans) {
            spannable.removeSpan(span)
        }
        return spannable
    }

    private companion object {
        const val ASURA_AUTHOR_MAX_LENGTH = 64
        const val SMART_MATCH_LIMIT = 8

        val ASURA_AUTHOR_METADATA_LABELS = setOf(
            "Rating",
            "Chapters",
            "Bookmarks",
            "Bookmark",
            "Status",
            "Type",
            "Author",
            "Artist",
            "Show more",
            "First Chapter",
            "Premium",
            "Download",
            "Offline",
            "Newest Chapter",
            "Safari",
            "ad blockers",
            "break part of our website",
            "disable your ad blockers",
        )

        val DEMONIC_CREATOR_JUNK = setOf(
            "unknown",
            "updating",
            "updated",
            "n/a",
            "author",
            "artist",
            "rating",
            "status",
            "chapter",
            "bookmark",
            "comment",
            "demonic scans",
            "demonicscans",
            "demon king",
            "greater demon",
            "lesser demon",
        )
    }

    private data class SmartMetadataMatch(
        val manga: Manga,
        val ratingBorrowed: Boolean = false,
        val authorBorrowed: Boolean = false,
        val statusBorrowed: Boolean = false,
        val typeBorrowed: Boolean = false,
    ) {

        val hasAnyChange: Boolean
            get() = ratingBorrowed || authorBorrowed || statusBorrowed || typeBorrowed
    }
}
