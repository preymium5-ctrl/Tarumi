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
				name = "Grok 4.3",
				url = "https://newapi.makelove.cloud/v1/chat/completions",
				apiKey = BuildConfig.AI_GROK_API_KEY,
				model = "grok-4.3",
			)
		)
		for (provider in providers) {
			if (provider.apiKey.isBlank()) {
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
				name = "Grok 4.3",
				url = "https://newapi.makelove.cloud/v1/chat/completions",
				apiKey = BuildConfig.AI_GROK_API_KEY,
				model = "grok-4.3",
			)
		)
		for (provider in providers) {
			if (provider.apiKey.isBlank()) {
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
			name = "Grok 4.3",
			url = "https://newapi.makelove.cloud/v1/chat/completions",
			apiKey = BuildConfig.AI_GROK_API_KEY,
			model = "grok-4.3",
		)
		if (provider.apiKey.isBlank()) {
			return@withContext "CONVERSATION"
		}
		val prompt = """
			Analyze the user's message and determine if they are asking to search or recommend a list of comics, manga, manhwa, or manhua.
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

	suspend fun generateVisionReply(
		query: String,
		imageBase64: String,
		includeNsfw: Boolean,
	): String? = withContext(Dispatchers.IO) {
		val provider = AiProvider(
			name = "Grok 4.3",
			url = "https://newapi.makelove.cloud/v1/chat/completions",
			apiKey = BuildConfig.AI_GROK_API_KEY,
			model = "grok-4.3",
		)
		if (provider.apiKey.isBlank()) {
			return@withContext null
		}
		runCatchingCancellable {
			provider.requestVision(query, imageBase64)
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
						put("content", JsonPrimitive("You are Tarumi AI, a capable conversational assistant and source-backed comics librarian. Reply in plain text only."))
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
			put("max_tokens", JsonPrimitive(700))
		}.toString()
		val request = Request.Builder()
			.url(url)
			.header("Authorization", "Bearer $apiKey")
			.header("Content-Type", "application/json")
			.apply {
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
						put("content", JsonPrimitive("You are Tarumi AI, a capable conversational assistant and source-backed comics librarian. Reply in plain text only."))
					},
				)
				add(
					buildJsonObject {
						put("role", JsonPrimitive("user"))
						put("content", buildJsonArray {
							add(
								buildJsonObject {
									put("type", JsonPrimitive("input_image"))
									put("image_url", JsonPrimitive(fullBase64))
								}
							)
							add(
								buildJsonObject {
									put("type", JsonPrimitive("input_text"))
									put("text", JsonPrimitive(prompt))
								}
							)
						})
					},
				)
			})
			put("temperature", JsonPrimitive(0.7))
			put("max_tokens", JsonPrimitive(700))
		}.toString()

		val request = Request.Builder()
			.url(url)
			.header("Authorization", "Bearer $apiKey")
			.header("Content-Type", "application/json")
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
			follow-ups naturally. Briefly explain the shared genre, trope, or story qualities that make these
			candidates relevant. Mention that the cards below are the best source-backed matches. Do not invent
			titles outside the candidate list.
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
			Recent conversation:
			${conversationContext.ifBlank { "No prior chat turns are available." }}

			Library context:
			${libraryContext.ifBlank { "No reading history context is available yet." }}
			User message: "$query"

			Current mode: ${if (includeNsfw) "18+ conversation only. Do not recommend safe-mode sources unless the user turns 18+ mode off." else "Safe conversation only. Do not discuss or recommend adult/NSFW comics."}
			Reply naturally and answer the message directly. You can discuss the app, comics, characters,
			recommendation ideas, or general topics. If the user asks for source-backed comic picks, suggest
			they ask by mood, trope, genre, or similar title.
		""".trimIndent()
	}

	private data class AiProvider(
		val name: String,
		val url: String,
		val apiKey: String,
		val model: String,
		val isOpenRouter: Boolean = false,
	)

	private companion object {
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
