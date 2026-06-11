package org.koitharu.kotatsu.parsers.site.en

import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

class AsuraScansParser(context: org.koitharu.kotatsu.parsers.MangaLoaderContext) : PagedMangaParser(context as MangaLoaderContextImpl, MangaParserSource.ASURASCANS, 20, 20) {

    private val regexDate = Regex("(\\d+)(st|nd|rd|th)")
    private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RATING,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.ALPHABETICAL
    )

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("asurascans.com")

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true
    )

    private val availableTags: LinkedHashSet<MangaTag> by lazy {
        val list = ASURA_GENRES
        val set = LinkedHashSet<MangaTag>(list.size)
        for (genre in list) {
            val key = genre.trim().lowercase(Locale.ENGLISH).replace(asuraGenreKeyRegex, "-").trim('-')
            set.add(MangaTag(genre, key, source))
        }
        set
    }

    private val tagMap: Map<String, MangaTag> by lazy {
        availableTags.associateBy { it.title.lowercase(Locale.ENGLISH) }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = availableTags,
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED, MangaState.PAUSED, MangaState.UPCOMING),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA)
        )
    }

    private fun toAbsoluteUrl(url: String, domain: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "https://" + domain.removePrefix("http://").removePrefix("https://").trimEnd('/') + "/" + url.trimStart('/')
    }

    private fun attrAsRelativeUrl(attr: String, element: Element): String {
        val raw = element.attr(attr).trim()
        val httpUrl = raw.toHttpUrlOrNull() ?: return raw
        val path = httpUrl.encodedPath
        val query = httpUrl.encodedQuery
        return if (query != null) "$path?$query" else path
    }

    private fun src(element: Element): String {
        val srcAttr = element.attr("data-src").trim()
        if (srcAttr.isNotEmpty()) return srcAttr
        return element.attr("src").trim()
    }

    private fun substringBetween(str: String, open: String, close: String): String? {
        val start = str.indexOf(open)
        if (start != -1) {
            val end = if (close.isEmpty()) str.length else str.indexOf(close, start + open.length)
            if (end != -1) {
                return str.substring(start + open.length, end)
            }
        }
        return null
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(toAbsoluteUrl(manga.url, domain))
        val document = org.jsoup.Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())

        val titleElement = document.selectFirst("article h1")
        val title = titleElement?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: manga.title

        val altTitlesText = document.selectFirst("#alt-titles")?.text() ?: ""
        val altTitles = altTitlesText.split('•', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val description = document.selectFirst("#description-text")?.html()?.trim()
            ?: document.selectFirst("span.font-medium.text-sm")?.text()?.trim()
            ?: ""

        val tagElements = document.select("a[href*=\"genres=\"]")
        val tags = tagElements.toList().mapNotNull { element ->
            val titleText = element.text().trim()
            if (titleText.isEmpty()) null else {
                tagMap[titleText.lowercase(Locale.ENGLISH)]
                    ?: MangaTag(titleText, titleText.lowercase(Locale.ENGLISH).replace(asuraGenreKeyRegex, "-").trim('-'), source)
            }
        }.toSet()

        val statusText = document.selectFirst("div:containsOwn(Status) + div")?.text()?.trim()?.lowercase(Locale.ENGLISH) ?: ""
        val state = when (statusText) {
            "ongoing" -> MangaState.ONGOING
            "completed", "finished" -> MangaState.FINISHED
            "dropped" -> MangaState.ABANDONED
            "hiatus", "paused" -> MangaState.PAUSED
            "upcoming", "coming soon" -> MangaState.UPCOMING
            else -> null
        }

        val authorName = document.selectFirst("div:has(span:containsOwn(Author)) > *:last-child")?.text()?.trim() ?: ""
        val artistName = document.selectFirst("div:has(span:containsOwn(Artist)) > *:last-child")?.text()?.trim() ?: ""

        val authors = LinkedHashSet<String>()
        if (authorName.isNotEmpty() && !authorName.equals("unknown", ignoreCase = true)) {
            authors.add(authorName)
        }
        if (artistName.isNotEmpty() && !artistName.equals("unknown", ignoreCase = true)) {
            authors.add(artistName)
        }

        val chapterElements = document.select("a.group[href*=/chapter/]")
        val chapters = ArrayList<MangaChapter>()
        val seenUids = HashSet<Long>()
        var chapterIndex = 0

        val chapterElementsList = chapterElements.toList()
        for (element in chapterElementsList.reversed()) {
            val url = attrAsRelativeUrl("href", element)
            val nameElement = element.selectFirst("span.font-medium") ?: element.selectFirst("span")
            var name = nameElement?.text()?.trim() ?: ""

            val subtitleElement = element.selectFirst("span.text-sm.text-white\\/50")
            val subtitle = subtitleElement?.text()?.trim() ?: ""

            if (name.isNotEmpty() && subtitle.isNotEmpty()) {
                name = "$name - $subtitle"
            } else if (name.isEmpty() && subtitle.isNotEmpty()) {
                name = subtitle
            }

            val match = chapterNumberRegex.find(name)
            val number = match?.groupValues?.get(1)?.toFloatOrNull() ?: (chapterIndex + 1).toFloat()

            val dateElement = element.selectFirst("span.text-sm.text-white\\/40")
            val dateText = dateElement?.text()?.trim() ?: ""

            val uploadDate = if (dateText.isNotEmpty()) {
                parseDate(dateText)
            } else {
                0L
            }

            val uid = generateUid(source, url)
            if (seenUids.add(uid)) {
                chapters.add(MangaChapter(uid, name, number, 0, url, null, uploadDate, null, source))
            } else {
                chapterIndex++
            }
        }

        val cutOffTime = System.currentTimeMillis() - 21600000
        val finalChapters = chapters.filter { it.uploadDate == 0L || it.uploadDate <= cutOffTime }

        return manga.copy(
            title = title,
            altTitles = altTitles,
            description = description,
            tags = tags,
            state = state,
            authors = authors,
            chapters = finalChapters
        )
    }

    override suspend fun getListPage(page: Int, sortOrder: SortOrder, filter: MangaListFilter): List<Manga> {
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .encodedPath("/browse")
            .addQueryParameter("page", page.toString())

        if (!filter.query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("search", filter.query)
        }
        if (filter.tags.isNotEmpty()) {
            urlBuilder.addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
        }

        val state = filter.states.firstOrNull()
        if (state != null) {
            val statusValue = when (state) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                MangaState.ABANDONED -> "dropped"
                MangaState.PAUSED -> "hiatus"
                MangaState.UPCOMING -> "upcoming"
                else -> ""
            }
            if (statusValue.isNotEmpty()) {
                urlBuilder.addQueryParameter("status", statusValue)
            }
        }

        val type = filter.types.firstOrNull()
        if (type != null) {
            val typeValue = when (type) {
                ContentType.MANGA -> "manga"
                ContentType.MANHWA -> "manhwa"
                ContentType.MANHUA -> "manhua"
                else -> ""
            }
            if (typeValue.isNotEmpty()) {
                urlBuilder.addQueryParameter("types", typeValue)
            }
        }

        val sortValue = when (sortOrder) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.RATING -> "rating"
            SortOrder.ALPHABETICAL -> "asc"
            SortOrder.ALPHABETICAL_DESC -> "desc"
            SortOrder.UPDATED -> "update"
            else -> ""
        }
        if (sortValue.isNotEmpty()) {
            urlBuilder.addQueryParameter("sort", sortValue)
        }

        val response = webClient.httpGet(urlBuilder.build())
        val document = org.jsoup.Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())

        val cardElements = document.select("#series-grid .series-card")
        val mangaList = ArrayList<Manga>()

        val cardElementsList = cardElements.toList()
        for (element in cardElementsList) {
            val linkElement = element.selectFirst("a[href*=/comics/]") ?: continue
            val url = attrAsRelativeUrl("href", linkElement)
            val uid = generateUid(source, url)
            val absoluteUrl = toAbsoluteUrl(url, domain)

            val imgElement = element.selectFirst("img")
            val coverUrl = imgElement?.let { src(it) }

            val titleText = element.selectFirst("h3")?.text()?.trim() ?: ""
            val ratingText = element.selectFirst("div.absolute.top-2.right-2 span")?.text()?.trim() ?: ""
            val rating = ratingText.toFloatOrNull() ?: -1.0f

            val statusElement = element.select("div.p-3 span").lastOrNull()
            val statusValue = statusElement?.text()?.trim()?.lowercase(Locale.ENGLISH) ?: ""
            val mangaState = when (statusValue) {
                "ongoing" -> MangaState.ONGOING
                "completed", "finished" -> MangaState.FINISHED
                "dropped" -> MangaState.ABANDONED
                "hiatus", "paused" -> MangaState.PAUSED
                "upcoming", "coming soon" -> MangaState.UPCOMING
                else -> null
            }

            val contentRating = if (isNsfwSource) ContentRating.ADULT else null
            mangaList.add(
                Manga(
                    uid, titleText, emptySet(), url, absoluteUrl, rating, contentRating, coverUrl, emptySet(), mangaState, emptySet(), null, null, null, source
                )
            )
        }
        return mangaList
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet(toAbsoluteUrl(chapter.url, domain))
        val document = org.jsoup.Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())

        val astroIsland = document.selectFirst("astro-island[component-url*='ChapterReader']")
        val props = astroIsland?.attr("props")
        if (props != null) {
            val decodedProps = props.replace("&quot;", "\"")
            val matches = pageUrlRegex.findAll(decodedProps)
            val urls = matches.map { it.groupValues[1] }.distinct().toList()
            if (urls.isNotEmpty()) {
                return urls.map { url ->
                    MangaPage(generateUid(source, url), url, null, source)
                }
            }
        }

        val scriptElements = document.select("script")
        val sb = StringBuilder()
        val scriptElementsList = scriptElements.toList()
        for (element in scriptElementsList) {
            val data = element.data()
            val matchedText = substringBetween(data, "self.__next_f.push(", "")
            if (matchedText != null && matchedText.isNotEmpty()) {
                try {
                    val jsonArray = JSONArray(matchedText)
                    for (i in 0 until jsonArray.length()) {
                        val value = jsonArray.opt(i)
                        if (value is String) {
                            sb.append(value)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        val lines = sb.toString().split('\n')
        val pagesMap = TreeMap<Int, String>()
        for (line in lines) {
            try {
                val jsonStr = line.substringAfter(':')
                val jsonObject = JSONObject(jsonStr)
                if (jsonObject.has("order") && jsonObject.has("url")) {
                    pagesMap[jsonObject.getInt("order")] = jsonObject.getString("url")
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return pagesMap.values.map { url ->
            MangaPage(generateUid(source, url), url, null, source)
        }
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private fun parseDate(dateStr: String): Long {
        val lower = dateStr.lowercase(Locale.US).trim()
        if (lower.isEmpty()) return 0L

        val now = Calendar.getInstance()
        when {
            lower == "today" -> {
                return now.timeInMillis
            }
            lower == "yesterday" -> {
                now.add(Calendar.DAY_OF_YEAR, -1)
                return now.timeInMillis
            }
            lower == "last week" -> {
                now.add(Calendar.WEEK_OF_YEAR, -1)
                return now.timeInMillis
            }
            lower.endsWith(" ago") -> {
                val amountStr = Regex("(\\d+)").find(lower)?.groupValues?.get(1) ?: return 0L
                val amount = amountStr.toIntOrNull() ?: return 0L
                when {
                    "second" in lower -> now.add(Calendar.SECOND, -amount)
                    "minute" in lower || "min" in lower -> now.add(Calendar.MINUTE, -amount)
                    "hour" in lower -> now.add(Calendar.HOUR_OF_DAY, -amount)
                    "day" in lower -> now.add(Calendar.DAY_OF_YEAR, -amount)
                    "week" in lower -> now.add(Calendar.WEEK_OF_YEAR, -amount)
                    "month" in lower -> now.add(Calendar.MONTH, -amount)
                    "year" in lower -> now.add(Calendar.YEAR, -amount)
                    else -> return 0L
                }
                return now.timeInMillis
            }
            else -> {
                return try {
                    val normalized = regexDate.replace(dateStr, "$1")
                    synchronized(chapterDateFormat) {
                        chapterDateFormat.parse(normalized)?.time ?: 0L
                    }
                } catch (e: Exception) {
                    0L
                }
            }
        }
    }

    private fun generateUid(source: MangaParserSource, url: String): Long {
        var hash = 1125899906842597L
        for (char in source.name) {
            hash = char.code.toLong() + 31L * hash
        }
        for (char in url) {
            hash = char.code.toLong() + 31L * hash
        }
        return hash
    }

    companion object {
        val chapterNumberRegex = Regex("Chapter\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val pageUrlRegex = Regex("\"url\":\\s*\\[0,\\s*\"([^\"]+)\"")
        val ASURA_GENRES = listOf("Action", "Adventure", "Comedy", "Crazy MC", "Demon", "Dungeons", "Fantasy", "Game", "Genius MC", "Isekai", "Magic", "Murim", "Mystery", "Necromancer", "Overpowered", "Regression", "Reincarnation", "Revenge", "Romance", "School Life", "Sci-fi", "Shoujo", "Shounen", "System", "Tower", "Tragedy", "Villain", "Violence")
        val asuraGenreKeyRegex = Regex("[^a-z0-9]+")
    }
}
