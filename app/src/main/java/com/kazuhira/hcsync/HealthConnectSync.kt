package com.kazuhira.hcsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Writes ration records to Android Health Connect. Samsung Health (and other apps such as
 * Google Fit or MyFitnessPal) picks them up from there once it is allowed to read Nutrition.
 *
 * Every record carries a stable clientRecordId ("kazuhira-<localId>"), so re-inserting a meal
 * updates the existing Health Connect entry instead of duplicating it, and deletes can target it.
 */
class HealthConnectSync(private val context: Context) {

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class)
        )

        /** Samsung Health files entries under breakfast / lunch / dinner / snacks. */
        fun inferMealType(time: LocalTime): Int = when (time.hour) {
            in 4..10 -> MealType.MEAL_TYPE_BREAKFAST
            in 11..15 -> MealType.MEAL_TYPE_LUNCH
            in 17..21 -> MealType.MEAL_TYPE_DINNER
            else -> MealType.MEAL_TYPE_SNACK
        }

        fun mealTypeLabel(type: Int): String = when (type) {
            MealType.MEAL_TYPE_BREAKFAST -> "BREAKFAST"
            MealType.MEAL_TYPE_LUNCH -> "LUNCH"
            MealType.MEAL_TYPE_DINNER -> "DINNER"
            MealType.MEAL_TYPE_SNACK -> "SNACK"
            else -> "RATION"
        }

        /** Meal type to write, falling back to one inferred from the meal time for legacy records. */
        fun resolveMealType(meal: LocalMealRecord): Int =
            if (meal.mealType != MealType.MEAL_TYPE_UNKNOWN) meal.mealType
            else inferMealType(meal.instant.atZone(ZoneId.systemDefault()).toLocalTime())
    }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        isAvailable() && client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** Inserts the meal, or updates the Health Connect entry previously written for it. */
    suspend fun upsert(meal: LocalMealRecord) {
        val now = Instant.now()
        // One-minute interval starting at the meal time, kept out of the future
        var start = meal.instant
        if (start.plusSeconds(60).isAfter(now)) start = now.minusSeconds(60)
        val end = start.plusSeconds(60)
        val zone = ZoneId.systemDefault().rules

        val record = NutritionRecord(
            startTime = start,
            endTime = end,
            startZoneOffset = zone.getOffset(start),
            endZoneOffset = zone.getOffset(end),
            name = sanitizeName(meal.mealName),
            mealType = resolveMealType(meal),
            energy = Energy.kilocalories(meal.calories),
            protein = Mass.grams(meal.proteinG),
            totalCarbohydrate = Mass.grams(meal.carbG),
            totalFat = Mass.grams(meal.fatG),
            metadata = Metadata(
                clientRecordId = meal.clientRecordId,
                // Must increase on every edit for Health Connect to accept the update
                clientRecordVersion = System.currentTimeMillis()
            )
        )
        client.insertRecords(listOf(record))
    }

    suspend fun delete(clientRecordIds: Collection<String>) {
        if (clientRecordIds.isEmpty()) return
        client.deleteRecords(
            NutritionRecord::class,
            recordIdsList = emptyList(),
            clientRecordIdsList = clientRecordIds.toList()
        )
    }

    /** Opens the Health Connect screen where the user manages which apps can read/write data. */
    fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    fun installIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
    ).setPackage("com.android.vending")

    // Meal names are written without '&' (the app has always replaced it with 'and')
    private fun sanitizeName(name: String): String = name.replace("&", "and").trim().ifBlank { "Meal" }
}
