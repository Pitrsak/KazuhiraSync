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
    private lateinit var tvModelSubtitle: TextView
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
        tvModelSubtitle = findViewById(R.id.tvModelSubtitle)
        progressBar = findViewById(R.id.progressBar)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnPickGallery = findViewById(R.id.btnPickGallery)
        btnSettings = findViewById(R.id.btnSettings)
        listViewHistory = findViewById(R.id.listViewHistory)

        updateModelSubtitle()

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
                .putString("GEMINI_MODEL", "gemini-3.5-flash-lite")
                .apply()
        } else if (prefs.getString("GEMINI_MODEL", "") == "gemini-2.5-flash") {
            // Update default to gemini-3.5-flash-lite
            prefs.edit().putString("GEMINI_MODEL", "gemini-3.5-flash-lite").apply()
        }
    }

    private fun updateModelSubtitle() {
        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val currentModel = prefs.getString("GEMINI_MODEL", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
        tvModelSubtitle.text = "AI Model: $currentModel"
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
        val modelName = prefs.getString("GEMINI_MODEL", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"

        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please configure your Google AI API key in Settings first", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        statusText.text = "🧠 Kazuhira is analyzing meal photo ($modelName)..."
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
        // Ensure no raw & symbols in meal name or notes
        val cleanName = estimation.mealName.replace("&", "and")
        val cleanNotes = estimation.notes.replace("&", "and")

        etMealName.setText(cleanName)
        etCalories.setText(estimation.calories.toInt().toString())
        etProtein.setText(estimation.proteinG.toInt().toString())
        etCarbs.setText(estimation.carbG.toInt().toString())
        etFat.setText(estimation.fatG.toInt().toString())
        tvNotes.text = cleanNotes

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val name = etMealName.text.toString().ifBlank { "Meal" }.replace("&", "and")
            val calories = etCalories.text.toString().toDoubleOrNull() ?: 0.0
            val protein = etProtein.text.toString().toDoubleOrNull() ?: 0.0
            val carbs = etCarbs.text.toString().toDoubleOrNull() ?: 0.0
            val fat = etFat.text.toString().toDoubleOrNull() ?: 0.0

            dialog.dismiss()
            logMealToHealthConnect(name, calories, protein, carbs, fat, cleanNotes)
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
                val tvProteinBadge = view.findViewById<TextView>(R.id.tvProteinBadge)
                val tvCarbBadge = view.findViewById<TextView>(R.id.tvCarbBadge)
                val tvFatBadge = view.findViewById<TextView>(R.id.tvFatBadge)
                val tvTime = view.findViewById<TextView>(R.id.tvMealTime)

                tvTitle.text = item.mealName
                tvCalories.text = "${item.calories.toInt()} kcal"
                tvProteinBadge.text = "P: ${item.proteinG.toInt()}g"
                tvCarbBadge.text = "C: ${item.carbG.toInt()}g"
                tvFatBadge.text = "F: ${item.fatG.toInt()}g"

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
        val spinnerModelPreset = dialogView.findViewById<Spinner>(R.id.spinnerModelPreset)
        val etModelName = dialogView.findViewById<EditText>(R.id.etModelName)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelSettings)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val currentApiKey = prefs.getString("GEMINI_API_KEY", "AIzaSyA8uAMWwiGiTG4JXA0TOWnemYo5iuIIzDw")
        val currentModel = prefs.getString("GEMINI_MODEL", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"

        etApiKey.setText(currentApiKey)
        etModelName.setText(currentModel)

        val modelPresets = listOf(
            "gemini-3.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "Custom..."
        )

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelPresets)
        spinnerModelPreset.adapter = spinnerAdapter

        val presetIndex = modelPresets.indexOf(currentModel)
        if (presetIndex >= 0) {
            spinnerModelPreset.setSelection(presetIndex)
        } else {
            spinnerModelPreset.setSelection(4) // Custom
        }

        spinnerModelPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = modelPresets[position]
                if (selected != "Custom...") {
                    etModelName.setText(selected)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim().ifBlank { "gemini-3.5-flash-lite" }

            prefs.edit()
                .putString("GEMINI_API_KEY", key)
                .putString("GEMINI_MODEL", model)
                .apply()

            updateModelSubtitle()
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }
}