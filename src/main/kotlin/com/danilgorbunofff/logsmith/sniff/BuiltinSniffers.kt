package com.danilgorbunofff.logsmith.sniff

import java.util.regex.Pattern

/**
 * Built-in sniffers. Day 3-4 expands this to the twelve charter formats;
 * today it carries the two that prove the pipeline end to end.
 */
object BuiltinSniffers {

    /** Logback / Log4j common layout: leading timestamp, optional [thread], level word. */
    private val common = Pattern.compile(
        """\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}[.,]\d{1,3}\s+(?:\[.*?\]\s+)?(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\b.*"""
    )

    /** Any line starting with a full timestamp; the last-resort generic candidate. */
    private val plainTimestamp = Pattern.compile(
        """\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}\b.*"""
    )

    val all: List<LogFormatSniffer> = listOf(
        RegexSniffer("Logback / Log4j", 20, common),
        RegexSniffer("Plain timestamp", 10, plainTimestamp),
    )
}
