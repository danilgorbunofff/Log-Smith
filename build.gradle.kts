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

// Local dev compiles against the installed WebStorm (fast, exact target IDE).
// CI has no local IDE, so it pins a public community platform instead (§8.1).
val onCi = System.getenv("CI") != null

dependencies {
    intellijPlatform {
        if (onCi) {
            intellijIdeaCommunity("2025.2")
        } else {
            local("C:/Program Files/JetBrains/WebStorm 2025.3.2")
        }
    }
    testImplementation("junit:junit:4.13.2")
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
        ides { recommended() }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.7.1"
    }
}
