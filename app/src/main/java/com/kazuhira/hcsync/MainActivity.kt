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
import androidx.health.connect.client.records.MealType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_QUICK_SCAN = "com.kazuhira.hcsync.action.QUICK_SCAN"
        const val EXTRA_QUICK_SCAN = "EXTRA_QUICK_SCAN"

        // SHA-256 of an API key that shipped as a default in early builds; wiped from prefs on launch
        private const val LEGACY_KEY_SHA256 = "710e5cb7f10d74a9c854b2dd1aae515a0ec3cfd2ffa7b97f9d3da3821cdc63ec"
    }

    private lateinit var cameraPreviewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var tvModelSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTakePhoto: Button
    private lateinit var btnPickGallery: Button
    private lateinit var tabBar: IdroidTabBar
    private lateinit var listViewHistory: ListView
    private lateinit var tvTodayCalories: TextView
    private lateinit var tvTodayCount: TextView
    private lateinit var tvTodayProtein: TextView
    private lateinit var tvTodayCarbs: TextView
    private lateinit var tvTodayFat: TextView
    private lateinit var tvOpticalStatus: TextView
    private lateinit var tvPendingSync: TextView
    private lateinit var layoutPendingSync: View
    private lateinit var tvHudRations: TextView
    private lateinit var tvHudAvg: TextView
    private lateinit var parallaxManager: IdroidParallaxManager

    private lateinit var localRepo: LocalMealRepository
    private lateinit var hcSync: HealthConnectSync
    private lateinit var logAdapter: RationLogAdapter
    private var healthPermissionResult: CompletableDeferred<Set<String>>? = null
    private var syncJob: Job? = null
    private var tempPhotoUri: Uri? = null
    private var imageCapture: ImageCapture? = null

    // Launchers
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var requestCameraPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRepo = LocalMealRepository(this)
        hcSync = HealthConnectSync(this)
        initDefaultPrefs()

        cameraPreviewView = findViewById(R.id.cameraPreviewView)
        // TextureView-backed preview so the feed can be colour graded like the iDroid projection
        cameraPreviewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        IdroidOverlayView.applyHoloColorGrade(cameraPreviewView)
        statusText = findViewById(R.id.statusText)
        tvModelSubtitle = findViewById(R.id.tvModelSubtitle)
        progressBar = findViewById(R.id.progressBar)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnPickGallery = findViewById(R.id.btnPickGallery)
        tabBar = findViewById(R.id.tabBar)
        listViewHistory = findViewById(R.id.listViewHistory)
        tvTodayCalories = findViewById(R.id.tvTodayCalories)
        tvTodayCount = findViewById(R.id.tvTodayCount)
        tvTodayProtein = findViewById(R.id.tvTodayProtein)
        tvTodayCarbs = findViewById(R.id.tvTodayCarbs)
        tvTodayFat = findViewById(R.id.tvTodayFat)
        tvOpticalStatus = findViewById(R.id.tvOpticalStatus)
        tvPendingSync = findViewById(R.id.tvPendingSync)
        layoutPendingSync = findViewById(R.id.layoutPendingSync)
        tvHudRations = findViewById(R.id.tvHudRations)
        tvHudAvg = findViewById(R.id.tvHudAvg)

        parallaxManager = IdroidParallaxManager(this)
        findViewById<View>(R.id.cardTodaySummary)?.let {
            parallaxManager.registerView(it, translationDp = 6f, rotationDeg = 2.5f)
        }
        findViewById<View>(R.id.cardAcquireTarget)?.let {
            parallaxManager.registerView(it, translationDp = 8f, rotationDeg = 3.0f)
        }
        findViewById<View>(R.id.layoutRationSection)?.let {
            parallaxManager.registerView(it, translationDp = 4f, rotationDeg = 2.0f)
        }

        updateModelSubtitle()

        // Health Connect Permissions Contract
        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()
        requestPermissionsLauncher = registerForActivityResult(requestPermissionActivityContract) { granted ->
            healthPermissionResult?.complete(granted)
            healthPermissionResult = null
        }

        // Camera Permission Launcher for always-on optical feed
        requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                tvOpticalStatus.text = "OPTICAL HUD // STANDBY"
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

        // Tab strip: INTEL FILE | RATIONS (this screen) | CONFIG
        tabBar.onTabClick = { index ->
            when (index) {
                0 -> galleryLauncher.launch("image/*")
                2 -> showSettingsDialog()
            }
        }

        logAdapter = RationLogAdapter(this)
        listViewHistory.adapter = logAdapter
        listViewHistory.setOnItemClickListener { _, _, position, _ ->
            (logAdapter.getItem(position) as? LogRow.Meal)?.let { showMealActions(it.record) }
        }
        layoutPendingSync.setOnClickListener { syncPending(interactive = true) }

        refreshHistoryList()

        // Handle incoming intent if shared from Gallery/Camera app or launched via Quick Settings / shortcut
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        parallaxManager.start()
        // Refresh "today" after midnight and quietly push anything still pending
        refreshHistoryList()
        syncPending(interactive = false)
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
                tvOpticalStatus.text = "OPTICAL HUD // ACTIVE"
                statusText.text = "Optical feed online. Ready for target acquisition."
            } catch (e: Exception) {
                tvOpticalStatus.text = "OPTICAL HUD // OFFLINE"
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

        statusText.text = "Acquiring target scan..."
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
        if (legacyKey != null && sha256(legacyKey) == LEGACY_KEY_SHA256) {
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun updateModelSubtitle() {
        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val provider = prefs.getString("AI_PROVIDER", "gemini") ?: "gemini"
        val currentModel = prefs.getString("MODEL_NAME", prefs.getString("GEMINI_MODEL", "gemini-3.8-flash")) ?: "gemini-3.8-flash"
        val provTag = if (provider.equals("openrouter", ignoreCase = true)) "OPENROUTER" else "GEMINI"
        findViewById<TextView>(R.id.tvAppTitle)?.text = "KAZUHIRA SYNC VER ${BuildConfig.VERSION_NAME}"
        tvModelSubtitle.text = "$provTag // ${currentModel.uppercase()}"
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (ACTION_QUICK_SCAN == action || intent.getBooleanExtra(EXTRA_QUICK_SCAN, false)) {
            statusText.text = "Tactical Quick Scan: Optical sensor ready. Aim at ration."
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            btnTakePhoto.animate()
                .scaleX(1.04f).scaleY(1.04f)
                .setDuration(180)
                .withEndAction {
                    btnTakePhoto.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).start()
                }.start()
        } else if (Intent.ACTION_SEND == action && type != null && type.startsWith("image/")) {
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
            Toast.makeText(this, "Please configure your $providerName API key in Settings first", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        statusText.text = "Kazuhira is analyzing target intel ($modelName)..."
        progressBar.visibility = View.VISIBLE
        btnTakePhoto.isEnabled = false
        btnPickGallery.isEnabled = false

        val visionService = GeminiVisionService(this, apiKey, modelName, provider)
        lifecycleScope.launch {
            val result = visionService.analyzeFoodImage(imageUri)

            progressBar.visibility = View.GONE
            btnTakePhoto.isEnabled = true
            btnPickGallery.isEnabled = true

            result.onSuccess { estimation ->
                statusText.text = "Target analysis complete. Confirm data below."
                showNewMealDialog(imageUri, estimation, visionService)
            }.onFailure { exception ->
                val errorMsg = describeAnalysisError(exception)
                statusText.text = errorMsg
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun describeAnalysisError(exception: Throwable): String = when {
        exception is java.net.UnknownHostException ||
        exception.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
        exception.message?.contains("Comms offline", ignoreCase = true) == true ->
            "Tactical link offline: Check Wi-Fi or mobile data connection."
        exception.message?.contains("API key not valid", ignoreCase = true) == true ||
        exception.message?.contains("403") == true ->
            "Invalid API Key: Check Settings."
        else ->
            "Intel extraction error: ${exception.localizedMessage ?: "Unknown error"}"
    }

    // ===================== Ration editing =====================

    private fun showNewMealDialog(imageUri: Uri, estimation: MealEstimation, visionService: GeminiVisionService) {
        MealEditorDialog(
            activity = this,
            title = "CONFIRM RATION INTEL",
            saveLabel = "LOG RATION",
            initial = MealDraft.fromEstimation(estimation),
            imageUri = imageUri,
            onReanalyze = { hint -> visionService.analyzeFoodImage(imageUri, hint) }
        ) { draft ->
            commitMeal(newRecord(draft))
        }.show()
    }

    private fun newRecord(draft: MealDraft) = LocalMealRecord(
        id = System.currentTimeMillis().toString(),
        mealName = draft.mealName,
        calories = draft.calories,
        proteinG = draft.proteinG,
        carbG = draft.carbG,
        fatG = draft.fatG,
        notes = draft.notes,
        timestampIso = draft.mealTime.toString(),
        syncedToHealthConnect = false,
        mealType = draft.mealType,
        hcLinked = true
    )

    private fun showMealActions(meal: LocalMealRecord) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_meal_actions, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        val time = meal.instant.atZone(ZoneId.systemDefault())
        val mealType = HealthConnectSync.mealTypeLabel(HealthConnectSync.resolveMealType(meal))
        view.findViewById<TextView>(R.id.tvActionTitle).text = meal.mealName
        view.findViewById<TextView>(R.id.tvActionMeta).text = "$mealType // ${RationFormat.dayAndClock(this, time)}"
        view.findViewById<TextView>(R.id.tvActionMacros).text =
            "${RationFormat.kcal(meal.calories)} KCAL // P ${RationFormat.grams(meal.proteinG)} // " +
                "C ${RationFormat.grams(meal.carbG)} // F ${RationFormat.grams(meal.fatG)}"
        view.findViewById<TextView>(R.id.tvActionNotes).apply {
            text = meal.notes
            visibility = if (meal.notes.isBlank()) View.GONE else View.VISIBLE
        }
        val tvActionSync = view.findViewById<TextView>(R.id.tvActionSync)
        tvActionSync.text = when {
            !meal.syncedToHealthConnect -> "◇ PENDING HEALTH CONNECT SYNC"
            !meal.hcLinked -> "◆ IN HEALTH CONNECT // LOGGED BEFORE v2.2, CHANGES STAY LOCAL"
            else -> "◆ SYNCED TO HEALTH CONNECT"
        }
        if (!meal.syncedToHealthConnect) tvActionSync.setTextColor(ContextCompat.getColor(this, R.color.idroid_warn))

        view.findViewById<Button>(R.id.btnActionRetrySync).apply {
            visibility = if (meal.syncedToHealthConnect) View.GONE else View.VISIBLE
            setOnClickListener {
                dialog.dismiss()
                syncPending(interactive = true)
            }
        }
        view.findViewById<Button>(R.id.btnActionRelog).setOnClickListener {
            dialog.dismiss()
            MealEditorDialog(
                activity = this,
                title = "RESUPPLY RATION",
                saveLabel = "LOG RATION",
                initial = MealDraft.fromRecord(meal).copy(mealTime = Instant.now(), mealType = MealType.MEAL_TYPE_UNKNOWN)
            ) { draft ->
                commitMeal(newRecord(draft))
            }.show()
        }
        view.findViewById<Button>(R.id.btnActionEdit).setOnClickListener {
            dialog.dismiss()
            MealEditorDialog(
                activity = this,
                title = "AMEND RATION INTEL",
                saveLabel = "UPDATE RATION",
                initial = MealDraft.fromRecord(meal)
            ) { draft ->
                commitMeal(
                    meal.copy(
                        mealName = draft.mealName,
                        calories = draft.calories,
                        proteinG = draft.proteinG,
                        carbG = draft.carbG,
                        fatG = draft.fatG,
                        timestampIso = draft.mealTime.toString(),
                        mealType = draft.mealType,
                        // Legacy entries cannot be addressed in Health Connect, so they keep their state
                        syncedToHealthConnect = if (meal.hcLinked) false else meal.syncedToHealthConnect
                    )
                )
            }.show()
        }
        view.findViewById<Button>(R.id.btnActionDelete).apply {
            var armed = false
            setOnClickListener {
                // Two-tap confirmation
                if (!armed) {
                    armed = true
                    text = "CONFIRM DELETE"
                    return@setOnClickListener
                }
                dialog.dismiss()
                deleteMeal(meal)
            }
        }

        dialog.show()
    }

    private fun deleteMeal(meal: LocalMealRecord) {
        localRepo.deleteMeal(meal.id)
        refreshHistoryList()
        if (!meal.hcLinked) {
            statusText.text = "Ration removed from log. It was logged before v2.2, so delete it in Samsung Health or Health Connect manually."
            return
        }
        localRepo.addPendingDelete(meal.clientRecordId)
        statusText.text = "Ration ${meal.mealName} removed."
        // Only prompt for access if the record may actually exist in Health Connect
        syncPending(interactive = meal.syncedToHealthConnect)
    }

    // ===================== Health Connect sync =====================

    /** Saves the meal locally, then pushes it to Health Connect (insert or update). */
    private fun commitMeal(meal: LocalMealRecord) {
        localRepo.saveMeal(meal)
        refreshHistoryList()
        if (!meal.hcLinked) {
            statusText.text = "Ration updated locally. It was logged before v2.2, so its Health Connect entry is unchanged."
            return
        }

        statusText.text = "Transmitting ration intel to Health Connect..."
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                if (!ensureHealthConnectAccess(interactive = true)) {
                    statusText.text = "[WARN] Saved locally. ${healthConnectProblem()}"
                    return@launch
                }
                hcSync.upsert(meal)
                localRepo.markSynced(meal.id, true)
                statusText.text = "Logged ${meal.mealName} (${RationFormat.kcal(meal.calories)} kcal) to Health Connect."
            } catch (e: Exception) {
                statusText.text = "[WARN] Saved locally (Health Connect: ${e.message}). Tap PENDING to retry."
            } finally {
                progressBar.visibility = View.GONE
                refreshHistoryList()
            }
        }
    }

    /** Pushes unsynced meals and pending deletions to Health Connect. */
    private fun syncPending(interactive: Boolean) {
        if (syncJob?.isActive == true) return
        val pendingMeals = localRepo.getMeals().filter { !it.syncedToHealthConnect && it.hcLinked }
        val pendingDeletes = localRepo.getPendingDeletes()
        if (pendingMeals.isEmpty() && pendingDeletes.isEmpty()) return

        syncJob = lifecycleScope.launch {
            try {
                if (!ensureHealthConnectAccess(interactive)) {
                    if (interactive) statusText.text = "[WARN] ${healthConnectProblem()}"
                    return@launch
                }
                var failures = 0
                if (pendingDeletes.isNotEmpty()) {
                    try {
                        hcSync.delete(pendingDeletes)
                        localRepo.removePendingDeletes(pendingDeletes)
                    } catch (e: Exception) {
                        failures += pendingDeletes.size
                    }
                }
                for (meal in pendingMeals) {
                    try {
                        hcSync.upsert(meal)
                        localRepo.markSynced(meal.id, true)
                    } catch (e: Exception) {
                        failures++
                    }
                }
                statusText.text = if (failures == 0) {
                    "Health Connect link synchronized (${pendingMeals.size + pendingDeletes.size} update(s))."
                } else {
                    "[WARN] $failures Health Connect update(s) still pending."
                }
            } catch (e: Exception) {
                if (interactive) statusText.text = "[WARN] Health Connect sync failed: ${e.message}"
            } finally {
                refreshHistoryList()
            }
        }
    }

    /**
     * Returns true when Health Connect is installed and write access is granted. With [interactive],
     * asks the user for access (or sends them to install / update Health Connect) when needed.
     */
    private suspend fun ensureHealthConnectAccess(interactive: Boolean): Boolean {
        when (hcSync.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                if (interactive) openIntentSafely(hcSync.installIntent())
                return false
            }
            else -> return false
        }
        if (hcSync.hasPermissions()) return true
        if (!interactive) return false

        val result = healthPermissionResult ?: CompletableDeferred<Set<String>>().also {
            healthPermissionResult = it
            requestPermissionsLauncher.launch(HealthConnectSync.PERMISSIONS)
        }
        return result.await().containsAll(HealthConnectSync.PERMISSIONS)
    }

    private fun healthConnectProblem(): String = when (hcSync.sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> "Health Connect write access not granted."
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Install or update Health Connect from the Play Store."
        else -> "Health Connect is not available on this device."
    }

    private fun openIntentSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    // ===================== Ration log =====================

    private fun refreshHistoryList() {
        val meals = localRepo.getMeals()
        updateTodaySummary(meals)
        logAdapter.submit(meals)

        val pending = meals.count { !it.syncedToHealthConnect && it.hcLinked } + localRepo.getPendingDeletes().size
        layoutPendingSync.visibility = if (pending > 0) View.VISIBLE else View.GONE
        tvPendingSync.text = "⟳ Sync $pending Pending"
        updateHudStats(meals)
    }

    private fun updateTodaySummary(meals: List<LocalMealRecord>) {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val todayMeals = meals.filter { it.instant.atZone(zone).toLocalDate() == today }
        tvTodayCalories.text = RationFormat.kcal(todayMeals.sumOf { it.calories })
        tvTodayCount.text = "${todayMeals.size} RATION${if (todayMeals.size == 1) "" else "S"}"
        tvTodayProtein.text = RationFormat.grams(todayMeals.sumOf { it.proteinG })
        tvTodayCarbs.text = RationFormat.grams(todayMeals.sumOf { it.carbG })
        tvTodayFat.text = RationFormat.grams(todayMeals.sumOf { it.fatG })
    }

    /** Bottom-right HUD block: total rations logged and the average daily intake over the last 7 days. */
    private fun updateHudStats(meals: List<LocalMealRecord>) {
        val zone = ZoneId.systemDefault()
        val weekStart = LocalDate.now().minusDays(6)
        val recentDays = meals
            .filter { !it.instant.atZone(zone).toLocalDate().isBefore(weekStart) }
            .groupBy { it.instant.atZone(zone).toLocalDate() }
        tvHudRations.text = meals.size.toString()
        tvHudAvg.text = if (recentDays.isEmpty()) "0"
            else RationFormat.kcal(recentDays.values.sumOf { day -> day.sumOf { it.calories } } / recentDays.size)
    }

    private fun themedSpinnerAdapter(items: List<String>) =
        ArrayAdapter(this, R.layout.item_spinner, items).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
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
        val tvHcStatus = dialogView.findViewById<TextView>(R.id.tvHcStatus)
        val btnHealthConnect = dialogView.findViewById<Button>(R.id.btnHealthConnect)

        fun renderHealthConnectStatus() {
            lifecycleScope.launch {
                val granted = try { hcSync.hasPermissions() } catch (e: Exception) { false }
                val status = hcSync.sdkStatus()
                tvHcStatus.text = when {
                    granted -> "◆ LINK ESTABLISHED // WRITE ACCESS GRANTED"
                    status == HealthConnectClient.SDK_AVAILABLE -> "◇ WRITE ACCESS NOT GRANTED"
                    status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "◇ HEALTH CONNECT MISSING OR OUTDATED"
                    else -> "◇ HEALTH CONNECT UNAVAILABLE ON THIS DEVICE"
                }
                tvHcStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (granted) R.color.idroid_cyan else R.color.idroid_warn))
                btnHealthConnect.text = if (granted || status != HealthConnectClient.SDK_AVAILABLE) "MANAGE HEALTH CONNECT" else "GRANT HEALTH CONNECT ACCESS"
            }
        }
        renderHealthConnectStatus()

        btnHealthConnect.setOnClickListener {
            lifecycleScope.launch {
                when {
                    hcSync.sdkStatus() != HealthConnectClient.SDK_AVAILABLE -> openIntentSafely(hcSync.installIntent())
                    !hcSync.hasPermissions() -> {
                        if (ensureHealthConnectAccess(interactive = true)) syncPending(interactive = true)
                    }
                    !openIntentSafely(hcSync.settingsIntent()) ->
                        Toast.makeText(this@MainActivity, "Open Health Connect from system settings", Toast.LENGTH_LONG).show()
                }
                renderHealthConnectStatus()
            }
        }

        val prefs = getSharedPreferences("KazuhiraPrefs", MODE_PRIVATE)
        val currentProvider = prefs.getString("AI_PROVIDER", "gemini") ?: "gemini"
        val currentApiKey = prefs.getString("API_KEY", prefs.getString("GEMINI_API_KEY", "")) ?: ""
        val currentModel = prefs.getString("MODEL_NAME", prefs.getString("GEMINI_MODEL", "gemini-3.8-flash")) ?: "gemini-3.8-flash"

        etApiKey.setText(currentApiKey)
        etModelName.setText(currentModel)

        val providerOptions = listOf("Google Gemini (Direct)", "OpenRouter")
        val providerAdapter = themedSpinnerAdapter(providerOptions)
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

            val modelAdapter = themedSpinnerAdapter(presets)
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
