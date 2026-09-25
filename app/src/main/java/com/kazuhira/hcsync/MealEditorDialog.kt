package com.kazuhira.hcsync

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.records.MealType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Editable ration values, as shown in [MealEditorDialog]. */
data class MealDraft(
    val mealName: String,
    val calories: Double,
    val proteinG: Double,
    val carbG: Double,
    val fatG: Double,
    val notes: String,
    val mealTime: Instant,
    // MEAL_TYPE_UNKNOWN lets the dialog infer it from the meal time
    val mealType: Int = MealType.MEAL_TYPE_UNKNOWN,
    val confidence: String = ""
) {
    companion object {
        fun fromEstimation(estimation: MealEstimation) = MealDraft(
            mealName = estimation.mealName.replace("&", "and"),
            calories = estimation.calories,
            proteinG = estimation.proteinG,
            carbG = estimation.carbG,
            fatG = estimation.fatG,
            notes = estimation.notes.replace("&", "and"),
            mealTime = Instant.now(),
            confidence = estimation.confidence
        )

        fun fromRecord(record: LocalMealRecord) = MealDraft(
            mealName = record.mealName,
            calories = record.calories,
            proteinG = record.proteinG,
            carbG = record.carbG,
            fatG = record.fatG,
            notes = record.notes,
            mealTime = record.instant,
            mealType = record.mealType
        )
    }
}

/**
 * Confirmation / edit dialog for a ration: name, meal time, meal type, portion multiplier,
 * calories and macros. When [onReanalyze] is set, the user can brief Kaz with extra context and
 * re-run the image analysis.
 */
class MealEditorDialog(
    private val activity: AppCompatActivity,
    private val title: String,
    private val saveLabel: String,
    private val initial: MealDraft,
    private val imageUri: Uri? = null,
    private val onReanalyze: (suspend (hint: String) -> Result<MealEstimation>)? = null,
    private val onSave: (MealDraft) -> Unit
) {

    private val zone = ZoneId.systemDefault()

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm_meal, null)

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvConfidence = view.findViewById<TextView>(R.id.tvConfidence)
        val imgPreview = view.findViewById<ImageView>(R.id.imgMealPreview)
        val etMealName = view.findViewById<EditText>(R.id.etMealName)
        val tvMealTime = view.findViewById<TextView>(R.id.tvMealTime)
        val tvPortion = view.findViewById<TextView>(R.id.tvPortion)
        val seekPortion = view.findViewById<SeekBar>(R.id.seekPortion)
        val fields = listOf<EditText>(
            view.findViewById(R.id.etCalories),
            view.findViewById(R.id.etProtein),
            view.findViewById(R.id.etCarbs),
            view.findViewById(R.id.etFat)
        )
        val layoutNotes = view.findViewById<View>(R.id.layoutNotes)
        val tvNotes = view.findViewById<TextView>(R.id.tvNotes)
        val layoutBriefing = view.findViewById<View>(R.id.layoutBriefing)
        val etBriefing = view.findViewById<EditText>(R.id.etBriefing)
        val btnReanalyze = view.findViewById<Button>(R.id.btnReanalyze)
        val progressReanalyze = view.findViewById<ProgressBar>(R.id.progressReanalyze)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelMeal)
        val btnSave = view.findViewById<Button>(R.id.btnSaveMeal)
        val chips = mapOf(
            view.findViewById<TextView>(R.id.chipBreakfast) to MealType.MEAL_TYPE_BREAKFAST,
            view.findViewById<TextView>(R.id.chipLunch) to MealType.MEAL_TYPE_LUNCH,
            view.findViewById<TextView>(R.id.chipDinner) to MealType.MEAL_TYPE_DINNER,
            view.findViewById<TextView>(R.id.chipSnack) to MealType.MEAL_TYPE_SNACK
        )

        tvTitle.text = title
        btnSave.text = saveLabel
        etMealName.setText(initial.mealName)

        if (imageUri != null) imgPreview.setImageURI(imageUri) else imgPreview.visibility = View.GONE
        layoutBriefing.visibility = if (imageUri != null && onReanalyze != null) View.VISIBLE else View.GONE

        fun renderNotes(notes: String, confidence: String) {
            tvNotes.text = notes
            layoutNotes.visibility = if (notes.isBlank()) View.GONE else View.VISIBLE
            if (confidence.isBlank()) {
                tvConfidence.visibility = View.GONE
            } else {
                val low = confidence.startsWith("low")
                tvConfidence.visibility = View.VISIBLE
                tvConfidence.text = "CONF // ${confidence.uppercase(Locale.ENGLISH)}"
                tvConfidence.setTextColor(ContextCompat.getColor(activity, if (low) R.color.idroid_warn else R.color.idroid_cyan_bright))
                tvConfidence.setBackgroundResource(if (low) R.drawable.bg_idroid_warn_badge else R.drawable.bg_idroid_badge_yellow)
            }
        }
        var notes = initial.notes
        var confidence = initial.confidence
        renderNotes(notes, confidence)

        // ---- Meal time and type ----
        var mealTime: ZonedDateTime = initial.mealTime.atZone(zone)
        var mealType = initial.mealType.takeIf { it != MealType.MEAL_TYPE_UNKNOWN }
            ?: HealthConnectSync.inferMealType(mealTime.toLocalTime())
        var mealTypePicked = false

        fun renderMealType() = chips.forEach { (chip, type) -> chip.isSelected = type == mealType }
        fun renderMealTime() {
            tvMealTime.text = "◷  ${RationFormat.dayAndClock(activity, mealTime)}"
        }
        renderMealType()
        renderMealTime()

        chips.forEach { (chip, type) ->
            chip.setOnClickListener {
                mealType = type
                mealTypePicked = true
                renderMealType()
            }
        }

        tvMealTime.setOnClickListener {
            val datePicker = DatePickerDialog(
                activity, R.style.IdroidPickerDialog,
                { _, year, month, day ->
                    TimePickerDialog(
                        activity, R.style.IdroidPickerDialog,
                        { _, hour, minute ->
                            val picked = ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, zone)
                            val now = ZonedDateTime.now(zone)
                            mealTime = if (picked.isAfter(now)) now else picked
                            if (!mealTypePicked) mealType = HealthConnectSync.inferMealType(mealTime.toLocalTime())
                            renderMealTime()
                            renderMealType()
                        },
                        mealTime.hour, mealTime.minute, DateFormat.is24HourFormat(activity)
                    ).show()
                },
                mealTime.year, mealTime.monthValue - 1, mealTime.dayOfMonth
            )
            datePicker.datePicker.maxDate = System.currentTimeMillis()
            datePicker.show()
        }

        // ---- Portion multiplier: fields show base value × multiplier ----
        val base = doubleArrayOf(initial.calories, initial.proteinG, initial.carbG, initial.fatG)
        var multiplier = 1.0
        var updatingFields = false

        fun renderFields() {
            updatingFields = true
            fields.forEachIndexed { i, field ->
                field.setText(RationFormat.fieldValue(base[i] * multiplier, wholeNumber = i == 0))
            }
            updatingFields = false
        }
        renderFields()

        fields.forEachIndexed { i, field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // A manual edit redefines the base so the multiplier keeps scaling from it
                    if (!updatingFields) base[i] = RationFormat.parse(s) / multiplier
                }
            })
        }

        seekPortion.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                multiplier = 0.25 * (progress + 1)
                tvPortion.text = String.format(Locale.US, "×%.2f", multiplier)
                renderFields()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        // ---- Re-analysis with operator briefing ----
        btnReanalyze.setOnClickListener {
            val reanalyze = onReanalyze ?: return@setOnClickListener
            val hint = etBriefing.text.toString()
            btnReanalyze.isEnabled = false
            btnSave.isEnabled = false
            progressReanalyze.visibility = View.VISIBLE
            activity.lifecycleScope.launch {
                val result = reanalyze(hint)
                btnReanalyze.isEnabled = true
                btnSave.isEnabled = true
                progressReanalyze.visibility = View.GONE
                result.onSuccess { estimation ->
                    val draft = MealDraft.fromEstimation(estimation)
                    etMealName.setText(draft.mealName)
                    base[0] = draft.calories
                    base[1] = draft.proteinG
                    base[2] = draft.carbG
                    base[3] = draft.fatG
                    multiplier = 1.0
                    seekPortion.progress = 3
                    tvPortion.text = "×1.00"
                    renderFields()
                    notes = draft.notes
                    confidence = draft.confidence
                    renderNotes(notes, confidence)
                }.onFailure { e ->
                    Toast.makeText(activity, "Re-scan failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val values = fields.map { RationFormat.parse(it.text) }
            dialog.dismiss()
            onSave(
                MealDraft(
                    mealName = etMealName.text.toString().ifBlank { "Meal" }.replace("&", "and").trim(),
                    calories = values[0],
                    proteinG = values[1],
                    carbG = values[2],
                    fatG = values[3],
                    notes = notes,
                    mealTime = mealTime.toInstant(),
                    mealType = mealType,
                    confidence = confidence
                )
            )
        }

        dialog.show()
    }
}
