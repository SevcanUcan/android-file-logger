import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
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
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        name.set("Android FileLogger OkHttp")
        description.set("Privacy-safe OkHttp logging integration")
        url.set("https://github.com/SevcanUcan/android-file-logger")
        licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT"); distribution.set("repo") } }
        developers {
            developer { id.set("SevcanUcan"); name.set("Sevcan Ucan"); url.set("https://github.com/SevcanUcan") }
        }
        scm {
            connection.set("scm:git:git://github.com/SevcanUcan/android-file-logger.git")
            developerConnection.set("scm:git:ssh://git@github.com/SevcanUcan/android-file-logger.git")
            url.set("https://github.com/SevcanUcan/android-file-logger")
        }
        issueManagement { system.set("GitHub"); url.set("https://github.com/SevcanUcan/android-file-logger/issues") }
    }
}

dependencies {
    api(project(":filelogger"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
