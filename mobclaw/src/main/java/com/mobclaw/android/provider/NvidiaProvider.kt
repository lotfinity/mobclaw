package com.mobclaw.android.provider

/**
 * NVIDIA NIM provider for omni/vision models.
 * Uses OpenAI-compatible endpoint with NVIDIA-specific headers.
 */
class NvidiaProvider(
    apiKey: String,
    model: String = "nvidia/nemotron-3-ultra-550b-a55b",
    baseUrl: String = "https://integrate.api.nvidia.com/v1",
) : OpenAiCompatibleProvider(
    apiKey = apiKey,
    model = model,
    baseUrl = baseUrl,
    extraHeaders = mapOf(
        "X-NVIDIA-Source" to "mobclaw",
    ),
) {
    override fun supportsVision(): Boolean = true
}
