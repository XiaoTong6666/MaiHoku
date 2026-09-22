plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.spotless)
}

fun runGitCommand(vararg args: String): String? =
    runCatching {
        val process = ProcessBuilder(*args)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { output }
        output
    }.getOrNull()

val gitCommitId = runGitCommand("git", "rev-parse", "--short", "HEAD") ?: "unknown"
val gitCommitCount = runGitCommand("git", "rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

extra["gitCommitId"] = gitCommitId
extra["gitCommitCount"] = gitCommitCount

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX

    java {
        target("**/src/*/java/**/*.java")
        targetExclude("**/build/**")

        palantirJavaFormat()
        importOrder()
        removeUnusedImports()
        formatAnnotations()
    }

    kotlin {
        target("**/src/*/kotlin/**/*.kt", "**/src/*/java/**/*.kt")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_max-line-length" to "disabled",
            ),
        )
    }
}

tasks.register("format") {
    dependsOn("spotlessApply")
    group = "formatting"
    description = "Formats the code using Spotless"
}
