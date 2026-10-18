package com.irfan.rabta

import android.content.Context
import android.content.pm.ApplicationInfo

object Config {
    const val SERVER = "https://rabta-server-1e2i.onrender.com"
    /** Rabta Test and CI builds are debuggable and keep the developer screens; the public app hides them. */
    fun testBuild(c: Context) = c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
