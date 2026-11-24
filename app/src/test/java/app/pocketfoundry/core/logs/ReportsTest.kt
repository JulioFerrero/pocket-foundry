package app.pocketfoundry.core.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportsTest {
    @Test fun `a save's name leaves the logs, its folder and the rest of the line stay`() {
        val logs = """
            0.000 Program arguments: "/data/user/0/dev.pocketfoundry/files/game/bin/arm64/factorio" "--load-game" "/data/user/0/dev.pocketfoundry/files/factorio/saves/Ana's base.zip"
           28.270 Loading map /data/user/0/dev.pocketfoundry/files/factorio/saves/lana12.zip: 3264334 bytes.
            0.010 Write data path: /data/data/dev.pocketfoundry/files/factorio [9013/230837MB]
        """.trimIndent()
        assertEquals("""
            0.000 Program arguments: "/data/user/0/dev.pocketfoundry/files/game/bin/arm64/factorio" "--load-game" "/data/user/0/dev.pocketfoundry/files/factorio/saves/(save).zip"
           28.270 Loading map /data/user/0/dev.pocketfoundry/files/factorio/saves/(save).zip: 3264334 bytes.
            0.010 Write data path: /data/data/dev.pocketfoundry/files/factorio [9013/230837MB]
        """.trimIndent(), Reports.withoutSaveNames(logs))
    }
}
