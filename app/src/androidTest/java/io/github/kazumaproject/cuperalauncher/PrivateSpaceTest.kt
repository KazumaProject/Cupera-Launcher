package io.github.kazumaproject.cuperalauncher

import android.os.Build
import android.os.UserManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PrivateSpaceTest {
    @Test fun privateFixtureIsVisibleOnlyThroughUnlockedPrivateCatalog() {
        val fixture = InstrumentationRegistry.getArguments().getString("privateFixture")
        assumeTrue("Requires an isolated emulator with a private-only fixture", !fixture.isNullOrBlank())
        assertTrue(Build.VERSION.SDK_INT >= 35)
        val apps = Apps(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(apps.hasHome())
        val user = requireNotNull(apps.privateUser())
        assertEquals(UserManager.USER_TYPE_PROFILE_PRIVATE, apps.profileType(user))
        assertTrue(apps.privateUnlocked(user))
        assertTrue(apps.privateEntries(user).any { it.info.componentName.packageName == fixture })
        assertFalse(apps.entries().any { it.info.componentName.packageName == fixture })
    }

    @Test fun lockedPrivateCatalogDoesNotExposeItsApps() {
        val fixture = InstrumentationRegistry.getArguments().getString("lockedPrivateFixture")
        assumeTrue("Requires an isolated emulator with a locked private profile", !fixture.isNullOrBlank())
        assertTrue(Build.VERSION.SDK_INT >= 35)
        val apps = Apps(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(apps.hasHome())
        val user = requireNotNull(apps.privateUser())
        assertFalse(apps.privateUnlocked(user))
        assertTrue(apps.privateEntries(user).isEmpty())
        assertFalse(apps.entries().any { it.info.componentName.packageName == fixture })
    }
}
