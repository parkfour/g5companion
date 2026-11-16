package dev.g5.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Asks DPVR's update server whether newer firmware exists, the same anonymous lookup the DPVR app makes
 * (`app/ota/getNewOtaPackage?deviceVersion=<bt version>&code=<project+customer>`). Only those two values are
 * sent: none of the phone/MAC/device-name headers the vendor app adds. Check only; installing stays with the
 * vendor app, since a bad flash can brick the glasses.
 */
object Firmware {
    private const val URL = "https://bsapi.dpaiglasses.com/api/app/ota/getNewOtaPackage"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val version: String, val notes: String) : Result
    }

    suspend fun check(btVersion: String, modelCode: String): Result = withContext(Dispatchers.IO) {
        val url = URL.toHttpUrl().newBuilder().addQueryParameter("deviceVersion", btVersion).addQueryParameter("code", modelCode).build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            parse(body)
        }
    }

    /**
     * Envelope `{code, message, data: {ble: Part?, wifi: Part?}}`, Part = `{OtaPackage: {deviceVersion, …}, InternationalData: [...]}`.
     * The server returns a part only when it is newer than the version sent; no parts means up to date.
     */
    fun parse(body: String, locale: Locale = Locale.getDefault()): Result {
        val json = JSONObject(body)
        val code = json.optInt("code", 200)
        if (code != 200 && code != 0) throw IOException(json.optString("message").ifBlank { "Update server error $code" })
        val data = json.optJSONObject("data") ?: return Result.UpToDate
        val parts = listOfNotNull(data.optJSONObject("wifi"), data.optJSONObject("ble"))
        val version = parts.firstNotNullOfOrNull { p -> p.field("OtaPackage")?.optString("deviceVersion")?.takeIf { it.isNotBlank() } }
            ?: return Result.UpToDate
        val notes = parts.mapNotNull { p ->
            val list = p.optJSONArray("InternationalData") ?: p.optJSONArray("internationalData") ?: return@mapNotNull null
            val entries = (0 until list.length()).map { list.getJSONObject(it) }
            (entries.firstOrNull { locale.toString().contains(it.optString("languageCode").ifBlank { "\u0000" }) } ?: entries.firstOrNull())
                ?.optString("detail")?.takeIf { it.isNotBlank() }
        }.joinToString("\n")
        return Result.Available(version, notes)
    }

    // The live server capitalizes these keys; the vendor app's model uses lowercase. Accept both.
    private fun JSONObject.field(name: String) = optJSONObject(name) ?: optJSONObject(name.replaceFirstChar { it.lowercase() })
}
