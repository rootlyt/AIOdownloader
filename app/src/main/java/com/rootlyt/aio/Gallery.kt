package com.rootlyt.aio

import android.content.Context
import org.json.JSONObject

// Record before publication: a crash can leave an unused record, never a guessed association.
object Gallery {
    data class Item(val uri: String, val name: String, val mime: String, val batch: String = "", val title: String = "")
    fun groups(items: List<Item>): List<List<Item>> = items.groupBy { if (it.batch.isEmpty()) "uri:${it.uri}" else "batch:${it.batch}" }.values.toList()
    @Synchronized fun record(context: Context, uri: String, batch: String, source: String, title: String): Boolean =
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putString(uri,
            JSONObject().put("batch", batch).put("source", source).put("title", title).toString()).commit()
    @Synchronized fun records(context: Context, present: Set<String>): Map<String, Pair<String, String>> {
        val prefs = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        val result = mutableMapOf<String, Pair<String, String>>()
        prefs.all.forEach { (uri, value) ->
            if (uri !in present) {
                context.contentResolver.query(android.net.Uri.parse(uri), arrayOf("_id"), null, null, null)?.use {
                    if (!it.moveToFirst()) edit.remove(uri)
                }
            }
            else try {
                val json = JSONObject(value as String)
                result[uri] = json.getString("batch") to json.optString("title")
            } catch (e: Exception) { android.util.Log.w("AIO", "Invalid gallery record", e); edit.remove(uri) }
        }
        edit.apply()
        return result
    }
}
