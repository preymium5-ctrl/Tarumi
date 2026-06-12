package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.*
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume

class AllPornComicParser(
	private val context: MangaLoaderContextImpl,
	private val delegate: MangaParser
) : MangaParser by delegate {

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val p = offset + 1
		val urlBuilder = StringBuilder("https://allporncomic.com/page/$p/?s=")
		urlBuilder.append(URLEncoder.encode(filter.query.orEmpty(), "UTF-8"))
		urlBuilder.append("&post_type=wp-manga")

		for (tag in filter.tags) {
			urlBuilder.append("&genre[]=").append(URLEncoder.encode(tag.key, "UTF-8"))
		}

		val sortValue = when (order) {
			SortOrder.UPDATED -> "latest"
			SortOrder.POPULARITY -> "views"
			SortOrder.RATING -> "rating"
			SortOrder.NEWEST -> "new-manga"
			SortOrder.ALPHABETICAL -> "alphabet"
			else -> "latest"
		}
		urlBuilder.append("&m_orderby=").append(sortValue)

		val url = urlBuilder.toString()
		val request = Request.Builder()
			.url(url)
			.header("User-Agent", context.getDefaultUserAgent())
			.build()

		val response = suspendCancellableCoroutine<Response> { continuation ->
			val call = context.httpClient.newCall(request)
			continuation.invokeOnCancellation { call.cancel() }
			call.enqueue(object : Callback {
				override fun onFailure(call: Call, e: IOException) {
					if (continuation.isActive) {
						continuation.resumeWith(Result.failure(e))
					}
				}

				override fun onResponse(call: Call, response: Response) {
					if (continuation.isActive) {
						continuation.resume(response)
					}
				}
			})
		}

		if (!response.isSuccessful) {
			response.close()
			throw IOException("Unexpected HTTP code: ${response.code}")
		}

		val htmlContent = response.body?.string().orEmpty()
		response.close()

		val document = Jsoup.parse(htmlContent, url)
		val elements = document.select("div.row.c-tabs-item__content").ifEmpty {
			document.select("div.page-item-detail")
		}

		val mangaList = ArrayList<Manga>()
		for (element in elements) {
			val linkElement = element.selectFirst("a") ?: continue
			val href = linkElement.attr("href") ?: continue
			val relativeUrl = toRelativeUrl(href, "allporncomic.com")
			val absoluteUrl = toAbsoluteUrl(relativeUrl, "allporncomic.com")
			val uid = generateUid(MangaParserSource.ALLPORN_COMIC, relativeUrl)

			val imgElement = element.selectFirst("img")
			val coverUrl = imgElement?.let { src(it) }

			val titleElement = element.selectFirst("h3, h4, .manga-name, .post-title")
			val title = titleElement?.text()?.trim().orEmpty()
			if (title.isEmpty()) continue

			val ratingElement = element.selectFirst("span.total_votes")
			val rating = ratingElement?.text()?.trim()?.toFloatOrNull()?.let { it / 5.0f } ?: -1.0f

			val genreElements = element.select(".mg_genres a, div.genres-content a")
			val tags = genreElements.mapNotNull { genreElement ->
				val genreText = genreElement.text().trim()
				if (genreText.isEmpty()) null else {
					val genreKey = genreElement.attr("href").trimEnd('/').substringAfterLast('/')
					MangaTag(genreText, genreKey, MangaParserSource.ALLPORN_COMIC)
				}
			}.toSet()

			val statusElement = element.selectFirst(".mg_status .summary-content, div.post-content_item:contains(Status) + div")
			val statusText = statusElement?.text()?.trim()?.lowercase() ?: ""
			val mangaState = when {
				statusText.contains("ongoing") || statusText.contains("on going") -> MangaState.ONGOING
				statusText.contains("completed") || statusText.contains("finished") -> MangaState.FINISHED
				statusText.contains("dropped") || statusText.contains("abandoned") -> MangaState.ABANDONED
				statusText.contains("hiatus") || statusText.contains("paused") -> MangaState.PAUSED
				statusText.contains("upcoming") -> MangaState.UPCOMING
				else -> null
			}

			val authorElement = element.selectFirst(".mg_author a, .mg_artists a")
			val authors = authorElement?.text()?.trim()?.let { setOf(it) } ?: emptySet()

			mangaList.add(
				Manga(
					uid, title, emptySet(), relativeUrl, absoluteUrl, rating, ContentRating.ADULT, coverUrl, tags, mangaState, authors, null, null, null, MangaParserSource.ALLPORN_COMIC
				)
			)
		}
		return mangaList
	}

	private fun src(element: Element): String {
		val srcAttr = element.attr("data-src").trim()
		if (srcAttr.isNotEmpty()) return srcAttr
		return element.attr("src").trim()
	}

	private fun toRelativeUrl(url: String, domain: String): String {
		if (url.startsWith("http://") || url.startsWith("https://")) {
			val path = url.substringAfter(domain).trimStart('/')
			return "/$path"
		}
		return if (url.startsWith("/")) url else "/$url"
	}

	private fun toAbsoluteUrl(url: String, domain: String): String {
		if (url.startsWith("http://") || url.startsWith("https://")) return url
		return "https://" + domain.trim('/') + "/" + url.trimStart('/')
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
}
