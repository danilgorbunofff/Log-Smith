package com.danilgorbunofff.logsmith.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whole-file detection on the shapes real applications write by default — including the long
 * stack traces that dominate a real failure log — so a common log is never reported as "no
 * format matched". Each case was reported as no format at all before the patterns covered it.
 */
class RealWorldFormatsTest {

    private fun detect(text: String): DetectionResult =
        LogScanner.scan(text.byteInputStream(), Charsets.UTF_8, 200, 1 shl 20).result

    private fun assertFormat(expected: String, text: String) {
        val result = detect(text)
        assertEquals(result.toString(), expected, (result as? DetectionResult.Matched)?.stats?.formatName)
    }

    private val frames = (0 until 40).joinToString("\n") { "\tat com.example.service.Step$it.run(Step$it.java:${10 + it})" }

    @Test
    fun `Spring Boot 3 console output with a startup failure is Logback`() {
        val records = (1..20).joinToString("\n") {
            "2026-10-01T09:14:02.%03d+02:00  INFO 12345 --- [demo] [           main] c.e.DemoApplication              : step $it".format(it)
        }
        val failure = "2026-10-01T09:14:03.000+02:00 ERROR 12345 --- [demo] [           main] o.s.boot.SpringApplication               : Application run failed\n" +
            "java.lang.IllegalStateException: boom\n$frames"
        assertFormat("Logback / Log4j 2", "$records\n$failure\n")
    }

    @Test
    fun `Laravel log dominated by stack traces is Monolog`() {
        val trace = (0 until 30).joinToString("\n") {
            "#$it /var/www/vendor/laravel/framework/src/Illuminate/Pipeline/Pipeline.php(${100 + it}): Illuminate\\Pipeline\\Pipeline->Illuminate\\Pipeline\\{closure}(Object(Illuminate\\Http\\Request))"
        }
        val error = "[2026-10-01 09:14:02] local.ERROR: SQLSTATE[HY000] [2002] Connection refused {\"exception\":\"[object] (Illuminate\\\\Database\\\\QueryException(code: 2002): Connection refused at /var/www/vendor/laravel/framework/src/Illuminate/Database/Connection.php:712)\n" +
            "[stacktrace]\n$trace\n#30 {main}\n\"} "
        val info = (1..5).joinToString("\n") { "[2026-10-01 09:14:0$it] local.INFO: request handled" }
        assertFormat("PHP / Laravel (Monolog)", "$info\n$error\n$error\n")
    }

    @Test
    fun `Monolog's own ISO timestamp is Monolog`() {
        val text = (1..10).joinToString("\n") { "[2026-10-01T09:14:0$it.123456+00:00] app.WARNING: slow query" }
        assertFormat("PHP / Laravel (Monolog)", text + "\n")
    }

    @Test
    fun `logstash-logback-encoder JSON is structured JSON`() {
        val text = (1..20).joinToString("\n") {
            "{\"@timestamp\":\"2026-10-01T09:14:02.$it+02:00\",\"@version\":\"1\",\"message\":\"step $it\",\"logger_name\":\"c.e.App\",\"thread_name\":\"main\",\"level\":\"INFO\",\"level_value\":20000}"
        }
        assertFormat("Structured JSON", text + "\n")
    }

    @Test
    fun `ECS JSON with log level is structured JSON`() {
        val text = (1..10).joinToString("\n") {
            "{\"@timestamp\":\"2026-10-01T09:14:02Z\",\"log.level\":\"info\",\"message\":\"step $it\",\"ecs.version\":\"1.2.0\"}"
        }
        assertFormat("Structured JSON", text + "\n")
    }

    @Test
    fun `winston default JSON without a timestamp is structured JSON`() {
        val text = (1..20).joinToString("\n") { "{\"level\":\"info\",\"message\":\"step $it\"}" }
        assertFormat("Structured JSON", text + "\n")
    }

    @Test
    fun `access log from IPv6 clients is an access log`() {
        val text = (1..20).joinToString("\n") {
            "2001:db8::$it - - [01/Oct/2026:09:14:02 +0000] \"GET /api/$it HTTP/1.1\" 200 512 \"-\" \"curl/8.0\""
        }
        assertFormat("nginx / Apache access", text + "\n")
    }

    @Test
    fun `real ANSI captures report their coloured lines so they still get coloured`() {
        for (name in listOf("docker.log", "ansi/docker-logs.log", "ansi/npm-install.log", "ansi/pytest-verbose.log")) {
            val result = LogScanner.scan(File("testdata/$name").inputStream(), Charsets.UTF_8, 200, 1 shl 20).result
            val coloured = when (result) {
                is DetectionResult.NoMatch -> result.ansiLines
                is DetectionResult.Matched -> Int.MAX_VALUE // a claimed format colours ANSI anyway
                is DetectionResult.Failed -> 0
            }
            assertTrue("$name: $result", coloured > 0)
        }
    }
}
