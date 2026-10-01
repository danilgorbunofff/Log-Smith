package com.danilgorbunofff.logsmith.sniff

import java.util.regex.Pattern

/**
 * The twelve charter formats (§5.1) plus the plain-timestamp fallback.
 * Every pattern matches a whole line. Continuation patterns cover the
 * non-record lines a formatter legitimately emits (stack frames, wrapped
 * messages, indented payloads) so they count as explained, not as noise.
 */
object BuiltinSniffers {

    private val LEVEL = "(?:TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|SEVERE|FATAL|CRITICAL|ALERT|EMERGENCY)"
    private val JUL_LEVEL = "(?:SEVERE|WARNING|INFO|CONFIG|FINE|FINER|FINEST)"
    private val MONTH = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"

    // Timestamp sub-patterns for the highlighter (match a record's date region,
    // either at offset 0 of a line or as the interior of a bracketed field).
    private val TS_ISO = Pattern.compile("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?""")
    private val TS_ISO_TZ = Pattern.compile(TS_ISO.pattern() + """(?: [+-]\d{2}:\d{2})?""")
    private val TS_ISO_MID = Pattern.compile("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?""")
    private val TS_JUL = Pattern.compile(MONTH + """ \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2} (?:AM|PM)""")
    private val TS_JULI = Pattern.compile("""\d{1,2}-""" + MONTH + """-\d{4} \d{2}:\d{2}:\d{2}\.\d{1,3}""")
    private val TS_GO = Pattern.compile("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}""")
    private val TS_SYSLOG = Pattern.compile("""(?:<\d{1,3}>)?""" + MONTH + """\s+\d{1,2} \d{2}:\d{2}:\d{2}""")
    private val TS_SQ = Pattern.compile("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?: [+-]\d{4})?""")
    private val TS_DJANGO = Pattern.compile("""\d{1,2}/""" + MONTH + """/\d{4} \d{2}:\d{2}:\d{2}""")
    private val TS_APACHE = Pattern.compile("""[A-Z][a-z]{2} """ + MONTH + """ \d{2} \d{2}:\d{2}:\d{2}\.\d{1,6} \d{4}""")
    private val TS_ACCESS = Pattern.compile("""\d{2}/""" + MONTH + """/\d{4}:\d{2}:\d{2}:\d{2} [+-]\d{4}""")

    private fun varargOf(vararg patterns: Pattern): Pattern =
        Pattern.compile(patterns.joinToString("|") { it.pattern() })

    /** 1. Logback / Log4j 2 — timestamp, then level and optional [thread] in either order. */
    private val logback = Pattern.compile(
        """\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?\s+(?:\[[^\]]*\]\s+)?""" + LEVEL + """\b.*"""
    )
    /** JVM stack-trace lines: frames, causes, elided frames, and the exception header line. */
    private val JAVA_STACK =
        """(?:\s+at .*)|(?:Caused by: .*)|(?:\s*\.{3} (?:\d+ )?more.*)""" +
            """|(?:[a-z]\w*(?:\.[\w$]+)+\.[A-Za-z]\w*(?:Exception|Error|Throwable)\b(?:: .*)?)"""

    private val logbackContinuation = Pattern.compile(
        JAVA_STACK + """|(?:\s{2,}\S.*)""",
        Pattern.CASE_INSENSITIVE
    )

    /** 2. java.util.logging — the JDK two-line SimpleFormatter and Tomcat JULI. */
    private val jul = Pattern.compile(
        """(?:""" + MONTH + """ \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2} (?:AM|PM) [\w$.]+ [\w$.]+)""" +
            """|(?:\d{1,2}-""" + MONTH + """-\d{4} \d{2}:\d{2}:\d{2}\.\d{1,3} """ + JUL_LEVEL + """ \[[^\]]*\].*)"""
    )
    private val julContinuation = Pattern.compile("""(?:""" + JUL_LEVEL + """: .*)|""" + JAVA_STACK)

    /** 3. Python logging — `date - name - LEVEL - msg` and the default `LEVEL:logger:msg`. */
    private val python = Pattern.compile(
        """(?:\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{1,3} - [\w.]+ - """ + LEVEL + """ - .*)""" +
            """|(?:""" + LEVEL + """:[\w.]+:.*)"""
    )
    /** Traceback header, `File "x", line N` frames, their indented source lines, and the final `XxxError: msg`. */
    private val pythonContinuation = Pattern.compile(
        """(?:Traceback \(most recent call last\):)|(?:\s+File "[^"]*", line \d+.*)|(?:\s{4,}\S.*)""" +
            """|(?:[A-Za-z_][\w.]*(?:Error|Exception|Warning|Exit|Interrupt)\b(?::.*)?)"""
    )

    /** 4. Go — stdlib `2009/11/10 23:00:00` (with optional file:line) and slog RFC3339. */
    private val go = Pattern.compile(
        """(?:\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(?: \S+\.go:\d+)? .*)""" +
            """|(?:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2}) """ + LEVEL + """ .*)"""
    )

    /** 5. Structured JSON lines — level + msg + time keys must all be present. */
    private val structuredJson = Pattern.compile(
        """\{(?=[^\n]*"level"\s*:)(?=[^\n]*"(?:msg|message|event)"\s*:)(?=[^\n]*"(?:time|timestamp|ts|ts_ms)"\s*:).*\}"""
    )

    /** 6. nginx / Apache combined access log. */
    private val nginxAccess = Pattern.compile(
        """(?:\d{1,3}\.){3}\d{1,3} \S+ \S+ \[[^\]]*\] "[^"]*" \d{3} (?:\d+|-).*"""
    )

    /** 7. nginx error log. */
    private val nginxError = Pattern.compile(
        """\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2} \[(?:emerg|alert|crit|error|warn|notice|info|debug)\] \d+#\d+: .*"""
    )

    /** 8. Apache error log — weekday-prefixed bracketed record. */
    private val apacheError = Pattern.compile(
        """\[[A-Z][a-z]{2} """ + MONTH + """ \d{2} \d{2}:\d{2}:\d{2}\.\d{1,6} \d{4}\] \[[^\]:]+:""" +
            """(?:emerg|alert|crit|error|warn|notice|info|debug)\].*"""
    )

    /** 9. PHP / Laravel via Monolog — `[date] channel.LEVEL: msg`. */
    private val monolog = Pattern.compile(
        """\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] [\w.-]+\.""" + LEVEL + """: .*"""
    )

    /** 10. Django / gunicorn — gunicorn bracketed records and Django runserver requests. */
    private val django = Pattern.compile(
        """(?:\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?: [+-]\d{4})?\] \[\d+\] \[[^\]]+\] .*)""" +
            """|(?:\[\d{1,2}/""" + MONTH + """/\d{4} \d{2}:\d{2}:\d{2}\] ".*" \d{3} .*)"""
    )

    /** 11. .NET (Microsoft.Extensions.Logging) — modern console tokens and Serilog prefix. */
    private val DOTNET_LEVEL = "(?:trce|dbug|info|warn|fail|crit|eror|ftl)"
    private val dotnet = Pattern.compile(
        """(?:\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{1,9} [+-]\d{2}:\d{2} \[[^\]]+\] .*)""" +
            """|(?:""" + DOTNET_LEVEL + """: \S+\[\d+\].*)"""
    )
    /** The console formatter prints the message on the next line, indented six spaces; stack frames follow. */
    private val dotnetContinuation = Pattern.compile("""(?: {6}\S.*)|(?:\s+at .*)|(?:\s*--- End of .*)""")

    /** 12. syslog — RFC3164 with optional <PRI>, and the ISO8601 rsyslog variant. */
    private val syslog = Pattern.compile(
        """(?:<\d{1,3}>)?(?:""" + MONTH + """\s+\d{1,2} \d{2}:\d{2}:\d{2} \S+ \S+(?:\[\d+\])?: .*)""" +
            """|(?:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2}) \S+ \S+(?:\[\d+\])?: .*)"""
    )

    /** Any line starting with a full timestamp; the last-resort generic candidate. */
    private val plainTimestamp = Pattern.compile(
        """\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}\b.*"""
    )

    val all: List<LogFormatSniffer> = listOf(
        RegexSniffer("Logback / Log4j 2", 20, logback, logbackContinuation, TS_ISO),
        RegexSniffer("java.util.logging", 30, jul, julContinuation, varargOf(TS_JUL, TS_JULI)),
        RegexSniffer("Python logging", 30, python, pythonContinuation, TS_ISO),
        RegexSniffer("Go log / slog", 30, go, timestamp = varargOf(TS_GO, TS_ISO_MID)),
        RegexSniffer("Structured JSON", 30, structuredJson),
        RegexSniffer("nginx / Apache access", 40, nginxAccess, timestamp = TS_ACCESS),
        RegexSniffer("nginx error", 40, nginxError, timestamp = TS_GO),
        RegexSniffer("Apache error", 40, apacheError, timestamp = TS_APACHE),
        RegexSniffer("PHP / Laravel (Monolog)", 30, monolog, timestamp = TS_SQ),
        RegexSniffer("Django / gunicorn", 30, django, timestamp = varargOf(TS_SQ, TS_DJANGO)),
        RegexSniffer(".NET (Microsoft.Extensions.Logging)", 30, dotnet, dotnetContinuation, TS_ISO_TZ),
        RegexSniffer("syslog", 30, syslog, timestamp = varargOf(TS_SYSLOG, TS_ISO_MID)),
        RegexSniffer("Plain timestamp", 10, plainTimestamp, timestamp = TS_ISO),
    )

    /** Lookup by format name for the toggle/installer wiring. */
    val byName: Map<String, LogFormatSniffer> = all.associateBy { it.formatName }
}
