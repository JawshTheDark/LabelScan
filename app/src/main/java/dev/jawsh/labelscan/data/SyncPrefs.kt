package dev.jawsh.labelscan.data

import android.content.Context

/** Stores the backend connection settings and cached auth token. */
class SyncPrefs(context: Context) {
    private val sp = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "")!!.trimEnd('/')
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var email: String
        get() = sp.getString("email", "")!!
        set(v) = sp.edit().putString("email", v.trim()).apply()

    var password: String
        get() = sp.getString("password", "")!!
        set(v) = sp.edit().putString("password", v).apply()

    var token: String
        get() = sp.getString("token", "")!!
        set(v) = sp.edit().putString("token", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0)
        set(v) = sp.edit().putLong("last_sync_at", v).apply()

    /** Configured enough to attempt a sync. */
    val configured: Boolean
        get() = baseUrl.startsWith("http") && email.isNotEmpty() && password.isNotEmpty()
}
