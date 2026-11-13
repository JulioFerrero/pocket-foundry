package app.pocketfoundry.core.saves

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAppTest {
    @Test fun `the new app's releases`() {
        for (tag in listOf("v0.9", "v0.9.0", "v0.9.1", "v0.10", "v1.0", "0.9")) assertTrue(tag, NewApp.isNewApp(tag))
    }

    @Test fun `this app's releases`() {
        for (tag in listOf("v0.8.18", "v0.8.17", "v0.8.18-beta.7", "v0.6.10", "")) {
            assertFalse(tag, NewApp.isNewApp(tag))
        }
    }
}
