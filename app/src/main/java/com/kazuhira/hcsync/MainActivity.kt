package com.kazuhira.hcsync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

class MainActivity : AppCompatActivity() {
    
    private val client = OkHttpClient()
    
    // CONFIGURATION
    private lateinit var HCGATEWAY_URL: String
    private lateinit var USERNAME: String
    private lateinit var PASSWORD: String
    
    private lateinit var statusText: TextView
    private lateinit var syncButton: Button
    
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class)
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val prefs = getSharedPreferences("KazuhiraSyncPrefs", MODE_PRIVATE)
        HCGATEWAY_URL = prefs.getString("HCGATEWAY_URL", "http://100.67.83.57:8765") ?: "http://100.67.83.57:8765"
        USERNAME = prefs.getString("USERNAME", "kazuhira") ?: "kazuhira"
        PASSWORD = prefs.getString("PASSWORD", "miller2026") ?: "miller2026"
        
        // Persist default values securely if they aren't written yet
        if (!prefs.contains("USERNAME")) {
            prefs.edit()
                .putString("HCGATEWAY_URL", HCGATEWAY_URL)
                .putString("USERNAME", USERNAME)
                .putString("PASSWORD", PASSWORD)
                .apply()
        }
        
        statusText = findViewById(R.id.statusText)
        syncButton = findViewById(R.id.syncButton)
        
        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()
        requestPermissions = registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(PERMISSIONS)) {
                performSync()
            } else {
                statusText.text = "❌ Health Connect Permission Denied"
            }
        }
        
        syncButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val hcClient = HealthConnectClient.getOrCreate(this@MainActivity)
                    val granted = hcClient.permissionController.getGrantedPermissions()
                    if (granted.containsAll(PERMISSIONS)) {
                        performSync()
                    } else {
                        statusText.text = "⏳ Requesting Permissions..."
                        requestPermissions.launch(PERMISSIONS)
                    }
                } catch(e: Exception) {
                    statusText.text = "❌ Error accessing Health Connect: ${e.message}"
                }
            }
        }
    }
    
    private fun performSync() {
        statusText.text = "⏳ Syncing..."
        syncButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                statusText.text = "⏳ Checking connection..."
                if (!checkServerHealth()) {
                    statusText.text = "❌ Gateway offline (Check Tailscale)"
                    syncButton.isEnabled = true
                    return@launch
                }
                
                statusText.text = "⏳ Authenticating..."
                val token = login()
                if (token == null) {
                    statusText.text = "❌ Login failed - check Tailscale"
                    syncButton.isEnabled = true
                    return@launch
                }
                
                val fetched = fetchNutrition(token)
                if (fetched.isEmpty()) {
                    statusText.text = "ℹ️ No meals to sync"
                    syncButton.isEnabled = true
                    return@launch
                }
                
                val recordsToInsert = fetched.map { it.second }
                val idsToMark = fetched.map { it.first }.filter { it.isNotEmpty() }
                
                val successCount = writeToHealthConnect(recordsToInsert)
                
                if (successCount > 0 && idsToMark.isNotEmpty()) {
                    markRecordsAsSynced(token, idsToMark)
                }
                
                statusText.text = "✅ Synced $successCount/${fetched.size} meals to Samsung Health"
                
            } catch (e: Exception) {
                statusText.text = "❌ Error: ${e.message}"
            } finally {
                syncButton.isEnabled = true
            }
        }
    }
    
    private suspend fun login(): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("username", USERNAME)
                put("password", PASSWORD)
            }
            
            val request = Request.Builder()
                .url("$HCGATEWAY_URL/api/v2/login")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                val data = JSONObject(body)
                
                if (data.optBoolean("success", false)) {
                    data.getString("token")
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun fetchNutrition(token: String): List<Pair<String, NutritionRecord>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$HCGATEWAY_URL/api/v2/read/nutrition")
                .header("Authorization", "Bearer $token")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val records: org.json.JSONArray = try {
                    val data = JSONObject(body)
                    if (data.has("success") && !data.optBoolean("success", false)) {
                        return@withContext emptyList()
                    }
                    data.optJSONArray("records") ?: org.json.JSONArray()
                } catch (e: org.json.JSONException) {
                    try {
                        org.json.JSONArray(body)
                    } catch (ex: Exception) {
                        return@withContext emptyList()
                    }
                }
                
                val result = mutableListOf<Pair<String, NutritionRecord>>()
                
                for (i in 0 until records.length()) {
                    try {
                        val record = records.getJSONObject(i)
                        val id = record.optString("id", "")
                        val nutritionData = record.getJSONObject("data")
                        
                        val startTime = Instant.parse(nutritionData.getString("timestamp_iso"))
                        val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(Instant.now())
                        
                        val nutritionRecord = NutritionRecord(
                            startTime = startTime,
                            endTime = startTime.plusSeconds(1),
                            startZoneOffset = zoneOffset,
                            endZoneOffset = zoneOffset,
                            name = nutritionData.optString("meal_name", "Meal"),
                            energy = Energy.kilocalories(nutritionData.optDouble("calories", 0.0)),
                            protein = Mass.grams(nutritionData.optDouble("protein_g", 0.0)),
                            totalCarbohydrate = Mass.grams(nutritionData.optDouble("carbohydrate_g", 0.0)),
                            totalFat = Mass.grams(nutritionData.optDouble("fat_g", 0.0))
                        )
                        
                        result.add(Pair(id, nutritionRecord))
                    } catch (e: Exception) {
                        // Skip invalid records
                    }
                }
                
                result
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private suspend fun writeToHealthConnect(records: List<NutritionRecord>): Int = withContext(Dispatchers.IO) {
        try {
            val healthConnectClient = HealthConnectClient.getOrCreate(this@MainActivity)
            healthConnectClient.insertRecords(records)
            records.size
        } catch (e: Exception) {
            0
        }
    }
    
    private suspend fun checkServerHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$HCGATEWAY_URL/health")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun markRecordsAsSynced(token: String, ids: List<String>) = withContext(Dispatchers.IO) {
        try {
            val jsonArray = org.json.JSONArray(ids)
            val json = JSONObject().apply {
                put("ids", jsonArray)
            }
            val request = Request.Builder()
                .url("$HCGATEWAY_URL/api/v2/mark_synced/nutrition")
                .header("Authorization", "Bearer $token")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                // Assuming successful or ignoring failures silently as it will be retried on next sync
            }
        } catch (e: Exception) {
            // Ignore for now
        }
    }
}