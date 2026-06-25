package com.example

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiAIEngine {
    private const val TAG = "GeminiAIEngine"
    private const val MODEL_NAME = "gemini-3.5-flash"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun getDeescalationSuggestions(message: String): List<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API Key is empty or placeholder!")
            return@withContext listOf(
                "Please configure your Gemini API Key in the Secrets panel in AI Studio.",
                "Let's take a deep breath before responding.",
                "I understand how you feel, let's talk calmly."
            )
        }

        val systemInstruction = "You are an expert communication coach and de-escalator. The user is in a tense chat. Analyze the received message and provide exactly 3 short, calming, and polite reply suggestions to resolve the conflict without arguing. Format the output as a simple JSON array of strings."

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"

        val jsonRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Received message: \"$message\"")
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.7)
            })
        }

        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "API call failed with code ${response.code}: $errBody")
                    return@withContext listOf(
                        "I hear you. Let's discuss this when we're both calm.",
                        "I value our connection. Let's take a brief break.",
                        "Let's work through this together step-by-step."
                    )
                }

                val responseBodyStr = response.body?.string()
                if (responseBodyStr.isNullOrBlank()) {
                    return@withContext getFallbackSuggestions()
                }

                Log.d(TAG, "Raw response: $responseBodyStr")
                val responseJson = JSONObject(responseBodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext getFallbackSuggestions()
                }

                val content = candidates.getJSONObject(0).optJSONObject("content") ?: return@withContext getFallbackSuggestions()
                val parts = content.optJSONArray("parts") ?: return@withContext getFallbackSuggestions()
                if (parts.length() == 0) {
                    return@withContext getFallbackSuggestions()
                }

                val text = parts.getJSONObject(0).optString("text") ?: ""
                Log.d(TAG, "Extracted raw text from LLM: $text")

                val suggestions = mutableListOf<String>()
                try {
                    val array = JSONArray(text.trim())
                    for (i in 0 until array.length()) {
                        suggestions.add(array.getString(i))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse text as JSON array: $text", e)
                    val lines = text.split("\n")
                        .map { it.replace(Regex("^[-*\\d.]+\\s*"), "").trim() }
                        .filter { it.isNotEmpty() }
                    if (lines.size >= 3) {
                        return@withContext lines.take(3)
                    } else {
                        return@withContext getFallbackSuggestions()
                    }
                }

                return@withContext if (suggestions.size >= 3) suggestions.take(3) else suggestions + getFallbackSuggestions().take(3 - suggestions.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during API call", e)
            return@withContext getFallbackSuggestions()
        }
    }

    private fun getFallbackSuggestions(): List<String> {
        return listOf(
            "I hear what you're saying, and I want to understand your perspective better.",
            "Let's take a small breather and talk about this calmly.",
            "I'm sorry this got tense. Let's work together to resolve it."
        )
    }
}
