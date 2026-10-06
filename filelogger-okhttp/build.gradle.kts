import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.filelogger.okhttp"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

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
                artifactId = "filelogger-okhttp"
                pom { name.set("Android FileLogger OkHttp"); description.set("Privacy-safe OkHttp logging integration"); url.set("https://github.com/SevcanUcan/android-file-logger") }
            }
        }
        repositories { maven { name = "test"; url = rootProject.layout.buildDirectory.dir("maven-repo").get().asFile.toURI() } }
    }
}

dependencies {
    api(project(":filelogger"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
