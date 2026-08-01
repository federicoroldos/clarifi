package com.clarifi.data.ai

/**
 * The three providers ClariFi speaks to, detected from the shape of the key -
 * the same rule as `_ai_provider` in app.py, so a key that works on the desktop
 * works here without being told which service it belongs to.
 */
enum class AiProvider(val label: String, val model: String, val visionModel: String) {
    GROQ("Groq", "qwen/qwen3.6-27b", "qwen/qwen3.6-27b"),
    CLAUDE("Claude", "claude-haiku-4-5-20251001", "claude-haiku-4-5-20251001"),
    GEMINI("Google Gemini", "gemini-2.0-flash", "gemini-2.0-flash");

    companion object {
        fun detect(apiKey: String?): AiProvider = when {
            apiKey.orEmpty().startsWith("gsk_") -> GROQ
            apiKey.orEmpty().startsWith("sk-ant-") -> CLAUDE
            else -> GEMINI
        }
    }
}

/** Raised for anything that goes wrong talking to a provider, with a message for the user. */
class AiException(message: String, val code: String = "ai_failed") : Exception(message)
