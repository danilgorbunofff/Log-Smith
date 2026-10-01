package com.danilgorbunofff.logsmith.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSniffersTest {

    private val logback = BuiltinSniffers.all.first { it.formatName == "Logback / Log4j" }
    private val plain = BuiltinSniffers.all.first { it.formatName == "Plain timestamp" }

    @Test
    fun `logback common layout with thread before level matches`() {
        assertTrue(logback.matches("2026-10-01 09:00:00.003 [http-nio-8080-exec-2] INFO c.e.web.IndexController - request handled"))
    }

    @Test
    fun `logback layout with level before thread matches`() {
        assertTrue(logback.matches("2026-10-01 09:14:02.101 DEBUG [http-nio-8080-exec-4] org.hibernate.SQL - select u1_0.id"))
    }

    @Test
    fun `continuation lines do not match - honest ratio`() {
        assertTrue(!logback.matches("    u1_0.id,"))
        assertTrue(!logback.matches("java.lang.NullPointerException: boom"))
    }

    @Test
    fun `plain timestamp fallback wins on lines without a level word`() {
        assertTrue(!logback.matches("2026-10-01 09:00:00 something happened"))
        assertTrue(plain.matches("2026-10-01 09:00:00 something happened"))
    }

    @Test
    fun `docker-style prefix keeps the file unknown on day one`() {
        val line = "docker-compose | 2026-10-01T09:14:00Z app-1 | [INFO] started"
        assertTrue(!logback.matches(line))
        assertTrue(!plain.matches(line))
    }

    @Test
    fun `stats renders the charter status format`() {
        val stats = FormatStats("Logback", 1204, 1208)
        assertEquals("Format: Logback — matched 1204 / 1208 lines (99.7%)", stats.toString())
    }

    @Test
    fun `higher ratio wins over priority`() {
        val high = FormatStats("Plain timestamp", 9, 10)
        val low = FormatStats("Logback / Log4j", 1, 10)
        assertTrue(high.ratio > low.ratio)
    }
}
