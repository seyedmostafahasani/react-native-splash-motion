package com.margelo.nitro.splashmotion

import android.content.Context
import org.json.JSONObject

/** Build-time config written by `npx splash-motion generate` into `assets/splashmotion/`. */
internal data class SplashConfig(
  val file: String,
  val width: Double,
  val height: Double,
  val loop: Boolean,
  val loopMarker: String?,
  val introMarker: String?,
) {
  companion object {
    private fun JSONObject.optStringOrNull(key: String): String? =
      if (has(key) && !isNull(key)) getString(key).ifEmpty { null } else null

    fun load(context: Context): SplashConfig? = try {
      val text = context.assets.open("splashmotion/config.json").bufferedReader().use { it.readText() }
      val json = JSONObject(text)
      SplashConfig(
        file = json.getString("file"),
        width = json.optDouble("width", 160.0),
        height = json.optDouble("height", 160.0),
        loop = json.optBoolean("loop", true),
        loopMarker = json.optStringOrNull("loopMarker"),
        introMarker = json.optStringOrNull("introMarker"),
      )
    } catch (e: Exception) {
      null
    }
  }
}
