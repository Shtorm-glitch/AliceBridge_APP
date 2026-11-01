package dev.alicebridge.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Registry indexes may contain the complete version history for a package. */
internal object YamCatalog {
    fun latest(index: JSONArray): List<JSONObject> {
        val packages = linkedMapOf<String, JSONObject>()
        for (i in 0 until index.length()) {
            val item = index.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val previous = packages[id]
            if (previous == null || compareVersions(item.optString("version"), previous.optString("version")) > 0) {
                packages[id] = item
            }
        }
        return packages.values.toList()
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parts(version: String) = version.removePrefix("v").substringBefore('+').split('-', limit = 2)
        val a = parts(left); val b = parts(right)
        val av = a[0].split('.'); val bv = b[0].split('.')
        for (i in 0 until maxOf(av.size, bv.size)) {
            val x = av.getOrElse(i) { "0" }; val y = bv.getOrElse(i) { "0" }
            val xn = x.toBigIntegerOrNull(); val yn = y.toBigIntegerOrNull()
            val comparison = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (comparison != 0) return comparison
        }
        if (a.size != b.size) return if (a.size == 1) 1 else -1
        if (a.size == 1) return 0
        val ap = a[1].split('.'); val bp = b[1].split('.')
        for (i in 0 until minOf(ap.size, bp.size)) {
            val xn: BigInteger? = ap[i].toBigIntegerOrNull(); val yn = bp[i].toBigIntegerOrNull()
            val comparison = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> ap[i].compareTo(bp[i])
            }
            if (comparison != 0) return comparison
        }
        return ap.size.compareTo(bp.size)
    }
}
