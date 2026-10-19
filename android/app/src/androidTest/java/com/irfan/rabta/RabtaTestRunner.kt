package com.irfan.rabta

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Device tests drive Rabta's screens themselves. The chats screen asks Android's "keep Rabta running?" question once by
 * itself; here it counts as asked, so Android's screen never covers one under test. ContactsTest asks it on purpose.
 */
class RabtaTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        // Before the tests start, which super.onCreate does.
        targetContext.getSharedPreferences("background_run", 0).edit().putBoolean("asked", true).commit()
        super.onCreate(arguments)
    }
}
