package com.kazuhira.hcsync

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Shared formatting for ration times and nutrition values. */
object RationFormat {

    fun clock(context: Context, time: ZonedDateTime): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(time)
    }

    fun dayLabel(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> "TODAY"
            today.minusDays(1) -> "YESTERDAY"
            else -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).format(date).uppercase(Locale.ENGLISH)
        }
    }

    fun dayAndClock(context: Context, time: ZonedDateTime): String =
        "${dayLabel(time.toLocalDate())} // ${clock(context, time)}"

    fun kcal(value: Double): String = value.roundToInt().toString()

    fun grams(value: Double): String = "${value.roundToInt()}g"

    /** Editable field value: whole kcal, macros with at most one decimal. */
    fun fieldValue(value: Double, wholeNumber: Boolean): String =
        if (wholeNumber) value.roundToInt().toString()
        else String.format(Locale.US, "%.1f", value).removeSuffix(".0")

    /** Parses user input, accepting both '.' and ',' as decimal separator. */
    fun parse(text: CharSequence?): Double =
        text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
}
