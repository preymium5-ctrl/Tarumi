package org.koitharu.kotatsu.ai.ui

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.home.ui.detectComicType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class LocalAiLibrarianEngine @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	private val modelDir = File(context.filesDir, MODEL_DIR_NAME)
	private val modelFile = File(modelDir, BuildConfig.AI_LOCAL_MODEL_FILE)
	private var inference: LlmInference? = null

	private val _status = MutableStateFlow(
		when {
			BuildConfig.AI_LOCAL_MODEL_URL.isBlank() -> LocalAiModelStatus.NotConfigured
			modelFile.exists() && modelFile.length() > 0L -> LocalAiModelStatus.Ready
			else -> LocalAiModelStatus.NotDownloaded
		},
	)
	val status: StateFlow<LocalAiModelStatus> = _status.asStateFlow()

	val expectedModelSizeBytes: Long = BuildConfig.AI_LOCAL_MODEL_SIZE_BYTES

	suspend fun downloadModel() = withContext(Dispatchers.IO) {
		if (BuildConfig.AI_LOCAL_MODEL_URL.isBlank()) {
			_status.value = LocalAiModelStatus.NotConfigured
			return@withContext
		}
		modelDir.mkdirs()
		val tempFile = File(modelDir, "${modelFile.name}.download")
		_status.value = LocalAiModelStatus.Downloading(0)
		runCatchingCancellable {
			val request = Request.Builder()
				.url(BuildConfig.AI_LOCAL_MODEL_URL)
				.build()
			okHttpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					error("Model download failed: HTTP ${response.code}")
				}
				val body = response.body
				val length = body.contentLength().coerceAtLeast(0L)
				body.byteStream().use { input ->
					tempFile.outputStream().use { output ->
						val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
						var copied = 0L
						var read = input.read(buffer)
						while (read >= 0) {
							output.write(buffer, 0, read)
							copied += read
							if (length > 0L) {
								val progress = ((copied.toDouble() / length.toDouble()) * 100).roundToInt()
									.coerceIn(0, 100)
								_status.update { LocalAiModelStatus.Downloading(progress) }
							}
							read = input.read(buffer)
						}
					}
				}
			}
			if (tempFile.length() == 0L) {
				error("Downloaded model is empty")
			}
			if (modelFile.exists()) {
				modelFile.delete()
			}
			if (!tempFile.renameTo(modelFile)) {
				tempFile.copyTo(modelFile, overwrite = true)
				tempFile.delete()
			}
			releaseInference()
			_status.value = LocalAiModelStatus.Ready
		}.onFailure { error ->
			error.printStackTraceDebug()
			tempFile.delete()
			_status.value = LocalAiModelStatus.Error(error.message ?: "Model download failed")
		}
	}

	suspend fun generateRecommendationReply(
		query: String,
		includeNsfw: Boolean,
		results: List<Manga>,
		libraryContext: String = "",
		conversationContext: String = "",
	): String? = generate(buildRecommendationPrompt(query, includeNsfw, results, libraryContext, conversationContext))

	suspend fun generateConversationReply(
		query: String,
		includeNsfw: Boolean,
		libraryContext: String = "",
		conversationContext: String = "",
	): String? = generate(buildConversationPrompt(query, includeNsfw, libraryContext, conversationContext))

	private suspend fun generate(prompt: String): String? = withContext(Dispatchers.IO) {
		if (!modelFile.exists() || modelFile.length() == 0L) {
			return@withContext null
		}
		runCatchingCancellable {
			val engine = inference ?: createInference().also { inference = it }
			engine.generateResponse(prompt)
				?.trim()
				?.takeIf { it.isNotEmpty() }
		}.onFailure { error ->
			error.printStackTraceDebug()
			releaseInference()
			_status.value = LocalAiModelStatus.Error(error.message ?: "Local model failed")
		}.getOrNull()
	}

	private fun createInference(): LlmInference {
		val options = LlmInference.LlmInferenceOptions.builder()
			.setModelPath(modelFile.absolutePath)
			.setMaxTokens(MAX_TOKENS)
			.setMaxTopK(MAX_TOP_K)
			.build()
		return LlmInference.createFromOptions(context, options)
	}

	private fun releaseInference() {
		runCatching {
			inference?.close()
		}
		inference = null
	}

	private fun buildRecommendationPrompt(
		query: String,
		includeNsfw: Boolean,
		results: List<Manga>,
		libraryContext: String,
		conversationContext: String,
	): String {
		val personality = if (includeNsfw) {
			"You are Tarumi AI in 18+ librarian mode. Be playful, but do not write explicit sexual content."
		} else {
			"You are Tarumi AI in safe librarian mode. Be kind, smart, and concise."
		}
		val cards = results.take(10).joinToString(separator = "\n") { manga ->
			val tags = manga.tags.take(6).joinToString { it.title }
			val synopsis = manga.description
				.orEmpty()
				.replace(Regex("\\s+"), " ")
				.take(220)
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
			Use only these source-backed candidate comics:
			$cards

			Current mode: ${if (includeNsfw) "18+ only. Recommend adult/NSFW candidates only." else "Safe only. Recommend non-adult candidates only."}
			Use the recent conversation to understand follow-ups. Explain the shared story mood, genre, trope,
			or character setup. Do not invent titles outside the candidate list. Tell the user the cards below
			are the best source-backed matches.
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
			Reply naturally and answer directly. You can chat about the app, comics, characters, recommendation
			ideas, or general topics. If useful, invite them to ask for manga, manhwa, or manhua recommendations
			by mood, trope, genre, or similar title.
		""".trimIndent()
	}

	companion object {
		private const val MODEL_DIR_NAME = "ai_models"
		private const val MAX_TOKENS = 768
		private const val MAX_TOP_K = 40
	}
}

sealed interface LocalAiModelStatus {
	data object NotConfigured : LocalAiModelStatus
	data object NotDownloaded : LocalAiModelStatus
	data class Downloading(val progress: Int) : LocalAiModelStatus
	data object Ready : LocalAiModelStatus
	data class Error(val message: String) : LocalAiModelStatus
}
