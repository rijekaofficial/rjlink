package rjlink.server.config

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigLoaderTest {

    @Test
    fun `ensureExists writes bundled defaults when file is missing`() {
        val tmp = Files.createTempDirectory("rjlink-cfg-")
        val target = tmp.resolve("config.yaml")
        try {
            assertFalse(Files.exists(target))
            val written = AppConfigLoader.ensureExists(target)
            assertTrue(written, "should report that defaults were written")
            assertTrue(Files.exists(target))

            val text = target.readText()
            assertTrue("RJLink server config" in text, "default header present")
            assertTrue("modules:" in text, "default contains modules section")

            // Default must be parseable round-trip and start in safe mode.
            val cfg = AppConfigLoader.load(target)
            assertTrue(cfg.modules.irc, "irc enabled by default")
            assertFalse(cfg.modules.tgbot, "tgbot disabled by default")
            assertFalse(cfg.admin.enabled, "admin disabled by default")
        } finally {
            target.deleteIfExists()
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `ensureExists is a no-op when the file already exists`() {
        val tmp = Files.createTempDirectory("rjlink-cfg-")
        val target = tmp.resolve("config.yaml")
        try {
            target.writeText("server:\n  port: 9999\n")
            val written = AppConfigLoader.ensureExists(target)
            assertFalse(written, "should not overwrite existing config")
            assertEquals("server:\n  port: 9999\n", target.readText())
        } finally {
            target.deleteIfExists()
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `empty yaml parses to all-defaults config`() {
        val tmp = Files.createTempDirectory("rjlink-cfg-")
        val target = tmp.resolve("config.yaml")
        try {
            target.writeText("{}\n")
            val cfg = AppConfigLoader.load(target)
            // All sections must accept defaults so a minimal user config still works.
            assertEquals(8888, cfg.server.port)
            assertEquals(1, cfg.minProtocolVersion)
            assertTrue(cfg.modules.irc)
        } finally {
            target.deleteIfExists()
            Files.deleteIfExists(tmp)
        }
    }
}
