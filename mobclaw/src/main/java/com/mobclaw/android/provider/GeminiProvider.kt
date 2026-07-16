package com.mobclaw.android.provider

import com.mobclaw.android.model.ChatMessage
import com.mobclaw.android.model.ChatResponse
import com.mobclaw.android.model.ToolCall
import com.mobclaw.android.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Gemini LLM provider using the Gemini API.
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash",
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        model: String?,
        temperature: Double,
    ): ChatResponse = withContext(Dispatchers.IO) {
        val effectiveModel = model ?: this@GeminiProvider.model
        val url = "$baseUrl/models/$effectiveModel:generateContent?key=$apiKey"

        val body = buildJsonObject {
            put("contents", buildGeminiContents(messages))

            put("generationConfig", buildJsonObject {
                put("temperature", temperature)
            })

            val systemText = messages
                .filter { it.role == "system" }
                .joinToString("\n") { it.content }
            if (systemText.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", systemText) })
                    })
                })
            }
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from Gemini API")

        if (!response.isSuccessful) {
            throw Exception("Gemini API error ${response.code}: $responseBody")
        }

        parseGeminiResponse(responseBody)
    }

    override fun supportsNativeTools(): Boolean = false

    override fun supportsVision(): Boolean = true

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/models?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()

            if (!response.isSuccessful) return@withContext emptyList()

            val root = Json.parseToJsonElement(responseBody).jsonObject
            val models = root["models"]?.jsonArray ?: return@withContext emptyList()

            models.mapNotNull { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val displayName = obj["displayName"]?.jsonPrimitive?.content ?: name
                val supportedMethods = obj["supportedGenerationMethods"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                } ?: emptyList()

                if ("generateContent" in supportedMethods) {
                    ModelInfo(
                        id = name.substringAfterLast('/'),
                        name = displayName,
                        supportsVision = name.contains("vision") || name.contains("pro"),
                        supportsTools = false,
                        description = "Gemini model",
                    )
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildGeminiContents(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        for (msg in messages) {
            if (msg.role == "system") continue
            val role = when (msg.role) {
                "assistant" -> "model"
                "user", "tool" -> "user"
                else -> "user"
            }
            add(buildJsonObject {
                put("role", role)
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", msg.content) })
                })
            })
        }
    }

    private fun parseGeminiResponse(responseBody: String): ChatResponse {
        val json = Json.parseToJsonElement(responseBody).jsonObject
        val candidates = json["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) {
            return ChatResponse(text = "No response generated")
        }

        val content = candidates[0].jsonObject["content"]?.jsonObject
        val parts = content?.get("parts")?.jsonArray

        val text = parts?.mapNotNull { part ->
            part.jsonObject["text"]?.jsonPrimitive?.content
        }?.joinToString("")

        return ChatResponse(text = text)
    }
}
