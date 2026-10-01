// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

apiValidation {
    ignoredProjects.add("app")
}

allprojects {
    group = "com.github.SevcanUcan.android-file-logger"
    version = providers.gradleProperty("VERSION_NAME").orElse("1.0.0-SNAPSHOT").get()
}
