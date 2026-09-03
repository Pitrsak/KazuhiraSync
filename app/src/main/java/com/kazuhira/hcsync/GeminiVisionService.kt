package com.kazuhira.hcsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

data class MealEstimation(
    val mealName: String,
    val calories: Double,
    val proteinG: Double,
    val carbG: Double,
    val fatG: Double,
    val notes: String
)

/**
 * Multi-provider Vision AI Service supporting both Google Gemini and OpenRouter.
 */
class GeminiVisionService(
    private val context: Context,
    private val apiKey: String,
    private val modelName: String = "gemini-2.5-flash",
    private val provider: String = "gemini" // "gemini" or "openrouter"
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeFoodImage(imageUri: Uri): Result<MealEstimation> = withContext(Dispatchers.IO) {
        try {
            val base64Image = readAndCompressImage(imageUri)
                ?: return@withContext Result.failure(Exception("Failed to load and compress target image."))

            val prompt = """
                You are Kazuhira Miller — logistics and supply officer for personal health operations.
                Analyze this food image in detail. Identify the dish/food items, portion sizes, and calculate precise nutritional information (calories, protein, carbs, fat).
                
                CRITICAL RULES:
                1. Calculate calories (kcal), protein (g), carbs (g), and fat (g) based on standard visible portions.
                2. NEVER use the symbol '&' anywhere in meal names or notes; always write out the word 'and'.
                3. Respond STRICTLY with a valid JSON object only (no markdown formatting, no explanation text outside JSON).
                
                Required JSON format:
                {
                  "meal_name": "Name of the meal",
                  "calories": 500,
                  "protein_g": 30.0,
                  "carbohydrate_g": 50.0,
                  "fat_g": 15.0,
                  "notes": "Brief Kazuhira-style analysis of portion sizes and ingredients estimated"
                }
            """.trimIndent()

            if (provider.equals("openrouter", ignoreCase = true)) {
                analyzeWithOpenRouter(prompt, base64Image)
            } else {
                analyzeWithGemini(prompt, base64Image)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun analyzeWithOpenRouter(prompt: String, base64Image: String): Result<MealEstimation> {
        val targetModel = modelName.ifBlank { "google/gemini-2.5-flash" }

        val jsonBody = JSONObject().apply {
            put("model", targetModel)
            val messages = JSONArray().apply {
                val userMsg = JSONObject().apply {
                    put("role", "user")
                    val contentParts = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    }
                    put("content", contentParts)
                }
                put(userMsg)
            }
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/Pitrsak/KazuhiraSync")
            .addHeader("X-Title", "KazuhiraSync")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return Result.failure(Exception("OpenRouter Error (${response.code}): $responseStr"))
            }

            val responseJson = JSONObject(responseStr)
            val choices = responseJson.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                val errorObj = responseJson.optJSONObject("error")
                val errMsg = errorObj?.optString("message") ?: "No response from OpenRouter"
                return Result.failure(Exception("OpenRouter Error: $errMsg"))
            }

            val messageObj = choices.getJSONObject(0).getJSONObject("message")
            val rawText = messageObj.getString("content")
            return parseEstimationJson(rawText)
        }
    }

    private fun analyzeWithGemini(prompt: String, base64Image: String): Result<MealEstimation> {
        val jsonBody = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    }
                    put("parts", partsArray)
                }
                put(contentObj)
            }
            put("contents", contentsArray)

            val generationConfig = JSONObject().apply {
                put("temperature", 0.2)
                put("response_mime_type", "application/json")
            }
            put("generationConfig", generationConfig)
        }

        val modelsToTry = listOf(
            modelName.ifBlank { "gemini-2.5-flash" },
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        ).distinct()

        var lastException: Exception? = null

        for (model in modelsToTry) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        lastException = Exception("Gemini API Error ($model - ${response.code}): $responseStr")
                        return@use
                    }

                    val responseJson = JSONObject(responseStr)
                    val candidates = responseJson.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        lastException = Exception("No candidates returned from Gemini API")
                        return@use
                    }

                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    val rawText = parts.getJSONObject(0).getString("text")
                    return parseEstimationJson(rawText)
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        return Result.failure(lastException ?: Exception("Failed to analyze image with Gemini API"))
    }

    private fun parseEstimationJson(rawText: String): Result<MealEstimation> {
        try {
            var text = rawText.trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) {
                text = text.substring(start, end + 1)
            }

            val parsedJson = JSONObject(text)
            val estimation = MealEstimation(
                mealName = parsedJson.optString("meal_name", "Logged Meal"),
                calories = parsedJson.optDouble("calories", 0.0),
                proteinG = parsedJson.optDouble("protein_g", 0.0),
                carbG = parsedJson.optDouble("carbohydrate_g", 0.0),
                fatG = parsedJson.optDouble("fat_g", 0.0),
                notes = parsedJson.optString("notes", "")
            )
            return Result.success(estimation)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse meal nutrition data: ${e.message}"))
        }
    }

    private fun readAndCompressImage(uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return null

            // Scale down to max 1024x1024 to save bandwidth and speed up processing
            val maxDimension = 1024
            val width = originalBitmap.width
            val height = originalBitmap.height
            val scale = if (width > maxDimension || height > maxDimension) {
                val max = width.coerceAtLeast(height)
                maxDimension.toFloat() / max.toFloat()
            } else 1.0f

            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(
                    originalBitmap,
                    (width * scale).toInt(),
                    (height * scale).toInt(),
                    true
                )
            } else originalBitmap

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val bytes = outputStream.toByteArray()

            Base64.getEncoder().encodeToString(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
