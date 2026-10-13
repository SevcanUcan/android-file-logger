import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.filelogger"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
        name.set("Android FileLogger")
        description.set("Reliable structured file logging for Android")
        url.set("https://github.com/SevcanUcan/android-file-logger")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("SevcanUcan")
                name.set("Sevcan Ucan")
                url.set("https://github.com/SevcanUcan")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/SevcanUcan/android-file-logger.git")
            developerConnection.set("scm:git:ssh://git@github.com/SevcanUcan/android-file-logger.git")
            url.set("https://github.com/SevcanUcan/android-file-logger")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/SevcanUcan/android-file-logger/issues")
        }
    }
}

dependencies {
    api(project(":filelogger-core"))
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
