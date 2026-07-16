package com.mobclaw.android.provider

/**
 * LiteLLM gateway provider via OpenAI-compatible /v1/chat/completions.
 */
class LiteLlmProvider(
    apiKey: String,
    model: String = "qwen/qwen3.5-397b-a17b",
    baseUrl: String = "https://aigw.whatsynaptic.com/v1",
) : OpenAiCompatibleProvider(
    apiKey = apiKey,
    model = model,
    baseUrl = baseUrl,
)
