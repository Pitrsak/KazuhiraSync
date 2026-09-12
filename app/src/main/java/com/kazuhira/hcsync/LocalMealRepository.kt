package com.kazuhira.hcsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

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
        // Ensure most recent meals are always at the top of the list
        return result.distinctBy { it.id }.sortedByDescending { it.timestampIso }
    }

    fun saveMeal(meal: LocalMealRecord) {
        val currentMeals = getMeals().toMutableList()
        currentMeals.add(0, meal)

        val sortedMeals = currentMeals.distinctBy { it.id }.sortedByDescending { it.timestampIso }

        val array = JSONArray()
        for (m in sortedMeals.take(100)) { // Keep last 100 meals
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
