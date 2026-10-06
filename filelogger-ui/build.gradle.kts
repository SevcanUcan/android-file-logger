import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.filelogger.ui"
    compileSdk = 36

    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "filelogger-ui"
                pom { name.set("Android FileLogger UI"); description.set("Compose log viewer with search and level filters"); url.set("https://github.com/SevcanUcan/android-file-logger") }
            }
        }
        repositories { maven { name = "test"; url = rootProject.layout.buildDirectory.dir("maven-repo").get().asFile.toURI() } }
    }
}

dependencies {
    api(project(":filelogger"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    testImplementation(libs.junit)
}
