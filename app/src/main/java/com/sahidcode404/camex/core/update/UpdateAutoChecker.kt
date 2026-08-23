package com.sahidcode404.camex.core.update

import android.content.Context

/** Matches Universal_Camera: check GitHub at most once every 12 hours when Camera is opened. */
class UpdateAutoChecker(
    context: Context,
    private val client: GitHubUpdateClient,
    private val installed: InstalledAppInfo,
) {
    private val prefs = context.getSharedPreferences("camera_update_state", Context.MODE_PRIVATE)

    suspend fun checkIfDue(
        nowMs: Long = System.currentTimeMillis(),
        intervalMs: Long = 12L * 60L * 60L * 1000L,
    ): UpdateCheckResult? {
        val last = prefs.getLong("last_check_ms", 0L)
        if (nowMs - last < intervalMs) return null
        val result = client.check(installed)
        prefs.edit().putLong("last_check_ms", nowMs).apply()
        return result
    }
}
