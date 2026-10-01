package com.danilgorbunofff.logsmith.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSniffersTest {

    private fun byName(name: String): LogFormatSniffer =
        BuiltinSniffers.all.first { it.formatName == name }

    private val logback get() = byName("Logback / Log4j 2")
    private val jul get() = byName("java.util.logging")
    private val python get() = byName("Python logging")
    private val go get() = byName("Go log / slog")
    private val json get() = byName("Structured JSON")
    private val nginxAccess get() = byName("nginx / Apache access")
    private val nginxError get() = byName("nginx error")
    private val apacheError get() = byName("Apache error")
    private val monolog get() = byName("PHP / Laravel (Monolog)")
    private val django get() = byName("Django / gunicorn")
    private val dotnet get() = byName(".NET (Microsoft.Extensions.Logging)")
    private val syslog get() = byName("syslog")
    private val plain get() = byName("Plain timestamp")

    private fun score(vararg lines: String): FormatStats? =
        FormatScorer(BuiltinSniffers.all).apply { lines.forEach { onLine(it) } }.best()

    // ---------------------------------------------------------------- formats

    @Test
    fun `logback with thread before level matches`() {
        assertTrue(logback.matches("2026-10-01 09:00:00.003 [http-nio-8080-exec-2] INFO c.e.web.IndexController - request handled"))
    }

    @Test
    fun `logback with level before thread and double space matches`() {
        assertTrue(logback.matches("2026-10-01 09:14:02.320  WARN [scheduling-1] c.e.s.CleanupJob - 3 stale sessions removed"))
    }

    @Test
    fun `logback continuation covers stack frames wrapped messages and indented sql`() {
        assertTrue(logback.matchesContinuation("\tat com.example.service.UserService.sendWelcomeEmail(UserService.java:128)"))
        assertTrue(logback.matchesContinuation("Caused by: java.lang.IllegalStateException: boom"))
        assertTrue(logback.matchesContinuation("... 3 more"))
        assertTrue(logback.matchesContinuation("java.lang.NullPointerException: Cannot invoke \"User.getEmail()\" because \"user\" is null"))
        assertTrue(logback.matchesContinuation("    select u1_0.id, u1_0.email from users u1_0"))
        assertFalse(logback.matches("\tat com.example.service.UserService.sendWelcomeEmail(UserService.java:128)"))
    }

    @Test
    fun `jdk two-line jul layout matches both lines`() {
        assertTrue(jul.matches("Oct 01, 2026 3:30:44 PM JulProbe main"))
        assertTrue(jul.matchesContinuation("INFO: hello world"))
    }

    @Test
    fun `tomcat juli layout matches`() {
        assertTrue(jul.matches("01-Oct-2026 09:14:00.123 INFO [main] org.apache.catalina.startup.Catalina.start Server startup in [123] ms"))
    }

    @Test
    fun `python formatter and default format both match`() {
        assertTrue(python.matches("2026-10-01 09:14:00,123 - myapp.routes - DEBUG - loaded 4 routes"))
        assertTrue(python.matchesContinuation("WARNING:root:disk almost full"))
        assertTrue(python.matchesContinuation("Traceback (most recent call last):"))
        assertTrue(python.matchesContinuation("  File \"app.py\", line 10, in <module>"))
    }

    @Test
    fun `go stdlib and slog both match`() {
        assertTrue(go.matches("2026/10/01 09:14:00 main.go:42: serving on :8080"))
        assertTrue(go.matches("2026/10/01 09:14:00 serving on :8080"))
        assertTrue(go.matches("2026-10-01T09:14:00.123Z INFO user authenticated"))
        assertTrue(go.matches("2026-10-01T09:14:00+03:00 ERROR dial timeout"))
    }

    @Test
    fun `structured json line with level msg and time matches`() {
        assertTrue(json.matches("""{"level":"info","msg":"request served","time":1696158840000}"""))
        assertFalse(json.matches("""{"msg":"no level or time here"}"""))
        assertFalse(json.matches("""{"level":"info","other":1}"""))
    }

    @Test
    fun `nginx combined access line matches`() {
        assertTrue(nginxAccess.matches("192.168.1.42 - alice [01/Oct/2026:09:14:00 +0000] \"GET /api/v1/orders HTTP/1.1\" 200 8123 \"https://example.com\" \"Mozilla/5.0\""))
        assertTrue(nginxAccess.matches("10.0.0.9 - - [01/Oct/2026:09:14:00 +0000] \"POST /login HTTP/1.1\" 404 - \"-\" \"-\""))
    }

    @Test
    fun `nginx error line matches`() {
        assertTrue(nginxError.matches("2026/10/01 09:14:00 [error] 12345#12345: *6789 open() \"/var/www/html/favicon.ico\" failed (2: No such file or directory)"))
    }

    @Test
    fun `apache error line matches`() {
        assertTrue(apacheError.matches("[Wed Oct 01 09:14:00.123456 2026] [core:error] [pid 1234: tid 140000123456789] [client 10.0.0.9:54123] AH00128: File does not exist: /var/www/html/favicon.ico"))
    }

    @Test
    fun `monolog line matches`() {
        assertTrue(monolog.matches("[2026-10-01 09:14:00] production.ERROR: SQLSTATE[42S02]: Base table not found"))
        assertTrue(monolog.matches("[2026-10-01 09:14:00] local.NOTICE: cache warmed"))
    }

    @Test
    fun `gunicorn and django runserver lines match`() {
        assertTrue(django.matches("[2026-10-01 09:14:00 +0000] [1234] [INFO] Starting gunicorn 21.2.0"))
        assertTrue(django.matches("[01/Oct/2026 09:14:00] \"GET /admin/ HTTP/1.1\" 200 12345"))
    }

    @Test
    fun `dotnet console and serilog prefixes match`() {
        assertTrue(dotnet.matches("2026-10-01 09:14:00.123 +00:00 [INF] Now listening on: http://localhost:5000"))
        assertTrue(dotnet.matches("info: Microsoft.Hosting.Lifetime[0]"))
        assertTrue(dotnet.matches("fail: MyApp.Services.EmailSender[3]"))
    }

    @Test
    fun `syslog rfc3164 pri prefixed and iso variants match`() {
        assertTrue(syslog.matches("Oct  1 09:14:00 myhost sshd[1234]: Accepted publickey for root from 10.0.0.9 port 54123 ssh2"))
        assertTrue(syslog.matches("<34>Oct 11 22:14:15 mymachine su: 'su root' failed for lonvick on /dev/pts/8"))
        assertTrue(syslog.matches("2026-10-01T09:14:00.123456+02:00 myhost sshd[1234]: Accepted publickey"))
    }

    @Test
    fun `plain timestamp wins when no level word is present`() {
        assertFalse(logback.matches("2026-10-01 09:00:00 something happened"))
        assertTrue(plain.matches("2026-10-01 09:00:00 something happened"))
    }

    // ---------------------------------------------------------------- scorer

    @Test
    fun `scorer explains hibernate-shaped file as logback`() {
        val stats = score(
            "2026-10-01 09:14:02.101 DEBUG [http-nio-8080-exec-4] org.hibernate.SQL - select u1_0.id",
            "    select",
            "        u1_0.id,",
            "        orders (user_id, total, id)",
            "2026-10-01 09:14:02.105 TRACE [http-nio-8080-exec-4] o.h.t.d.s.BasicBinder - binding parameter [1]",
            "",
            "\tat com.example.service.UserService.sendWelcomeEmail(UserService.java:128)",
        )
        assertEquals("Logback / Log4j 2", stats?.formatName)
        assertEquals(6, stats?.matched)
        assertEquals(6, stats?.scanned)
    }

    @Test
    fun `scorer rejects a mostly-unstructured file honestly`() {
        assertNull(
            score(
                "random text with no timestamps at all",
                "another random line",
                "more noise",
                "just words",
                "no format here",
                "still nothing",
                "2026-10-01 09:00:00 one lonely timestamped line",
            )
        )
    }

    @Test
    fun `scorer returns unknown for ansi docker multiplexed output`() {
        assertNull(
            score(
                "\u001B[90m2026-10-01T09:14:00.123Z\u001B[0m \u001B[32mdocker-compose\u001B[0m | \u001B[32mapp-1  | \u001B[0mserver started on :3000",
                "\u001B[33mapp-1  | \u001B[0mwarning: deprecated option",
                "\u001B[38;5;196mapp-1  | \u001B[0m\u001B[1;31mError: connect ECONNREFUSED\u001B[0m",
            )
        )
    }

    @Test
    fun `scorer breaks ties by sniffer priority`() {
        // both logback and plain explain these lines fully; logback outranks plain
        val stats = score(
            "2026-10-01 09:00:00.001 INFO started",
            "2026-10-01 09:00:01.002 INFO stopped",
        )
        assertEquals("Logback / Log4j 2", stats?.formatName)
    }

    @Test
    fun `scorer handles crlf endings and blanks`() {
        val stats = score("2026-10-01 09:00:00.001 [main] INFO a.b.C - ok\r", "", "   ")
        assertEquals("Logback / Log4j 2", stats?.formatName)
        assertEquals(1, stats?.scanned)
        assertNull(score("", "   "))
    }

    @Test
    fun `capped scan carries its note into the rendered stats`() {
        val scorer = FormatScorer(BuiltinSniffers.all)
        scorer.onLine("2026-10-01 09:00:00.001 [main] INFO a.b.C - one")
        scorer.markCapped("scan covered the first 200 lines")
        val stats = scorer.best()
        assertEquals(
            "Format: Logback / Log4j 2 — matched 1 / 1 lines (100.0%) — scan covered the first 200 lines",
            stats.toString(),
        )
    }

    @Test
    fun `stats renders thousands grouping`() {
        val stats = FormatStats("Logback / Log4j 2", 1204, 1208)
        assertEquals("Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%)", stats.toString())
    }

    @Test
    fun `empty scan yields no winner`() {
        assertNull(score())
        assertNull(score("", "   "))
    }
}
