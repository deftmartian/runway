package dev.deftmartian.runway

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunwayApplicationInstrumentedTest {
    @Test
    fun workManagerInitializesOnDemandFromTheApplicationConfiguration() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        assertTrue(application is Configuration.Provider)
        assertNotNull(WorkManager.getInstance(application))
        assertTrue(WorkManager.isInitialized())
    }
}
