package com.sbro.emucorer.data

import android.content.Context
import java.net.URI
import java.security.MessageDigest

/** Public, user-selected catalog URLs. No credentials from another application. */
object DukeCatalogSource {
    private const val PREFS = "duke_public_catalogs"
    fun get(context: Context, kind: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind, "").orEmpty()

    fun isValid(url: String): Boolean = url.isBlank() || runCatching {
        val uri = URI(url.trim())
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    fun set(context: Context, kind: String, url: String) {
        require(kind == "textures" || kind == "cheats")
        require(isValid(url)) { "Use a public HTTPS catalog URL" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kind, url.trim()).apply()
    }

    fun cacheName(kind: String, url: String): String {
        // Keep the old cache available offline, but never mix two configured sources.
        if (url.isBlank()) return "$kind-v1.json"
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$kind-$hash.json"
    }
}
