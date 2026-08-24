package com.sahidcode404.camex.core.update

import android.content.Context

class UpdateAutoChecker(
    context: Context,
    private val client: GitHubUpdateClient,
    private val installed: InstalledAppInfo,
    private val channel: UpdateChannel,
) {
    private val prefs = context.getSharedPreferences("camera_update_state", Context.MODE_PRIVATE)

    suspend fun checkIfDue(
        nowMs: Long = System.currentTimeMillis(),
        intervalMs: Long = channel.automaticCheckIntervalMs,
    ): UpdateCheckResult? {
        val key = "last_check_ms_${channel.buildConfigValue}"
        val last = prefs.getLong(key, 0L)
        if (intervalMs > 0L && nowMs - last < intervalMs) return null
        val result = client.check(installed)
        prefs.edit().putLong(key, nowMs).apply()
        return result
    }
}
