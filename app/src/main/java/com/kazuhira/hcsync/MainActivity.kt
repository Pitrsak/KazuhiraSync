package com.kazuhira.hcsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTakePhoto: Button
    private lateinit var btnPickGallery: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var listViewHistory: ListView

    private lateinit var localRepo: LocalMealRepository
    private var tempPhotoUri: Uri? = null

    // Launchers
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Set<String>>

    private val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRepo = LocalMealRepository(this)
        initDefaultPrefs()

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnPickGallery = findViewById(R.id.btnPickGallery)
        btnSettings = findViewById(R.id.btnSettings)
        listViewHistory = findViewById(R.id.listViewHistory)

        // Health Connect Permissions Contract
        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()
        requestPermissionsLauncher = registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(PERMISSIONS)) {
                Toast.makeText(this, "Health Connect permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = "⚠️ Health Connect permissions not granted"
            }
        }

        // Camera Launcher
        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && tempPhotoUri != null) {
                processFoodImage(tempPhotoUri!!)
            } else {
                statusText.text = "Camera photo capture cancelled."
            }
        }

        // Gallery Launcher
        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                processFoodImage(uri)
            }
        }

        btnTakePhoto.setOnClickListener {
            launchCamera()
        }

        btnPickGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        refreshHistoryList()

        // Handle incoming intent if shared from Gallery/Camera app
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun initDefaultPrefs() {
        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        if (!prefs.contains("GEMINI_API_KEY")) {
            prefs.edit()
                .putString("GEMINI_API_KEY", "AIzaSyA8uAMWwiGiTG4JXA0TOWnemYo5iuIIzDw")
                .putString("GEMINI_MODEL", "gemini-2.5-flash")
                .apply()
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null && type.startsWith("image/")) {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
                statusText.text = "Processing shared image..."
                processFoodImage(imageUri)
            }
        }
    }

    private fun launchCamera() {
        try {
            val photoFile = File(cacheDir, "food_photo_${System.currentTimeMillis()}.jpg")
            tempPhotoUri = FileProvider.getUriForFile(
                this,
                "com.kazuhira.hcsync.fileprovider",
                photoFile
            )
            cameraLauncher.launch(tempPhotoUri!!)
        } catch (e: Exception) {
            Toast.makeText(this, "Error launching camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processFoodImage(imageUri: Uri) {
        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val apiKey = prefs.getString("GEMINI_API_KEY", "") ?: ""
        val modelName = prefs.getString("GEMINI_MODEL", "gemini-2.5-flash") ?: "gemini-2.5-flash"

        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please configure your Google AI API key in Settings first", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        statusText.text = "🧠 Analyzing meal photo with Gemini AI ($modelName)..."
        progressBar.visibility = View.VISIBLE
        btnTakePhoto.isEnabled = false
        btnPickGallery.isEnabled = false

        lifecycleScope.launch {
            val visionService = GeminiVisionService(this@MainActivity, apiKey, modelName)
            val result = visionService.analyzeFoodImage(imageUri)

            progressBar.visibility = View.GONE
            btnTakePhoto.isEnabled = true
            btnPickGallery.isEnabled = true

            result.onSuccess { estimation ->
                statusText.text = "✅ Analysis complete! Please verify values below."
                showMealConfirmationDialog(imageUri, estimation)
            }.onFailure { exception ->
                statusText.text = "❌ Error analyzing food: ${exception.localizedMessage}"
                Toast.makeText(this@MainActivity, "Analysis failed: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMealConfirmationDialog(imageUri: Uri, estimation: MealEstimation) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_meal, null)

        val imgPreview = dialogView.findViewById<ImageView>(R.id.imgMealPreview)
        val etMealName = dialogView.findViewById<EditText>(R.id.etMealName)
        val etCalories = dialogView.findViewById<EditText>(R.id.etCalories)
        val etProtein = dialogView.findViewById<EditText>(R.id.etProtein)
        val etCarbs = dialogView.findViewById<EditText>(R.id.etCarbs)
        val etFat = dialogView.findViewById<EditText>(R.id.etFat)
        val tvNotes = dialogView.findViewById<TextView>(R.id.tvNotes)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelMeal)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveMeal)

        imgPreview.setImageURI(imageUri)
        etMealName.setText(estimation.mealName)
        etCalories.setText(estimation.calories.toInt().toString())
        etProtein.setText(estimation.proteinG.toInt().toString())
        etCarbs.setText(estimation.carbG.toInt().toString())
        etFat.setText(estimation.fatG.toInt().toString())
        tvNotes.text = estimation.notes

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val name = etMealName.text.toString().ifBlank { "Meal" }
            val calories = etCalories.text.toString().toDoubleOrNull() ?: 0.0
            val protein = etProtein.text.toString().toDoubleOrNull() ?: 0.0
            val carbs = etCarbs.text.toString().toDoubleOrNull() ?: 0.0
            val fat = etFat.text.toString().toDoubleOrNull() ?: 0.0

            dialog.dismiss()
            logMealToHealthConnect(name, calories, protein, carbs, fat, estimation.notes)
        }

        dialog.show()
    }

    private fun logMealToHealthConnect(
        name: String,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        notes: String
    ) {
        statusText.text = "⏳ Logging meal to Health Connect..."
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            var syncedSuccess = false
            try {
                val hcClient = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = hcClient.permissionController.getGrantedPermissions()

                if (!granted.containsAll(PERMISSIONS)) {
                    requestPermissionsLauncher.launch(PERMISSIONS)
                }

                val now = Instant.now()
                val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(now)

                val nutritionRecord = NutritionRecord(
                    startTime = now.minusSeconds(60),
                    endTime = now,
                    startZoneOffset = zoneOffset,
                    endZoneOffset = zoneOffset,
                    name = name,
                    energy = Energy.kilocalories(calories),
                    protein = Mass.grams(protein),
                    totalCarbohydrate = Mass.grams(carbs),
                    totalFat = Mass.grams(fat)
                )

                hcClient.insertRecords(listOf(nutritionRecord))
                syncedSuccess = true
                statusText.text = "🎉 Logged $name ($calories kcal) to Health Connect & Samsung Health!"
            } catch (e: Exception) {
                statusText.text = "⚠️ Saved locally (Health Connect write failed: ${e.message})"
            } finally {
                progressBar.visibility = View.GONE

                // Save locally
                val mealRecord = LocalMealRecord(
                    id = System.currentTimeMillis().toString(),
                    mealName = name,
                    calories = calories,
                    proteinG = protein,
                    carbG = carbs,
                    fatG = fat,
                    notes = notes,
                    timestampIso = Instant.now().toString(),
                    syncedToHealthConnect = syncedSuccess
                )
                localRepo.saveMeal(mealRecord)
                refreshHistoryList()
            }
        }
    }

    private fun refreshHistoryList() {
        val meals = localRepo.getMeals()
        val adapter = object : ArrayAdapter<LocalMealRecord>(this, R.layout.item_meal, meals) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_meal, parent, false)
                val item = getItem(position) ?: return view

                val tvTitle = view.findViewById<TextView>(R.id.tvMealTitle)
                val tvCalories = view.findViewById<TextView>(R.id.tvMealCalories)
                val tvMacros = view.findViewById<TextView>(R.id.tvMealMacros)
                val tvTime = view.findViewById<TextView>(R.id.tvMealTime)

                tvTitle.text = item.mealName
                tvCalories.text = "${item.calories.toInt()} kcal"
                tvMacros.text = "P: ${item.proteinG.toInt()}g  C: ${item.carbG.toInt()}g  F: ${item.fatG.toInt()}g"

                try {
                    val instant = Instant.parse(item.timestampIso)
                    val formatted = DateTimeFormatter.ofPattern("MMM d, h:mm a")
                        .withZone(ZoneOffset.systemDefault())
                        .format(instant)
                    tvTime.text = formatted
                } catch (e: Exception) {
                    tvTime.text = item.timestampIso
                }

                return view
            }
        }

        listViewHistory.adapter = adapter
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etApiKey)
        val etModelName = dialogView.findViewById<EditText>(R.id.etModelName)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelSettings)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("GEMINI_API_KEY", "AIzaSyA8uAMWwiGiTG4JXA0TOWnemYo5iuIIzDw"))
        etModelName.setText(prefs.getString("GEMINI_MODEL", "gemini-2.5-flash"))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim().ifBlank { "gemini-2.5-flash" }

            prefs.edit()
                .putString("GEMINI_API_KEY", key)
                .putString("GEMINI_MODEL", model)
                .apply()

            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }
}