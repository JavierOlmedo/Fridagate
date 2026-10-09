// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ── Version bump tasks ───────────────────────────────────────────────────────
// Run them from Android Studio's Gradle panel (Tasks > versioning) or with
// ./gradlew bumpPatch. They rewrite appVersion in gradle.properties.
val versionFile: File = layout.projectDirectory.file("gradle.properties").asFile
val versionLine = Regex("""(?m)^appVersion=(\d+)\.(\d+)\.(\d+)[ \t]*$""")

listOf("Major", "Minor", "Patch").forEach { part ->
    tasks.register("bump$part") {
        group = "versioning"
        description = "Increases the ${part.lowercase()} number of appVersion in gradle.properties"
        doLast {
            val text = versionFile.readText()
            val match = versionLine.find(text)
                ?: throw GradleException("appVersion=X.Y.Z not found in gradle.properties")
            val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
            val next = when (part) {
                "Major" -> "${major + 1}.0.0"
                "Minor" -> "$major.${minor + 1}.0"
                else -> "$major.$minor.${patch + 1}"
            }
            versionFile.writeText(text.replaceRange(match.range, "appVersion=$next"))
            println("appVersion: $major.$minor.$patch -> $next")
        }
    }
}
