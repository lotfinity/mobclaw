package com.mobclaw.android.provider

import com.mobclaw.android.model.ChatMessage
import com.mobclaw.android.model.ChatResponse
import com.mobclaw.android.model.ToolSpec

/**
 * Description of a model available from a provider.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val contextWindow: Int? = null,
    val description: String = "",
)

/**
 * LLM provider interface.
 */
interface LlmProvider {

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>? = null,
        model: String? = null,
        temperature: Double = 0.7,
    ): ChatResponse

    fun supportsNativeTools(): Boolean = false

    suspend fun listModels(): List<ModelInfo> = emptyList()

    fun supportsVision(): Boolean = false
}
