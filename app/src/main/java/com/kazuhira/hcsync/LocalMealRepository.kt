package com.kazuhira.hcsync

import android.content.Context
import androidx.health.connect.client.records.MealType
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
    val syncedToHealthConnect: Boolean = true,
    val mealType: Int = MealType.MEAL_TYPE_UNKNOWN,
    // True when the Health Connect copy (if any) is addressable via clientRecordId, so it can be
    // updated or deleted. Records logged before v2.2 were written without one.
    val hcLinked: Boolean = true
) {
    val clientRecordId: String get() = "kazuhira-$id"

    val instant: Instant
        get() = try {
            Instant.parse(timestampIso)
        } catch (e: Exception) {
            Instant.EPOCH
        }
}

class LocalMealRepository(context: Context) {

    companion object {
        private const val MAX_MEALS = 500
    }

    private val prefs = context.getSharedPreferences("KazuhiraLocalMeals", Context.MODE_PRIVATE)

    fun getMeals(): List<LocalMealRecord> {
        val jsonStr = prefs.getString("meals_list", "[]") ?: "[]"
        val result = mutableListOf<LocalMealRecord>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val synced = obj.optBoolean("syncedToHealthConnect", true)
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
                        syncedToHealthConnect = synced,
                        mealType = obj.optInt("mealType", MealType.MEAL_TYPE_UNKNOWN),
                        // Legacy records that never reached Health Connect are safe to link
                        hcLinked = obj.optBoolean("hcLinked", !synced)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Ensure most recent meals are always at the top of the list
        return result.distinctBy { it.id }.sortedByDescending { it.instant }
    }

    fun getMeal(id: String): LocalMealRecord? = getMeals().firstOrNull { it.id == id }

    /** Inserts a new meal or replaces the existing one with the same id. */
    fun saveMeal(meal: LocalMealRecord) {
        val meals = getMeals().filterNot { it.id == meal.id } + meal
        writeMeals(meals)
    }

    fun deleteMeal(id: String) {
        writeMeals(getMeals().filterNot { it.id == id })
    }

    fun markSynced(id: String, synced: Boolean) {
        val meal = getMeal(id) ?: return
        saveMeal(meal.copy(syncedToHealthConnect = synced, hcLinked = meal.hcLinked || synced))
    }

    // Health Connect deletions that could not be performed yet (offline / no permission)
    fun getPendingDeletes(): Set<String> =
        prefs.getStringSet("pending_hc_deletes", emptySet())?.toSet() ?: emptySet()

    fun addPendingDelete(clientRecordId: String) {
        prefs.edit().putStringSet("pending_hc_deletes", getPendingDeletes() + clientRecordId).apply()
    }

    fun removePendingDeletes(clientRecordIds: Collection<String>) {
        prefs.edit().putStringSet("pending_hc_deletes", getPendingDeletes() - clientRecordIds.toSet()).apply()
    }

    private fun writeMeals(meals: List<LocalMealRecord>) {
        val sortedMeals = meals.distinctBy { it.id }.sortedByDescending { it.instant }

        val array = JSONArray()
        for (m in sortedMeals.take(MAX_MEALS)) {
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
                put("mealType", m.mealType)
                put("hcLinked", m.hcLinked)
            }
            array.put(obj)
        }

        prefs.edit().putString("meals_list", array.toString()).apply()
    }
}
