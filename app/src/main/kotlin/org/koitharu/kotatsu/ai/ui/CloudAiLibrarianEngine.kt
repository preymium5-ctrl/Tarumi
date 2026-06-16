package org.koitharu.kotatsu.ai.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudAiLibrarianEngine @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	suspend fun generateReply(
		query: String,
		includeNsfw: Boolean,
		results: List<Manga>,
		libraryContext: String = "",
		conversationContext: String = "",
	): String? = withContext(Dispatchers.IO) {
		val prompt = buildPrompt(query, includeNsfw, results, libraryContext, conversationContext)
		val providers = listOf(
			AiProvider(
				name = "Cloudflare AI proxy",
				url = BuildConfig.AI_CLOUD_PROXY_URL,
				clientToken = BuildConfig.AI_CLOUD_PROXY_CLIENT_TOKEN,
				model = BuildConfig.AI_CLOUD_PROXY_MODEL,
			)
		)
		for (provider in providers) {
			if (provider.url.isBlank()) {
				continue
			}
			val reply = runCatchingCancellable {
				provider.request(prompt)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()
			if (!reply.isNullOrBlank()) {
				return@withContext reply
			}
		}
		null
	}

	suspend fun generateConversationReply(
		query: String,
		includeNsfw: Boolean,
		libraryContext: String = "",
		conversationContext: String = "",
	): String? = withContext(Dispatchers.IO) {
		val prompt = buildConversationPrompt(query, includeNsfw, libraryContext, conversationContext)
		val providers = listOf(
			AiProvider(
				name = "Cloudflare AI proxy",
				url = BuildConfig.AI_CLOUD_PROXY_URL,
				clientToken = BuildConfig.AI_CLOUD_PROXY_CLIENT_TOKEN,
				model = BuildConfig.AI_CLOUD_PROXY_MODEL,
			)
		)
		for (provider in providers) {
			if (provider.url.isBlank()) {
				continue
			}
			val reply = runCatchingCancellable {
				provider.request(prompt)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()
			if (!reply.isNullOrBlank()) {
				return@withContext reply
			}
		}
		null
	}

	suspend fun classifyIntent(
		query: String,
	): String = withContext(Dispatchers.IO) {
		val provider = AiProvider(
			name = "Cloudflare AI proxy",
			url = BuildConfig.AI_CLOUD_PROXY_URL,
			clientToken = BuildConfig.AI_CLOUD_PROXY_CLIENT_TOKEN,
			model = BuildConfig.AI_CLOUD_PROXY_MODEL,
		)
		if (provider.url.isBlank()) {
			return@withContext "CONVERSATION"
		}
		val prompt = """
			Analyze the user's message and determine their intent:
			- If they are asking for recommendations, list of titles, suggestions of what to read, or similar series, return "RECOMMENDATION".
			- If they are asking general questions about characters, plots, authors, lore, or having a general conversation (even if they mention a comic title), return "CONVERSATION".
			Respond with exactly one word: "RECOMMENDATION" or "CONVERSATION". Do not include any punctuation or extra text.

			User message: "$query"
		""".trimIndent()
		val reply = runCatchingCancellable {
			provider.request(prompt)
		}.getOrNull()

		if (reply?.trim()?.uppercase() == "RECOMMENDATION") {
			"RECOMMENDATION"
		} else {
			"CONVERSATION"
		}
	}

	suspend fun classifyVisionIntent(
		query: String,
	): String = withContext(Dispatchers.IO) {
		val provider = AiProvider(
			name = "Cloudflare AI proxy",
			url = BuildConfig.AI_CLOUD_PROXY_URL,
			clientToken = BuildConfig.AI_CLOUD_PROXY_CLIENT_TOKEN,
			model = BuildConfig.AI_CLOUD_PROXY_MODEL,
		)
		if (provider.url.isBlank()) {
			return@withContext "IDENTIFY"
		}
		val prompt = """
			Analyze the user's message that accompanies an image they uploaded. Determine their intent:
			- If they want to IDENTIFY the comic/manga/manhwa/manhua title shown in the image, or find where to read it, or want source-backed results from that image, return "IDENTIFY".
			- If they are asking a GENERAL question about the image (e.g. who is this character, what's happening in this scene, describe this image, what art style is this, translate this text, or any other question that just needs a direct answer about the image content), return "GENERAL".
			- If no text query was provided and only an image is sent, return "IDENTIFY".
			Respond with exactly one word: "IDENTIFY" or "GENERAL". Do not include any punctuation or extra text.

			User message: "$query"
		""".trimIndent()
		val reply = runCatchingCancellable {
			provider.request(prompt)
		}.getOrNull()

		if (reply?.trim()?.uppercase() == "GENERAL") {
			"GENERAL"
		} else {
			"IDENTIFY"
		}
	}

	suspend fun generateImage(
		prompt: String,
		includeNsfw: Boolean,
	): ImageGenerationResult = withContext(Dispatchers.IO) {
		ImageGenerationResult(error = "Image generation is currently disabled to save API tokens.")
	}

	private fun extractImageError(responseBody: String): String {
		return runCatching {
			val root = json.parseToJsonElement(responseBody).jsonObject
			val error = root["error"] ?: return@runCatching ""
			when (error) {
				is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
					?: error["code"]?.jsonPrimitive?.contentOrNull
					?: error.toString()
				else -> error.jsonPrimitive.contentOrNull ?: error.toString()
			}
		}.getOrDefault("")
	}

	private fun buildImagePrompt(prompt: String, includeNsfw: Boolean): String {
		return if (includeNsfw) {
			"""
				NSFW mode is enabled. Generate an explicit, uncensored adult illustration based on the user's prompt.
				Nudity, sexual themes, and mature content are fully allowed and expected.
				All depicted characters must be adults (18+). Do not depict minors, non-consensual acts, or illegal content.

				User prompt:
				$prompt
			""".trimIndent()
		} else {
			"""
				Safe mode is enabled. Generate a clean, non-explicit illustration from this prompt.
				Keep it suitable for general audiences. No nudity, sexual content, fetish content, graphic violence, or suggestive framing.

				User prompt:
				$prompt
			""".trimIndent()
		}
	}

	suspend fun generateVisionSearchQuery(
		imageBase64: String,
	): String? = withContext(Dispatchers.IO) {
		val provider = AiProvider(
			name = "Cloudflare AI proxy",
			url = BuildConfig.AI_CLOUD_PROXY_URL,
			clientToken = BuildConfig.AI_CLOUD_PROXY_CLIENT_TOKEN,
			model = BuildConfig.AI_CLOUD_PROXY_MODEL,
		)
		if (provider.url.isBlank()) {
			return@withContext null
		}
		val prompt = """
			Look at this image and identify the exact title of the comic/manga/manhwa/manhua shown.

			Use your web search to look up this image and find:
			1. The exact title of the series
			2. Any character names visible or recognizable
			3. The chapter or volume number if visible

			Steps:
			1. First, read ALL visible text — title text, dialogue, watermarks, credits, chapter numbers.
			2. Search the web using the most distinctive text or visual elements you can see.
			3. If you recognize the art style or characters, search for those directly.
			4. Correct any obvious misspellings in visible text before searching.

			Output ONLY the most likely title as a plain text search query. No quotes, no explanation, no formatting.
			If you can identify the title with confidence, output just the title name.
		""".trimIndent()
		runCatchingCancellable {
			provider.requestVision(prompt, imageBase64)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
	}

	suspend fun generateVisionReply(
		query: String,
		imageBase64: String,
		includeNsfw: Boolean,
		conversationContext: String = "",
		webSearchContext: String = "",
	): String? = withContext(Dispatchers.IO) {
		val provider = AiProvider(
			name = "Cloudflare AI proxy",
			url = BuildConfig.AI_CLOUD_PROXY_URL,
			clientToken = BuildConfig.AI_CLOUD_PROXY_CLIENT_TOKEN,
			model = BuildConfig.AI_CLOUD_PROXY_MODEL,
		)
		if (provider.url.isBlank()) {
			return@withContext null
		}
		val visionPrompt = buildString {
			if (conversationContext.isNotBlank()) {
				append("Recent conversation:\n")
				append(conversationContext)
				append("\n\n")
			}
			if (webSearchContext.isNotBlank()) {
				append("Web Search Results (use these to identify the comic title, character names, and source):\n")
				append(webSearchContext)
				append("\n\n")
				append("IMPORTANT: Use the web search results above along with your own knowledge to identify the comic/manhwa/manga title and any characters shown. If a title appears in the search results that matches what you see in the image, state it confidently.\n\n")
			}
			append(query)
		}
		runCatchingCancellable {
			provider.requestVision(visionPrompt, imageBase64)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private fun AiProvider.request(prompt: String): String? {
		val body = buildJsonObject {
			put("model", JsonPrimitive(model))
			put("messages", buildJsonArray {
				add(
					buildJsonObject {
						put("role", JsonPrimitive("system"))
						put("content", JsonPrimitive("You are Tarumi AI, an exceptionally knowledgeable and intelligent assistant. You have deep expertise in manga, manhwa, manhua, anime, and comics, including characters, authors, art styles, plot details, publication history, and cultural context. You also have broad general knowledge and can answer questions on any topic thoughtfully and accurately. Always provide detailed, well-reasoned responses. Use your full knowledge to give the best possible answer. Reply in plain text only."))
					},
				)
				add(
					buildJsonObject {
						put("role", JsonPrimitive("user"))
						put("content", JsonPrimitive(prompt))
					},
				)
			})
			put("temperature", JsonPrimitive(0.7))
			put("max_tokens", JsonPrimitive(1200))
			put("search_parameters", buildJsonObject {
				put("mode", JsonPrimitive("auto"))
			})
		}.toString()
		val request = Request.Builder()
			.url(url)
			.header("Content-Type", "application/json")
			.apply {
				if (clientToken.isNotBlank()) {
					header("X-Tarumi-Client-Token", clientToken)
				}
				if (isOpenRouter) {
					header("HTTP-Referer", "https://github.com/preymium5-ctrl/Tarumi")
					header("X-Title", "Tarumi")
				}
			}
			.post(body.toRequestBody(JSON_MEDIA_TYPE))
			.build()
		okHttpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				return null
			}
			val responseBody = response.body.string()
			val root = json.parseToJsonElement(responseBody).jsonObject
			return root["choices"]
				?.jsonArray
				?.firstOrNull()
				?.jsonObject
				?.get("message")
				?.jsonObject
				?.get("content")
				?.jsonPrimitive
				?.contentOrNull
				?.trim()
				?.takeIf { it.isNotEmpty() }
		}
	}

	private fun AiProvider.requestVision(prompt: String, imageBase64: String): String? {
		val prefix = if (imageBase64.startsWith("data:image")) "" else "data:image/png;base64,"
		val fullBase64 = prefix + imageBase64

		val body = buildJsonObject {
			put("model", JsonPrimitive(model))
			put("messages", buildJsonArray {
				add(
					buildJsonObject {
						put("role", JsonPrimitive("system"))
						put("content", JsonPrimitive("You are Tarumi AI, an expert at identifying manga, manhwa, manhua, and comic panels. When asked to identify a comic from an image, you MUST use your web search capabilities to look up the image. Try multiple search queries including searching on community discussion forums and databases (like site:reddit.com, site:quora.com, site:myanimelist.net, anilist.co, etc.) to check where this image, scene, or character has been discussed. Analyze the search results carefully to extract the correct title. Do NOT guess or rely only on internal knowledge — search the web to verify. When you identify a title, state it confidently with evidence from your search. Reply in plain text only."))
					},
				)
				add(
					buildJsonObject {
						put("role", JsonPrimitive("user"))
						put("content", buildJsonArray {
							add(
								buildJsonObject {
									put("type", JsonPrimitive("image_url"))
									put("image_url", buildJsonObject {
										put("url", JsonPrimitive(fullBase64))
									})
								}
							)
							add(
								buildJsonObject {
									put("type", JsonPrimitive("text"))
									put("text", JsonPrimitive(prompt))
								}
							)
						})
					},
				)
			})
			put("temperature", JsonPrimitive(0.7))
			put("max_tokens", JsonPrimitive(1200))
			put("search_parameters", buildJsonObject {
				put("mode", JsonPrimitive("on"))
			})
		}.toString()

		val request = Request.Builder()
			.url(url)
			.header("Content-Type", "application/json")
			.apply {
				if (clientToken.isNotBlank()) {
					header("X-Tarumi-Client-Token", clientToken)
				}
			}
			.post(body.toRequestBody(JSON_MEDIA_TYPE))
			.build()

		okHttpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				return null
			}
			val responseBody = response.body.string()
			val root = json.parseToJsonElement(responseBody).jsonObject
			return root["choices"]
				?.jsonArray
				?.firstOrNull()
				?.jsonObject
				?.get("message")
				?.jsonObject
				?.get("content")
				?.jsonPrimitive
				?.contentOrNull
				?.trim()
				?.takeIf { it.isNotEmpty() }
		}
	}

	private fun buildPrompt(
		query: String,
		includeNsfw: Boolean,
		results: List<Manga>,
		libraryContext: String,
		conversationContext: String,
	): String {
		val personality = if (includeNsfw) {
			"You are the 18+ Tarumi librarian. Be playful and cheeky, but do not write explicit sexual content."
		} else {
			"You are the Safe Tarumi librarian. Be kind, smart, and calm."
		}
		val cards = results.take(20).joinToString(separator = "\n") { manga ->
			val tags = manga.tags.take(6).joinToString { it.title }
			val synopsis = manga.description
				.orEmpty()
				.replace(Regex("\\s+"), " ")
				.take(180)
				.ifBlank { "no synopsis" }
			"- ${manga.title} | ${manga.detectComicType().label} | ${manga.source.name} | " +
				"${tags.ifBlank { "no tags" }} | $synopsis"
		}
		return """
			$personality
			Recent conversation:
			${conversationContext.ifBlank { "No prior chat turns are available." }}

			Library context:
			${libraryContext.ifBlank { "No reading history context is available yet." }}
			User request: "$query"
			Use only these candidate comics for recommendations:
			$cards

			Current mode: ${if (includeNsfw) "18+ only. Recommend adult/NSFW candidates only." else "Safe only. Recommend non-adult candidates only."}
			Honor every requested format such as manga, manhwa, or manhua. Use the conversation context to answer
			follow-ups naturally. For each recommendation, explain WHY the user would enjoy it — mention shared
			genres, tropes, story qualities, art style similarities, or thematic connections that make each pick
			relevant. Give thoughtful, personalized reasoning, not just generic descriptions. Mention that the
			cards below are the best source-backed matches. Do not invent titles outside the candidate list.
		""".trimIndent()
	}

	private fun buildConversationPrompt(
		query: String,
		includeNsfw: Boolean,
		libraryContext: String,
		conversationContext: String,
	): String {
		val personality = if (includeNsfw) {
			"You are Tarumi AI in 18+ mode. Be playful and cheeky, but do not write explicit sexual content."
		} else {
			"You are Tarumi AI in safe mode. Be kind, smart, friendly, and concise."
		}
		return """
			$personality
			You are an exceptionally knowledgeable AI with deep expertise in manga, manhwa, manhua, anime, comics, and broad general knowledge.
			You can discuss characters, plot details, authors, art styles, publication history, cultural context, and any other topic with depth and accuracy.
			Always think carefully before answering. Provide detailed, well-reasoned, and insightful responses.

			Recent conversation:
			${conversationContext.ifBlank { "No prior chat turns are available." }}

			Library context:
			${libraryContext.ifBlank { "No reading history context is available yet." }}
			User message: "$query"

			Current mode: ${if (includeNsfw) "18+ conversation. Adult topics and NSFW comic discussions are allowed." else "Safe conversation. Keep responses appropriate for general audiences."}
			Answer the user's question directly and thoroughly. Use your full knowledge to give the best possible answer.
			If the user asks about a specific character, give detailed information. If they ask about a plot, explain it well.
			If they ask a general knowledge question, answer it accurately. Be conversational but informative.
		""".trimIndent()
	}

	data class ImageGenerationResult(
		val image: String? = null,
		val error: String? = null,
	)

	private data class AiProvider(
		val name: String,
		val url: String,
		val clientToken: String,
		val model: String,
		val isOpenRouter: Boolean = false,
	)

	private companion object {
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
