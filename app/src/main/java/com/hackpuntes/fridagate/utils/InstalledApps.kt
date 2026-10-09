package com.hackpuntes.fridagate.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * InstalledApps - Lists the apps a pentester can target and maps them to Linux uids.
 *
 * Used by the Extras tab (app to inject) and the Proxy tab (app whose traffic is
 * redirected). Every Android app runs under its own uid, which is what the iptables
 * owner match uses to pick one app's traffic.
 */
object InstalledApps {

    data class AppInfo(val name: String, val packageName: String)

    /** Non-system apps sorted by name. Blocking: call it from Dispatchers.IO. */
    fun load(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val all = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return all
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { AppInfo(name = it.loadLabel(pm).toString(), packageName = it.packageName) }
            .sortedBy { it.name.lowercase() }
    }

    /** uid of [packageName], or null if it isn't installed */
    fun uidOf(context: Context, packageName: String): Int? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        info.uid
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** First package that runs under [uid], or null if none */
    fun packageOf(context: Context, uid: Int): String? =
        context.packageManager.getPackagesForUid(uid)?.firstOrNull()
}
