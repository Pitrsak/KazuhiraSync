package com.kazuhira.hcsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
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

    private lateinit var cameraPreviewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var tvModelSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTakePhoto: Button
    private lateinit var btnPickGallery: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var listViewHistory: ListView
    private lateinit var tvTodayCalories: TextView
    private lateinit var tvTodayCount: TextView
    private lateinit var tvTodayProtein: TextView
    private lateinit var tvTodayCarbs: TextView
    private lateinit var tvTodayFat: TextView
    private lateinit var parallaxManager: IdroidParallaxManager

    private lateinit var localRepo: LocalMealRepository
    private var tempPhotoUri: Uri? = null
    private var imageCapture: ImageCapture? = null

    // Launchers
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var requestCameraPermissionLauncher: ActivityResultLauncher<String>

    private val HEALTH_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRepo = LocalMealRepository(this)
        initDefaultPrefs()

        cameraPreviewView = findViewById(R.id.cameraPreviewView)
        statusText = findViewById(R.id.statusText)
        tvModelSubtitle = findViewById(R.id.tvModelSubtitle)
        progressBar = findViewById(R.id.progressBar)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnPickGallery = findViewById(R.id.btnPickGallery)
        btnSettings = findViewById(R.id.btnSettings)
        listViewHistory = findViewById(R.id.listViewHistory)
        tvTodayCalories = findViewById(R.id.tvTodayCalories)
        tvTodayCount = findViewById(R.id.tvTodayCount)
        tvTodayProtein = findViewById(R.id.tvTodayProtein)
        tvTodayCarbs = findViewById(R.id.tvTodayCarbs)
        tvTodayFat = findViewById(R.id.tvTodayFat)

        parallaxManager = IdroidParallaxManager(this)
        findViewById<View>(R.id.cardTodaySummary)?.let {
            parallaxManager.registerView(it, translationDp = 12f, rotationDeg = 3.5f)
        }
        findViewById<View>(R.id.cardAcquireTarget)?.let {
            parallaxManager.registerView(it, translationDp = 16f, rotationDeg = 4.5f)
        }
        findViewById<View>(R.id.listViewHistory)?.let {
            parallaxManager.registerView(it, translationDp = 8f, rotationDeg = 2.0f)
        }

        updateModelSubtitle()

        // Health Connect Permissions Contract
        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()
        requestPermissionsLauncher = registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(HEALTH_PERMISSIONS)) {
                Toast.makeText(this, "Health Connect permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = "⚠️ Health Connect permissions not granted"
            }
        }

        // Camera Permission Launcher for always-on optical feed
        requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                statusText.text = "Optical feed standby (camera permission denied). Tap SCAN to retry."
            }
        }

        // Fallback System Camera Launcher
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

        // Check & request camera permission on startup to start always-on background feed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else if (imageCapture != null) {
                captureLiveTargetPhoto()
            } else {
                launchCamera()
            }
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

    override fun onResume() {
        super.onResume()
        parallaxManager.start()
    }

    override fun onPause() {
        super.onPause()
        parallaxManager.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun startCamera() {
        lifecycleScope.launch {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(this@MainActivity).await()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(cameraPreviewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@MainActivity,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                statusText.text = "Optical feed online. Ready for target acquisition."
            } catch (e: Exception) {
                statusText.text = "Optical sensor error: ${e.message}"
            }
        }
    }

    private fun captureLiveTargetPhoto() {
        val capture = imageCapture ?: run {
            launchCamera()
            return
        }

        val photoFile = File(cacheDir, "food_photo_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        statusText.text = "⚡ Acquiring target scan..."
        progressBar.visibility = View.VISIBLE
        btnTakePhoto.isEnabled = false

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    btnTakePhoto.isEnabled = true
                    val savedUri = Uri.fromFile(photoFile)
                    processFoodImage(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    btnTakePhoto.isEnabled = true
                    progressBar.visibility = View.GONE
                    statusText.text = "Capture error: ${exception.message}. Fallback to camera..."
                    launchCamera()
                }
            }
        )
    }


    private fun initDefaultPrefs() {
        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val editor = prefs.edit()

        // Clean up any legacy hardcoded key
        val legacyKey = prefs.getString("GEMINI_API_KEY", null) ?: prefs.getString("API_KEY", null)
        if (legacyKey == "AIzaSyA8uAMWwiGiTG4JXA0TOWnemYo5iuIIzDw") {
            editor.putString("API_KEY", "").putString("GEMINI_API_KEY", "")
        }

        if (!prefs.contains("AI_PROVIDER")) {
            editor.putString("AI_PROVIDER", "gemini")
        }
        if (!prefs.contains("API_KEY") && !prefs.contains("GEMINI_API_KEY")) {
            editor.putString("API_KEY", "")
        }
        if (!prefs.contains("MODEL_NAME")) {
            val legacyModel = prefs.getString("GEMINI_MODEL", "gemini-3.8-flash") ?: "gemini-3.8-flash"
            editor.putString("MODEL_NAME", legacyModel)
        }
        editor.apply()
    }

    private fun updateModelSubtitle() {
        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val provider = prefs.getString("AI_PROVIDER", "gemini") ?: "gemini"
        val currentModel = prefs.getString("MODEL_NAME", prefs.getString("GEMINI_MODEL", "gemini-3.8-flash")) ?: "gemini-3.8-flash"
        val provTag = if (provider.equals("openrouter", ignoreCase = true)) "OPENROUTER" else "GEMINI"
        tvModelSubtitle.text = "KAZUHIRA SYNC // $provTag : ${currentModel.uppercase()}"
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null && type.startsWith("image/")) {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
                statusText.text = "Processing shared intel image..."
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
        val provider = prefs.getString("AI_PROVIDER", "gemini") ?: "gemini"
        val apiKey = prefs.getString("API_KEY", prefs.getString("GEMINI_API_KEY", "")) ?: ""
        val modelName = prefs.getString("MODEL_NAME", prefs.getString("GEMINI_MODEL", "gemini-3.8-flash")) ?: "gemini-3.8-flash"

        if (apiKey.isBlank()) {
            val providerName = if (provider.equals("openrouter", ignoreCase = true)) "OpenRouter" else "Google AI (Gemini)"
            Toast.makeText(this, "Please configure your $providerName API key in Settings (⚙️) first", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        statusText.text = "🧠 Kazuhira is analyzing target intel ($modelName)..."
        progressBar.visibility = View.VISIBLE
        btnTakePhoto.isEnabled = false
        btnPickGallery.isEnabled = false

        lifecycleScope.launch {
            val visionService = GeminiVisionService(this@MainActivity, apiKey, modelName, provider)
            val result = visionService.analyzeFoodImage(imageUri)

            progressBar.visibility = View.GONE
            btnTakePhoto.isEnabled = true
            btnPickGallery.isEnabled = true

            result.onSuccess { estimation ->
                statusText.text = "✅ Target analysis complete! Confirm data below."
                showMealConfirmationDialog(imageUri, estimation)
            }.onFailure { exception ->
                val errorMsg = when {
                    exception is java.net.UnknownHostException ||
                    exception.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                    exception.message?.contains("Comms offline", ignoreCase = true) == true ->
                        "Tactical link offline: Check Wi-Fi or mobile data connection."
                    exception.message?.contains("API key not valid", ignoreCase = true) == true ||
                    exception.message?.contains("403") == true ->
                        "Invalid API Key: Check Settings (⚙️)."
                    else ->
                        "Intel extraction error: ${exception.localizedMessage ?: "Unknown error"}"
                }
                statusText.text = "❌ $errorMsg"
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
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
        statusText.text = "⏳ Logging ration intel to Health Connect..."
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            var syncedSuccess = false
            try {
                val hcClient = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = hcClient.permissionController.getGrantedPermissions()

                if (!granted.containsAll(HEALTH_PERMISSIONS)) {
                    requestPermissionsLauncher.launch(HEALTH_PERMISSIONS)
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
                statusText.text = "⚠️ Saved locally (Health Connect write: ${e.message})"
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
        updateTodaySummary(meals)
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

    private fun updateTodaySummary(meals: List<LocalMealRecord>) {
        val today = java.time.LocalDate.now()
        val zone = ZoneOffset.systemDefault()
        val todayMeals = meals.filter {
            try {
                Instant.parse(it.timestampIso).atZone(zone).toLocalDate() == today
            } catch (e: Exception) {
                false
            }
        }
        val sumCal = todayMeals.sumOf { it.calories }
        val sumP = todayMeals.sumOf { it.proteinG }
        val sumC = todayMeals.sumOf { it.carbG }
        val sumF = todayMeals.sumOf { it.fatG }
        tvTodayCalories.text = sumCal.toInt().toString()
        tvTodayCount.text = "${todayMeals.size} RATION${if (todayMeals.size == 1) "" else "S"}"
        tvTodayProtein.text = "${sumP.toInt()}g"
        tvTodayCarbs.text = "${sumC.toInt()}g"
        tvTodayFat.text = "${sumF.toInt()}g"
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val spinnerProvider = dialogView.findViewById<Spinner>(R.id.spinnerProvider)
        val tvApiKeyLabel = dialogView.findViewById<TextView>(R.id.tvApiKeyLabel)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etApiKey)
        val spinnerModelPreset = dialogView.findViewById<Spinner>(R.id.spinnerModelPreset)
        val etModelName = dialogView.findViewById<EditText>(R.id.etModelName)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelSettings)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val currentProvider = prefs.getString("AI_PROVIDER", "gemini") ?: "gemini"
        val currentApiKey = prefs.getString("API_KEY", prefs.getString("GEMINI_API_KEY", "")) ?: ""
        val currentModel = prefs.getString("MODEL_NAME", prefs.getString("GEMINI_MODEL", "gemini-3.8-flash")) ?: "gemini-3.8-flash"

        etApiKey.setText(currentApiKey)
        etModelName.setText(currentModel)

        val providerOptions = listOf("Google Gemini (Direct)", "OpenRouter")
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providerOptions)
        spinnerProvider.adapter = providerAdapter

        if (currentProvider.equals("openrouter", ignoreCase = true)) {
            spinnerProvider.setSelection(1)
        } else {
            spinnerProvider.setSelection(0)
        }

        val geminiPresets = listOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "Custom..."
        )
        val openRouterPresets = listOf(
            "google/gemini-3.8-flash",
            "google/gemini-3.5-flash-lite",
            "google/gemini-flash-latest",
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-haiku",
            "meta-llama/llama-3.2-11b-vision-instruct",
            "Custom..."
        )

        fun updateModelPresets(isGemini: Boolean) {
            val presets = if (isGemini) geminiPresets else openRouterPresets
            tvApiKeyLabel.text = if (isGemini) "GOOGLE AI API KEY" else "OPENROUTER API KEY"
            etApiKey.hint = if (isGemini) "Paste Gemini API key (AIza...)" else "Paste OpenRouter key (sk-or-v1-...)"

            val modelAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presets)
            spinnerModelPreset.adapter = modelAdapter

            val cur = etModelName.text.toString().trim()
            val idx = presets.indexOf(cur)
            if (idx >= 0) {
                spinnerModelPreset.setSelection(idx)
            } else {
                spinnerModelPreset.setSelection(presets.size - 1) // Custom...
            }

            spinnerModelPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val sel = presets[position]
                    if (sel != "Custom...") {
                        etModelName.setText(sel)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        updateModelPresets(!currentProvider.equals("openrouter", ignoreCase = true))

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isGemini = position == 0
                val presets = if (isGemini) geminiPresets else openRouterPresets
                val defaultModel = presets[0]
                val curModel = etModelName.text.toString().trim()
                val currentIsGemini = !curModel.contains("/")

                if (isGemini != currentIsGemini) {
                    etModelName.setText(defaultModel)
                }
                updateModelPresets(isGemini)
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
            val isGemini = spinnerProvider.selectedItemPosition == 0
            val providerKey = if (isGemini) "gemini" else "openrouter"
            val key = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim().ifBlank {
                if (isGemini) "gemini-3.8-flash" else "google/gemini-3.8-flash"
            }

            prefs.edit()
                .putString("AI_PROVIDER", providerKey)
                .putString("API_KEY", key)
                .putString("MODEL_NAME", model)
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