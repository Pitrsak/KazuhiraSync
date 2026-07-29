package com.kazuhira.hcsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

data class LocalMealRecord(
    val id: String,
    val mealName: String,
    val calories: Double,
    val proteinG: Double,
    val carbG: Double,
    val fatG: Double,
    val notes: String,
    val timestampIso: String,
    val syncedToHealthConnect: Boolean = true
)

class LocalMealRepository(context: Context) {

    private val prefs = context.getSharedPreferences("KazuhiraLocalMeals", Context.MODE_PRIVATE)

    fun getMeals(): List<LocalMealRecord> {
        val jsonStr = prefs.getString("meals_list", "[]") ?: "[]"
        val result = mutableListOf<LocalMealRecord>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    LocalMealRecord(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        mealName = obj.optString("mealName", "Meal"),
                        calories = obj.optDouble("calories", 0.0),
                        proteinG = obj.optDouble("proteinG", 0.0),
                        carbG = obj.optDouble("carbG", 0.0),
                        fatG = obj.optDouble("fatG", 0.0),
                        notes = obj.optString("notes", ""),
                        timestampIso = obj.optString("timestampIso", Instant.now().toString()),
                        syncedToHealthConnect = obj.optBoolean("syncedToHealthConnect", true)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Return latest first
        return result.reversed()
    }

    fun saveMeal(meal: LocalMealRecord) {
        val currentMeals = getMeals().toMutableList()
        currentMeals.add(0, meal) // Add to top

        val array = JSONArray()
        for (m in currentMeals.take(100)) { // Keep last 100 meals
            val obj = JSONObject().apply {
                put("id", m.id)
                put("mealName", m.mealName)
                put("calories", m.calories)
                put("proteinG", m.proteinG)
                put("carbG", m.carbG)
                put("fatG", m.fatG)
                put("notes", m.notes)
                put("timestampIso", m.timestampIso)
                put("syncedToHealthConnect", m.syncedToHealthConnect)
            }
            array.put(obj)
        }

        prefs.edit().putString("meals_list", array.toString()).apply()
    }
}
