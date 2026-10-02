package dev.arrbrants.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.view.Gravity
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout

/**
 * VM-style launch tests: activities are inflated and driven through their
 * lifecycle inside Robolectric's simulated Android environment (JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {

    @Test
    fun mainActivityLaunchesWithoutCrash() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .get()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun settingsActivityLaunchesWithoutCrash() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun drawerOpensAndNewChatResets() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .get()
        activity.findViewById<DrawerLayout>(R.id.drawerLayout).openDrawer(Gravity.START)
        activity.findViewById<View>(R.id.btnNewChat).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.drawerEmpty).visibility)
    }

    @Test
    fun traditionalChineseResourcesResolve() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale("zh", "TW"))
        val localized = context.createConfigurationContext(config)
        assertEquals("設定", localized.getString(R.string.settings_title))
        assertEquals("聊天記錄", localized.getString(R.string.drawer_title))
    }
}
