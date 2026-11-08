package com.elite.chargerate

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process

/**
 * What an ordinary app is actually allowed to do about heat. Android does not let one app
 * force-close another, so this can only ask the system to drop other apps' background
 * processes, and point at the apps that have been used the most so the user can stop them.
 */
object CoolDown {

    class AppUse(val packageName: String, val label: String, val minutes: Int)

    /** Packages we never touch: this app and the home screen. */
    private fun protectedPackages(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        return setOfNotNull(ctx.packageName, home)
    }

    private fun isUserApp(info: ApplicationInfo): Boolean =
        info.flags and ApplicationInfo.FLAG_SYSTEM == 0 || info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

    /**
     * Asks Android to end the background processes of every installed app we are allowed to.
     * Apps that are on screen, or that Android keeps alive on purpose, are not affected, and
     * Android gives no feedback about which ones actually stopped. Returns how many we asked.
     */
    fun closeBackgroundApps(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val keep = protectedPackages(ctx)
        var asked = 0
        for (app in ctx.packageManager.getInstalledApplications(0)) {
            if (app.packageName in keep || !isUserApp(app)) continue
            try {
                am.killBackgroundProcesses(app.packageName)
                asked++
            } catch (_: Throwable) {
            }
        }
        return asked
    }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Apps with the most screen time in the last three hours. Needs usage access. */
    fun mostUsedApps(ctx: Context, limit: Int = 5): List<AppUse> {
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3 * 3_600_000L, now) ?: return emptyList()
        val pm = ctx.packageManager
        val keep = protectedPackages(ctx)
        return stats
            .filter { it.totalTimeInForeground > 60_000L && it.packageName !in keep }
            .groupBy { it.packageName }
            .mapValues { e -> e.value.sumOf { it.totalTimeInForeground } }
            .entries.sortedByDescending { it.value }
            .mapNotNull { (pkg, ms) ->
                if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Throwable) {
                    pkg
                }
                AppUse(pkg, label, (ms / 60_000L).toInt())
            }
            .take(limit)
    }
}
