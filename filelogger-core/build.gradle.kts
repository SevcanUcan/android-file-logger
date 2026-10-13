import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

project.plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    project.the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().download.set(false)
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(11)

    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.filelogger.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        name.set("FileLogger Multiplatform Core")
        description.set("Portable structured logging core for Android, JVM, iOS, and Wasm")
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
