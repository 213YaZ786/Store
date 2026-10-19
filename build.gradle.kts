// Ported from Files (YAZ/Files at HEAD, build.gradle.kts), itself from Calculator (github.com/213YaZ786/Calculator, build.gradle.kts)
plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere. AGP 9 has built-in Kotlin, and applying this plugin
    // to a module is now an error. Kept here with apply false only to pin the
    // Kotlin Gradle plugin version on the build classpath, as Google's own
    // AGP 9 example does.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Screens compared pixel for pixel, as SMS's build has it (Roborazzi's README, https://github.com/takahirom/roborazzi).
    alias(libs.plugins.roborazzi) apply false
}
