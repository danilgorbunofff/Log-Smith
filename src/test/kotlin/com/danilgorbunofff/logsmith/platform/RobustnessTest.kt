package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.LogSmithActionPromoter
import com.danilgorbunofff.logsmith.LogSmithEditorAttacher
import com.danilgorbunofff.logsmith.LogSmithEditorSession
import com.danilgorbunofff.logsmith.LogSmithGotoNextErrorAction
import com.danilgorbunofff.logsmith.filter.FilterState
import com.danilgorbunofff.logsmith.filter.LogLevel
import com.danilgorbunofff.logsmith.filter.LogSmithFilterService
import com.danilgorbunofff.logsmith.filter.LogSmithStackFrames
import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithLazyHighlighter
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.highlight.LogSmithTokenTypes
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Regression tests from the 2026-10-03 verification of Days 1–9: each one reproduced a defect
 * through the real code path before the fix.
 */
class RobustnessTest : BasePlatformTestCase() {

    private fun record(n: Int, level: String = "INFO ") =
        "2026-10-01 09:00:00.%03d [main] $level c.e.App - line $n".format(n % 1000)

    private fun attached(name: String, text: String): LogSmithEditorSession {
        val file = myFixture.configureByText(name, text).virtualFile
        LogSmithEditorAttacher.attachAll(FileEditorManager.getInstance(project), file)
        val session = myFixture.editor.getUserData(LogSmithEditorSession.SESSION_KEY)!!
        session.detection?.awaitForTests()
        session.indexJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return session
    }

    private fun LogSmithEditorSession.filterAndWait(state: FilterState) {
        applyFilter(state)
        filterJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    // ------------------------------------------------------------------ folds

    fun `test the record after a fold starts on its own line`() {
        val text = listOf(record(1, "ERROR"), record(2), record(3), record(4, "ERROR")).joinToString("\n") + "\n"
        val session = attached("fold.log", text)
        session.filterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        val editor = session.textEditor.editor
        assertEquals(1, editor.foldingModel.allFoldRegions.size)
        val fold = editor.foldingModel.allFoldRegions.single()
        assertEquals("… 2 hidden", fold.placeholderText)
        assertEquals("the fold ends before the last hidden line's newline", editor.document.getLineEndOffset(2), fold.endOffset)
        val visual = editor.offsetToVisualPosition(editor.document.getLineStartOffset(3))
        assertEquals("the next record starts at column 0", 0, visual.column)
        assertEquals("the placeholder has a line of its own", 2, visual.line)
    }

    fun `test a document change keeps the folds until the new plan replaces them`() {
        val text = listOf(record(1, "ERROR"), record(2), record(3), record(4, "ERROR")).joinToString("\n") + "\n"
        val session = attached("keep.log", text)
        session.filterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        val editor = session.textEditor.editor
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, record(5) + "\n")
        }
        assertEquals("folds stay while the re-plan runs", 1, editor.foldingModel.allFoldRegions.size)
        session.filterJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        session.filterJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // Records 2, 3 and the appended 5 are hidden, and the empty last line goes with record 5.
        assertTrue(session.strip.text, session.strip.text.contains("filter hides 4 of 6 lines"))
        assertEquals(2, editor.foldingModel.allFoldRegions.size)
    }

    // ------------------------------------------------------------------ filter facts

    fun `test JUL headers take the level of the line under them`() {
        val text = listOf(
            "Oct 01, 2026 3:30:44 PM com.example.App main",
            "SEVERE: boom",
            "Oct 01, 2026 3:30:45 PM com.example.App main",
            "INFO: fine",
        ).joinToString("\n") + "\n"
        val session = attached("jul.log", text)
        session.filterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        val fold = session.textEditor.editor.foldingModel.allFoldRegions.single()
        assertEquals("the INFO record — header and level line — is hidden", session.textEditor.editor.document.getLineStartOffset(2), fold.startOffset)
    }

    fun `test appended facts match a full scan`() {
        val head = (1..30).joinToString("\n") { record(it, if (it % 7 == 0) "ERROR" else "INFO ") } + "\n"
        val session = attached("append.log", head)
        session.filterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        repeat(3) { round ->
            WriteCommandAction.runWriteCommandAction(project) {
                val document = session.textEditor.editor.document
                document.insertString(document.textLength, record(100 + round, "ERROR") + "\n\tat com.example.App.run(App.java:1)\n")
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            session.filterJob?.awaitForTests()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        val incremental = session.textEditor.editor.foldingModel.allFoldRegions.map { it.startOffset to it.endOffset }
        // A fresh session-independent plan over the same text must fold the same ranges.
        val document = session.textEditor.editor.document
        var full: LogSmithFilterService.Outcome? = null
        project.getService(LogSmithFilterService::class.java).plan(
            myFixture.configureByText("full.log", document.text).virtualFile, myFixture.editor,
            FilterState(setOf(LogLevel.ERROR), ""), BuiltinSniffers.byName.getValue("Logback / Log4j 2"),
        ) { _, outcome -> full = outcome }.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val folded = full as LogSmithFilterService.Outcome.Folded
        assertEquals(folded.plan.size, incremental.size)
        assertTrue(session.strip.text, session.strip.text.contains("filter hides ${folded.hiddenLines} of"))
    }

    // ------------------------------------------------------------------ F2

    fun `test F2 is promoted to LogSmith in a log editor only`() {
        val session = attached("keys.log", record(1, "ERROR") + "\n")
        val promoter = ActionPromoter.EP_NAME.extensionList.filterIsInstance<LogSmithActionPromoter>().singleOrNull()
        assertNotNull("the promoter must be registered", promoter)
        val platform = ActionManager.getInstance().getAction("GotoNextError")
        val mine = ActionManager.getInstance().getAction("LogSmith.GotoNextError")
        assertTrue(mine is LogSmithGotoNextErrorAction)
        val context = (session.textEditor.editor as EditorEx).dataContext
        assertEquals(listOf(mine), promoter!!.promote(listOfNotNull(platform, mine), context))

        val plain = myFixture.configureByText("notes.txt", "hello\n")
        assertEquals(plain.name, "notes.txt")
        assertEquals(emptyList<Any>(), promoter.promote(listOfNotNull(platform, mine), (myFixture.editor as EditorEx).dataContext))
    }

    fun `test F2 says so when there is no error to go to`() {
        val session = attached("calm.log", (1..5).joinToString("\n") { record(it) } + "\n")
        session.gotoError(true)
        session.factsJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("No ERROR records in this log", session.lastHint)
    }

    // ------------------------------------------------------------------ ANSI without a format

    fun `test a coloured file no format claims still gets its colours`() {
        val session = attached("docker.log", File("testdata/docker.log").readText())
        assertTrue(session.strip.text, session.strip.text.contains("no format matched"))
        assertTrue(session.strip.text, session.strip.text.contains("showing ANSI colours only"))
        assertTrue("an ANSI-only highlighter must be installed", session.isHighlighting)
        val editor = session.textEditor.editor as EditorEx
        val iterator = editor.highlighter.createIterator(0)
        assertEquals("the leading escape is a token of its own", LogSmithTokenTypes.ANSI_ESCAPE, iterator.tokenType)
        assertEquals("and paints no ink", editor.colorsScheme.defaultBackground, iterator.textAttributes.foregroundColor)
    }

    // ------------------------------------------------------------------ the lazy highlighter

    private fun lazyHighlighter(document: Document, sniffer: LogFormatSniffer, base: EditorHighlighter? = null): LogSmithLazyHighlighter {
        val highlighter = LogSmithLazyHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            EditorColorsManager.getInstance().globalScheme,
            base,
        )
        highlighter.setEditor(object : HighlighterClient {
            override fun getProject() = this@RobustnessTest.project
            override fun repaint(start: Int, end: Int) {}
            override fun getDocument() = document
        })
        highlighter.setText(document.immutableCharSequence)
        return highlighter
    }

    private val logback get() = BuiltinSniffers.byName.getValue("Logback / Log4j 2")

    fun `test retreat walks back to the first token and then reports atEnd`() {
        val document = EditorFactory.getInstance().createDocument((1..600).joinToString("\n") { record(it) } + "\n")
        val highlighter = lazyHighlighter(document, logback)
        val forward = ArrayList<Int>()
        highlighter.createIterator(0).let { while (!it.atEnd()) { forward += it.start; it.advance() } }

        val iterator = highlighter.createIterator(document.getLineStartOffset(400) + 5)
        val backward = ArrayList<Int>()
        var guard = forward.size + 10
        while (!iterator.atEnd() && guard-- > 0) {
            backward += iterator.start
            iterator.retreat()
        }
        assertTrue("retreat must end in atEnd", iterator.atEnd())
        assertEquals("backwards meets the very tokens forwards met", forward.filter { it <= backward.first() }.reversed(), backward)
        iterator.advance()
        assertFalse("advancing from before the start lands on the first token", iterator.atEnd())
        assertEquals(0, iterator.start)
    }

    fun `test a long line anywhere in a window is never handed to the sniffer`() {
        var longest = 0
        val probe = object : LogFormatSniffer by logback {
            override fun matches(line: String): Boolean {
                longest = maxOf(longest, line.length)
                return logback.matches(line)
            }
        }
        val payload = "2026-10-01 09:00:00.500 [main] INFO  c.e.App - payload " + "{\"k\": [1, 2]} ".repeat(16_000)
        val lines = (1..5).map { record(it) } + payload + (6..20).map { record(it) }
        val document = EditorFactory.getInstance().createDocument(lines.joinToString("\n") + "\n")
        val highlighter = lazyHighlighter(document, probe)
        val iterator = highlighter.createIterator(0)
        var expected = 0
        while (!iterator.atEnd()) {
            assertEquals(expected, iterator.start)
            expected = iterator.end
            if (iterator.end >= document.textLength) break
            iterator.advance()
        }
        assertEquals(document.textLength, expected)
        assertTrue("a line over 64K was segmented ($longest chars)", longest <= LogSmithLazyHighlighter.MAX_SEGMENTED_LINE_CHARS)
    }

    fun `test scrolling inside a block is served from the cache`() {
        var calls = 0
        val probe = object : LogFormatSniffer by logback {
            override fun matches(line: String): Boolean {
                calls++
                return logback.matches(line)
            }
        }
        val document = EditorFactory.getInstance().createDocument((1..1000).joinToString("\n") { record(it) } + "\n")
        val highlighter = lazyHighlighter(document, probe)
        highlighter.createIterator(document.getLineStartOffset(300)).textAttributes
        calls = 0
        for (line in 301..400) highlighter.createIterator(document.getLineStartOffset(line)).textAttributes
        assertEquals("one-line scroll steps must not rebuild windows", 0, calls)
    }

    // ------------------------------------------------------------------ never worse than the platform

    private val digit = IElementType("VERIFY_DIGIT", null)
    private val other = IElementType("VERIFY_OTHER", null)

    /** A stand-in for the platform's highlighter (TextMate in a real IDE): it colours digit runs. */
    private fun digitHighlighter(): EditorHighlighter = LexerEditorHighlighter(
        object : SyntaxHighlighterBase() {
            override fun getHighlightingLexer() = object : LexerBase() {
                private var buffer: CharSequence = ""
                private var end = 0
                private var tokenStart = 0
                private var tokenEnd = 0
                override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
                    this.buffer = buffer
                    end = endOffset
                    tokenStart = startOffset
                    tokenEnd = startOffset
                    advance()
                }
                override fun getState() = 0
                override fun getTokenType() = if (tokenStart >= end) null else if (buffer[tokenStart].isDigit()) digit else other
                override fun getTokenStart() = tokenStart
                override fun getTokenEnd() = tokenEnd
                override fun advance() {
                    tokenStart = tokenEnd
                    if (tokenStart >= end) return
                    val isDigit = buffer[tokenStart].isDigit()
                    var i = tokenStart
                    while (i < end && buffer[i].isDigit() == isDigit) i++
                    tokenEnd = i
                }
                override fun getBufferSequence() = buffer
                override fun getBufferEnd() = end
            }
            override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
                if (tokenType == digit) arrayOf(DefaultLanguageHighlighterColors.NUMBER) else emptyArray()
        },
        EditorColorsManager.getInstance().globalScheme,
    )

    fun `test colours the platform gives are kept where LogSmith adds none`() {
        val text = record(42, "ERROR") + "\n"
        val document = EditorFactory.getInstance().createDocument(text)
        val highlighter = lazyHighlighter(document, logback, digitHighlighter())
        val scheme = EditorColorsManager.getInstance().globalScheme
        val number = scheme.getAttributes(DefaultLanguageHighlighterColors.NUMBER)
        assertNotNull(number.foregroundColor)

        fun attributesAt(offset: Int) = highlighter.createIterator(offset).let { it.tokenType to it.textAttributes }
        val messageDigits = text.lastIndexOf("42")
        val (messageType, messageAttributes) = attributesAt(messageDigits)
        assertEquals(LogSmithTokenTypes.MESSAGE, messageType)
        assertEquals("the platform's number colour survives inside the message", number.foregroundColor, messageAttributes.foregroundColor)

        val (timeType, timeAttributes) = attributesAt(2)
        assertEquals(LogSmithTokenTypes.TIMESTAMP, timeType)
        assertFalse("LogSmith's timestamp colour wins over the platform's", timeAttributes.foregroundColor == number.foregroundColor)
        val (levelType, levelAttributes) = attributesAt(text.indexOf("ERROR"))
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, levelType)
        assertNotNull(levelAttributes.foregroundColor)

        // The tokens still tile the line.
        val iterator = highlighter.createIterator(0)
        var expected = 0
        while (!iterator.atEnd()) {
            assertEquals(expected, iterator.start)
            expected = iterator.end
            iterator.advance()
        }
        assertEquals(text.length, expected)
    }

    /**
     * End to end, against the platform's real `.log` highlighting (the bundled TextMate log
     * grammar, which the test IDE runs): installing LogSmith keeps the grammar's colours for the
     * strings, numbers and URLs in a message, and adds its own only where it has one.
     */
    fun `test a claimed log file keeps the TextMate colours LogSmith does not replace`() {
        val line = "2026-10-01 09:00:00.001 [main] ERROR c.e.App - GET \"/orders\" failed with 404 at https://x.io/a"
        val text = (1..5).joinToString("\n") { record(it) } + "\n" + line + "\n"
        val file = myFixture.configureByText("textmate.log", text).virtualFile
        val editor = myFixture.editor as EditorEx
        fun foregroundAt(highlighter: EditorHighlighter, word: String) =
            highlighter.createIterator(text.indexOf(word)).textAttributes.foregroundColor
        val platform = editor.highlighter
        val string = foregroundAt(platform, "\"/orders\"")
        val number = foregroundAt(platform, "404")
        assertNotNull("the platform colours strings in a .log", string)
        assertNotNull("the platform colours numbers in a .log", number)

        LogSmithEditorAttacher.attachAll(FileEditorManager.getInstance(project), file)
        val session = editor.getUserData(LogSmithEditorSession.SESSION_KEY)!!
        session.detection?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(session.isHighlighting)
        val mine = editor.highlighter
        assertEquals("a quoted string keeps the platform colour", string, foregroundAt(mine, "\"/orders\""))
        assertEquals("a number keeps the platform colour", number, foregroundAt(mine, "404"))
        assertEquals(
            "ERROR is LogSmith's",
            EditorColorsManager.getInstance().globalScheme.getAttributes(com.danilgorbunofff.logsmith.highlight.LogSmithColors.LEVEL_ERROR).foregroundColor,
            foregroundAt(mine, "ERROR"),
        )
    }

    fun `test the platform layer follows edits`() {
        val document = EditorFactory.getInstance().createDocument(record(1) + "\n")
        val highlighter = lazyHighlighter(document, logback, digitHighlighter())
        document.addDocumentListener(highlighter)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "plain 7\n") }
        val number = EditorColorsManager.getInstance().globalScheme.getAttributes(DefaultLanguageHighlighterColors.NUMBER)
        val iterator = highlighter.createIterator(document.textLength - 2)
        assertEquals(number.foregroundColor, iterator.textAttributes.foregroundColor)
    }

    // ------------------------------------------------------------------ logs too large for a text editor

    fun `test the too-large check agrees with the IDE's own text-editor provider`() {
        val dir = java.nio.file.Files.createTempDirectory("logsmith-large").toFile()
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, dir.path, dir.canonicalPath)
        try {
            fun real(name: String, size: Int) = File(dir, name).let { file ->
                file.writeBytes(ByteArray(size) { if (it % 100 == 99) '\n'.code.toByte() else 'a'.code.toByte() })
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.toPath())!!
            }
            val big = real("big.log", 25 shl 20)
            val small = real("small.log", 4096)
            val provider = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance()
            assertFalse("the IDE gives a 25 MB log no text editor", provider.accept(project, big))
            assertTrue(LogSmithEditorAttacher.isTooLargeForEditor(big))
            assertTrue(provider.accept(project, small))
            assertFalse(LogSmithEditorAttacher.isTooLargeForEditor(small))
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(dir)
        }
    }

    // ------------------------------------------------------------------ stack frames

    fun `test absolute Node Go and PHP frames are clickable`() {
        val node = "    at Object.<anonymous> (/app/src/index.js:10:5)"
        val nodeSpan = LogSmithStackFrames.spanAt(node, node.indexOf("index.js"))!!
        assertEquals("/app/src/index.js", nodeSpan.path)
        assertEquals(10, nodeSpan.line)
        assertEquals(4, nodeSpan.column)
        val go = "\t/home/user/app/main.go:42 +0x1d"
        assertEquals("/home/user/app/main.go", LogSmithStackFrames.spanAt(go, go.indexOf("main.go"))!!.path)
        val php = "#3 /var/www/app/Http/Kernel.php(42): Illuminate\\Pipeline\\Pipeline->then()"
        val phpSpan = LogSmithStackFrames.spanAt(php, php.indexOf("Kernel"))!!
        assertEquals("/var/www/app/Http/Kernel.php", phpSpan.path)
        assertEquals(42, phpSpan.line)
        val windows = "error at C:\\src\\App.cs:12"
        assertEquals("C:\\src\\App.cs", LogSmithStackFrames.spanAt(windows, windows.indexOf("App"))!!.path)
        val start = "src/app.ts:10:5 - error TS2322"
        assertEquals("src/app.ts", LogSmithStackFrames.spanAt(start, 2)!!.path)
    }

    fun `test the frame's package picks between files with the same name`() {
        myFixture.addFileToProject("a/com/example/billing/Service.java", "class Service {}\n")
        val wanted = myFixture.addFileToProject("b/src/com/example/user/Service.java", "class Service {}\n").virtualFile
        val log = myFixture.addFileToProject("logs/app.log", "x\n").virtualFile
        val line = "\tat com.example.user.Service.load(Service.java:12)"
        val span = LogSmithStackFrames.spanAt(line, line.indexOf("Service.java"))!!
        assertEquals("com/example/user", span.packageDir)
        assertEquals(wanted.path, LogSmithStackFrames.resolve(project, log, span.path, span.packageDir)?.path)
    }

    fun `test an absolute frame resolves on the log's own file system`() {
        val target = myFixture.addFileToProject("app/src/index.js", "x\n").virtualFile
        val log = myFixture.addFileToProject("logs/app.log", "x\n").virtualFile
        assertEquals(target.path, LogSmithStackFrames.resolve(project, log, target.path)?.path)
    }
}
