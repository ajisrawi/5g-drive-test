package com.drivetest.rf.core

import org.json.JSONObject
import java.util.Locale

fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

fun JSONObject.optIntOrNull(key: String): Int? = optDoubleOrNull(key)?.toInt()

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun Double.fmt(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)
fun Float.fmt(decimals: Int): String = toDouble().fmt(decimals)
