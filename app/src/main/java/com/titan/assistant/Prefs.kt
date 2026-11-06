package com.titan.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API anahtarı şifreli saklanır; sadece kurulumda bir kez girilir. */
object Prefs {
    private fun sp(c: Context): SharedPreferences {
        return try {
            val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                c, "titan_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            c.getSharedPreferences("titan_plain", Context.MODE_PRIVATE)
        }
    }

    fun apiKey(c: Context): String = sp(c).getString("api", "") ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api", v).apply()

    fun model(c: Context): String =
        (sp(c).getString("model", "") ?: "").ifBlank { "gemini-flash-latest" }

    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v).apply()
}
