// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

apiValidation {
    ignoredProjects.add("app")
    ignoredClasses.add("com.filelogger.ui.ComposableSingletons\$FileLoggerViewerKt")
}

rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().download.set(false)
}

allprojects {
    group = if (providers.environmentVariable("JITPACK").isPresent) {
        "com.github.SevcanUcan.android-file-logger"
    } else {
        "io.github.sevcanucan"
    }
    version = providers.gradleProperty("VERSION_NAME").orElse("1.0.0-SNAPSHOT").get()
}

tasks.register("verifyMavenLocalPublications") {
    group = "verification"
    description = "Validates Maven Central artifacts in the local Maven repository."

    doLast {
        val publicationVersion = providers.gradleProperty("VERSION_NAME").get()
        val groupPath = project.group.toString().replace('.', File.separatorChar)
        val localRepository = System.getProperty("maven.repo.local")
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".m2/repository")
        val repositoryRoot = File(localRepository, groupPath)
        val requireSignatures = providers.gradleProperty("verifySignatures").orNull == "true"
        val primaryModules = mapOf(
            "filelogger" to "aar",
            "filelogger-core" to "jar",
            "filelogger-okhttp" to "aar",
            "filelogger-remote" to "aar",
            "filelogger-crashlytics" to "aar",
            "filelogger-otel" to "aar",
            "filelogger-ui" to "aar"
        )
        val portablePlatformModules = mapOf(
            "filelogger-core-android" to "aar",
            "filelogger-core-jvm" to "jar",
            "filelogger-core-wasm-js" to "klib"
        )
        val applePlatformModules = mapOf(
            "filelogger-core-iosarm64" to "klib",
            "filelogger-core-iossimulatorarm64" to "klib"
        )
        val supportsApplePublications = !System.getProperty("os.name").contains("Linux", ignoreCase = true)
        val platformModules = portablePlatformModules + if (supportsApplePublications) applePlatformModules else emptyMap()
        val modules = primaryModules + platformModules
        val expectedProjectDependencies = mapOf(
            "filelogger" to "filelogger-core",
            "filelogger-okhttp" to "filelogger",
            "filelogger-remote" to "filelogger",
            "filelogger-crashlytics" to "filelogger",
            "filelogger-otel" to "filelogger",
            "filelogger-ui" to "filelogger"
        )

        modules.forEach { (artifactId, packaging) ->
            val artifactDirectory = File(repositoryRoot, "$artifactId/$publicationVersion")
            val baseName = "$artifactId-$publicationVersion"
            val requiredFiles = listOf(
                File(artifactDirectory, "$baseName.$packaging"),
                File(artifactDirectory, "$baseName.pom"),
                File(artifactDirectory, "$baseName.module"),
                File(artifactDirectory, "$baseName-sources.jar"),
                File(artifactDirectory, "$baseName-javadoc.jar")
            )
            requiredFiles.forEach { artifact ->
                check(artifact.isFile && artifact.length() > 0L) {
                    "Missing or empty Maven artifact: ${artifact.absolutePath}"
                }
                if (requireSignatures) {
                    val signature = File("${artifact.absolutePath}.asc")
                    check(signature.isFile && signature.length() > 0L) {
                        "Missing or empty signature: ${signature.absolutePath}"
                    }
                }
            }

            val pomFile = File(artifactDirectory, "$baseName.pom")
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(pomFile)
            fun requiredPomValue(elementName: String): String {
                val value = document.getElementsByTagName(elementName).item(0)?.textContent?.trim().orEmpty()
                check(value.isNotEmpty()) { "POM $artifactId is missing <$elementName>" }
                return value
            }

            check(requiredPomValue("groupId") == project.group.toString())
            check(requiredPomValue("artifactId") == artifactId)
            check(requiredPomValue("version") == publicationVersion)
            listOf("name", "description", "url", "license", "developer", "scm").forEach(::requiredPomValue)

            expectedProjectDependencies[artifactId]?.let { expectedDependency ->
                val dependencyArtifactIds = document.getElementsByTagName("dependency")
                    .let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
                    .mapNotNull { dependency ->
                        dependency.childNodes.let { children ->
                            (0 until children.length)
                                .map { children.item(it) }
                                .firstOrNull { it.nodeName == "artifactId" }
                                ?.textContent
                                ?.trim()
                        }
                    }
                check(expectedDependency in dependencyArtifactIds) {
                    "POM $artifactId is missing project dependency $expectedDependency"
                }
            }
        }

        logger.lifecycle(
            "Verified ${primaryModules.size} primary and ${platformModules.size} platform Maven publications at $repositoryRoot"
        )
    }
}
