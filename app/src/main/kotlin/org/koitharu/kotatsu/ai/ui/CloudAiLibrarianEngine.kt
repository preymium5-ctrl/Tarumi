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
	): String? = withContext(Dispatchers.IO) {
		val prompt = buildPrompt(query, includeNsfw, results)
		val providers = listOf(
			AiProvider(
				name = "Groq",
				url = "https://api.groq.com/openai/v1/chat/completions",
				apiKey = BuildConfig.AI_GROQ_API_KEY,
				model = BuildConfig.AI_GROQ_MODEL,
			),
			AiProvider(
				name = "OpenRouter Gemini",
				url = "https://openrouter.ai/api/v1/chat/completions",
				apiKey = BuildConfig.AI_OPENROUTER_GEMINI_API_KEY,
				model = BuildConfig.AI_OPENROUTER_GEMINI_MODEL,
				isOpenRouter = true,
			),
			AiProvider(
				name = "OpenRouter Nemotron",
				url = "https://openrouter.ai/api/v1/chat/completions",
				apiKey = BuildConfig.AI_OPENROUTER_NEMOTRON_API_KEY,
				model = BuildConfig.AI_OPENROUTER_NEMOTRON_MODEL,
				isOpenRouter = true,
			),
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
	): String? = withContext(Dispatchers.IO) {
		val prompt = buildConversationPrompt(query, includeNsfw)
		val providers = listOf(
			AiProvider(
				name = "Groq",
				url = "https://api.groq.com/openai/v1/chat/completions",
				apiKey = BuildConfig.AI_GROQ_API_KEY,
				model = BuildConfig.AI_GROQ_MODEL,
			),
			AiProvider(
				name = "OpenRouter Gemini",
				url = "https://openrouter.ai/api/v1/chat/completions",
				apiKey = BuildConfig.AI_OPENROUTER_GEMINI_API_KEY,
				model = BuildConfig.AI_OPENROUTER_GEMINI_MODEL,
				isOpenRouter = true,
			),
			AiProvider(
				name = "OpenRouter Nemotron",
				url = "https://openrouter.ai/api/v1/chat/completions",
				apiKey = BuildConfig.AI_OPENROUTER_NEMOTRON_API_KEY,
				model = BuildConfig.AI_OPENROUTER_NEMOTRON_MODEL,
				isOpenRouter = true,
			),
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

	private fun AiProvider.request(prompt: String): String? {
		val body = buildJsonObject {
			put("model", JsonPrimitive(model))
			put("messages", buildJsonArray {
				add(
					buildJsonObject {
						put("role", JsonPrimitive("system"))
						put("content", JsonPrimitive("You are Tarumi AI, a concise comics librarian. Reply in plain text only."))
					},
				)
				add(
					buildJsonObject {
						put("role", JsonPrimitive("user"))
						put("content", JsonPrimitive(prompt))
					},
				)
			})
			put("temperature", JsonPrimitive(0.55))
			put("max_tokens", JsonPrimitive(320))
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

	private fun buildPrompt(query: String, includeNsfw: Boolean, results: List<Manga>): String {
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
			User request: "$query"
			Use only these candidate comics for recommendations:
			$cards

			Honor every requested format such as manga, manhwa, or manhua. Briefly explain the shared genre,
			trope, or story qualities that make these candidates relevant. Write 2 short sentences and mention
			that the cards below are the best matches. Do not invent titles outside the candidate list.
		""".trimIndent()
	}

	private fun buildConversationPrompt(query: String, includeNsfw: Boolean): String {
		val personality = if (includeNsfw) {
			"You are Tarumi AI in 18+ mode. Be playful and cheeky, but do not write explicit sexual content."
		} else {
			"You are Tarumi AI in safe mode. Be kind, smart, friendly, and concise."
		}
		return """
			$personality
			The user is chatting, not asking for comic search results yet.
			User message: "$query"

			Reply naturally in 1-2 short sentences. If helpful, invite them to ask for a manga, manhwa, or manhua recommendation by mood, trope, genre, or similar title.
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
