package cl.ecualizador

import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @Test
    fun opensWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(20) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        }
    }
}
