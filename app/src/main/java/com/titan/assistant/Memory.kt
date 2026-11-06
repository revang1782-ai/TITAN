package com.titan.assistant

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Uzun süreli hafıza: telefonda bir dosyada saklanır. */
object Memory {
    private fun file(c: Context) = File(c.filesDir, "memory.json")

    @Synchronized
    fun all(c: Context): List<String> {
        return try {
            val a = JSONArray(file(c).readText())
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(c: Context, fact: String) {
        val f = fact.trim()
        if (f.isEmpty()) return
        val l = all(c).toMutableList()
        if (l.none { it.equals(f, ignoreCase = true) }) {
            l.add(f)
            file(c).writeText(JSONArray(l).toString())
        }
    }

    @Synchronized
    fun clear(c: Context) {
        file(c).delete()
    }
}
