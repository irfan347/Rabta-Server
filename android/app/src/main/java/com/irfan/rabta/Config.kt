package com.irfan.rabta

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object Config {
    const val SERVER = BuildConfig.API_ORIGIN
    /** Rabta Test and CI builds are debuggable and keep the developer screens; the public app hides them. */
    fun testBuild(c: Context) = c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @Volatile private var requested: Set<String>? = null
    /** The permissions this build asks for; the Google Play build leaves out a few that Play keeps for other kinds of apps. */
    fun declared(c: Context): Set<String> = requested ?: runCatching {
        c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
    }.getOrNull()?.also { requested = it }.orEmpty()
    fun declares(c: Context, permission: String) = permission in declared(c)
}
