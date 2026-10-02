import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.danilgorbunofff"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

kotlin {
    jvmToolchain(21)
}

// Compile against a locally installed IDE when `logsmith.localIde` is set (e.g. in
// ~/.gradle/gradle.properties: logsmith.localIde=C:/Program Files/JetBrains/WebStorm 2025.3.2).
// Everywhere else — CI, a fresh machine — pin the public community platform (§8.1).
val localIde: String? = providers.gradleProperty("logsmith.localIde").orNull

dependencies {
    intellijPlatform {
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdeaCommunity("2025.2")
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // First stable that satisfies the charter target matrix (2025.2+).
            sinceBuild = "252"
            // Deliberately unset so the plugin keeps loading in 2025.3.x and future EAPs (§8.1).
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
            // Charter §8.1 Decision 2: verify against the next IDE version too, not just stable.
            latest {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.EAP)
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.7.1"
    }

    /**
     * §5.3 (R5): the heavy perf assertions — a 500 MB line index and the time-to-first-paint
     * budget — only run when asked for, so the default suite stays fast and independent of
     * the gitignored big.log fixture. CI drives them from the `perf` job in verify.yml.
     */
    test {
        systemProperty("logsmith.perf", providers.gradleProperty("logsmith.perf").orElse("false").get())
    }

    /**
     * Cross-platform replacement for testdata/generate-test-files.ps1 (charter §7.2):
     * writes hibernate.log, docker.log and the ~400 MB big.log (gitignored). CRLF endings,
     * UTF-8 without BOM, same content as the PowerShell script. -PbigLogLines=0 skips big.log.
     */
    register("generateTestData") {
        group = "verification"
        description = "Regenerates the §7.2 adversarial log fixtures in testdata/."
        val outDir = layout.projectDirectory.dir("testdata")
        val bigLogLines = providers.gradleProperty("bigLogLines").map { it.toInt() }.orElse(4_200_000)
        doLast {
            val nl = "\r\n"
            val dir = outDir.asFile
            val hibernate = listOf(
                "2026-10-01 09:14:02.101 DEBUG [http-nio-8080-exec-4] org.hibernate.SQL - ",
                "    select", "        u1_0.id,", "        u1_0.email,", "        u1_0.name,",
                "        o2_0.user_id,", "        o2_0.id,", "        o2_0.total", "    from", "        users u1_0 ",
                "    left join", "        orders o2_0 on u1_0.id=o2_0.user_id ", "    where", "        u1_0.email=?",
                "2026-10-01 09:14:02.104 TRACE [http-nio-8080-exec-4] org.hibernate.orm.jdbc.bind - binding parameter [1] as [VARCHAR] - [user@example.com]",
                "2026-10-01 09:14:02.319 ERROR [http-nio-8080-exec-4] c.e.s.UserService - Failed to load user 4711",
                "java.lang.NullPointerException: Cannot invoke \"User.getEmail()\" because \"user\" is null",
                "\tat com.example.service.UserService.sendWelcomeEmail(UserService.java:128)",
                "\tat com.example.web.UserController.create(UserController.java:64)",
                "2026-10-01 09:14:02.320  WARN [scheduling-1] c.e.s.CleanupJob - 3 stale sessions removed",
                "2026-10-01 09:14:03.010 DEBUG [http-nio-8080-exec-7] org.hibernate.SQL - ",
                "    insert", "    into", "        orders (user_id, total, id)", "    values", "        (?, ?, ?)",
                "2026-10-01 09:14:03.011 TRACE [http-nio-8080-exec-7] org.hibernate.orm.jdbc.bind - binding parameter [1] as [BIGINT] - [4711]",
                "2026-10-01 09:14:03.012 TRACE [http-nio-8080-exec-7] org.hibernate.orm.jdbc.bind - binding parameter [2] as [NUMERIC] - [129.99]",
                "2026-10-01 09:14:03.013 TRACE [http-nio-8080-exec-7] org.hibernate.orm.jdbc.bind - binding parameter [3] as [BIGINT] - [90815]",
            )
            dir.resolve("hibernate.log").writeText(hibernate.joinToString(nl) + nl)

            val esc = "\u001B"
            val prefix = "$esc[36mdocker-compose$esc[0m | $esc[90m"
            val docker = listOf(
                "${prefix}2026-10-01T09:14:00Z$esc[0m app-1  | $esc[32m[INFO]$esc[0m Server started on port 8080",
                "${prefix}2026-10-01T09:14:01Z$esc[0m app-1  | $esc[33m[WARN]$esc[0m Connection pool 80% full",
                "${prefix}2026-10-01T09:14:02Z$esc[0m app-1  | $esc[38;5;196m[ERROR]$esc[0m Query timeout after 30s",
                "${prefix}2026-10-01T09:14:02Z$esc[0m app-1  | $esc[38;2;255;128;0m[TRUECOLOR]$esc[0m payload rendered rgb(255,128,0)",
                "${prefix}2026-10-01T09:14:03Z$esc[0m app-1  | $esc[1;31mjava.sql.SQLException: timeout$esc[0m",
                "${prefix}2026-10-01T09:14:03Z$esc[0m app-1  | $esc[2m\tat com.example.db.QueryRunner.run(QueryRunner.java:88)$esc[0m",
                "${prefix}2026-10-01T09:14:04Z$esc[0m app-2  | $esc[32m[INFO]$esc[0m healthcheck OK",
            )
            dir.resolve("docker.log").writeText(docker.joinToString(nl) + nl)

            val lines = bigLogLines.get()
            if (lines > 0) {
                val levels = listOf("DEBUG", "INFO", "WARN", "INFO", "DEBUG", "ERROR", "INFO", "DEBUG")
                val loggers = listOf("c.e.s.UserService", "c.e.s.OrderService", "o.h.SQL", "c.e.web.UserController", "c.e.s.CleanupJob")
                dir.resolve("big.log").bufferedWriter(Charsets.UTF_8, 1 shl 20).use { w ->
                    for (i in 0 until lines) {
                        val ts = "2026-10-01 %02d:%02d:%02d.000".format(9 + i / 60000, (i % 60000) / 1000, i % 60)
                        val lvl = levels[i % 8]
                        w.write("$ts $lvl [http-nio-8080-exec-${i % 16 + 1}] ${loggers[i % 5]} - Request $i processed in ${i % 40}ms$nl")
                        if (lvl == "ERROR") {
                            w.write("java.lang.NullPointerException: Cannot invoke \"User.getEmail()\" because \"user\" is null$nl")
                            w.write("\tat com.example.service.UserService.sendWelcomeEmail(UserService.java:128)$nl")
                            w.write("\tat com.example.web.UserController.create(UserController.java:64)$nl")
                        }
                    }
                }
                logger.lifecycle("big.log: ${dir.resolve("big.log").length()} bytes")
            }
        }
    }
}
