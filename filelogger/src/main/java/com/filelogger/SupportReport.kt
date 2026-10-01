package com.filelogger

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class SupportReportOptions(
    val userNote: String? = null,
    val sections: Map<String, String> = emptyMap(),
    val includeLogs: Boolean = true,
    val includeBreadcrumbs: Boolean = true,
    val redactor: LogRedactor = LogRedactor.DEFAULT_SENSITIVE,
    val maxSections: Int = DEFAULT_MAX_SECTIONS,
    val maxSectionBytes: Int = DEFAULT_MAX_SECTION_BYTES,
    val maxSupplementBytes: Int = DEFAULT_MAX_SUPPLEMENT_BYTES
) {
    init {
        require(maxSections > 0) { "maxSections must be greater than zero" }
        require(maxSectionBytes > 0) { "maxSectionBytes must be greater than zero" }
        require(maxSupplementBytes > 0) { "maxSupplementBytes must be greater than zero" }
    }

    companion object {
        const val DEFAULT_MAX_SECTIONS = 16
        const val DEFAULT_MAX_SECTION_BYTES = 128 * 1024
        const val DEFAULT_MAX_SUPPLEMENT_BYTES = 1024 * 1024
    }
}

internal data class SupportReportEnvironment(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val androidSdk: Int,
    val androidRelease: String,
    val manufacturer: String,
    val model: String,
    val supportedAbis: List<String>,
    val locale: String
) {
    companion object {
        fun capture(context: Context, packageName: String): SupportReportEnvironment {
            val packageInfo = context.packageManager.getPackageInfoCompat(packageName)
            return SupportReportEnvironment(
                packageName = packageName,
                versionName = packageInfo.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                androidSdk = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                supportedAbis = Build.SUPPORTED_ABIS.orEmpty().toList(),
                locale = Locale.getDefault().toLanguageTag()
            )
        }

        @Suppress("DEPRECATION")
        private fun android.content.pm.PackageManager.getPackageInfoCompat(
            packageName: String
        ): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            getPackageInfo(packageName, 0)
        }
    }
}

internal object SupportReportBundle {
    private val validSectionName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun metadataEntries(
        options: SupportReportOptions,
        environment: SupportReportEnvironment,
        generatedAtMillis: Long,
        breadcrumbs: List<Breadcrumb> = emptyList(),
        reportId: String = UUID.randomUUID().toString()
    ): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        var supplementBytes = 0
        require(options.sections.size <= options.maxSections) {
            "support report sections exceed maxSections"
        }

        options.userNote
            ?.takeIf { it.isNotBlank() }
            ?.let { note ->
                val redacted = options.redactor.redact(note)
                supplementBytes += validateSize("userNote", redacted, options)
                validateTotalSize(supplementBytes, options)
                entries["user-note.txt"] = redacted.ensureTrailingNewline()
            }

        options.sections.toSortedMap().forEach { (name, content) ->
            require(validSectionName.matches(name)) {
                "section name must match ${validSectionName.pattern}"
            }
            val redacted = options.redactor.redact(content)
            supplementBytes += validateSize(name, redacted, options)
            validateTotalSize(supplementBytes, options)
            entries["sections/$name.txt"] = redacted.ensureTrailingNewline()
        }

        val reportBreadcrumbs = if (options.includeBreadcrumbs) {
            breadcrumbs.map { breadcrumb ->
                breadcrumb.copy(
                    message = options.redactor.redact(breadcrumb.message),
                    attributes = breadcrumb.attributes.mapValues { (_, value) ->
                        options.redactor.redact(value)
                    }
                )
            }
        } else {
            emptyList()
        }
        if (reportBreadcrumbs.isNotEmpty()) {
            entries["breadcrumbs.json"] = gson.toJson(reportBreadcrumbs) + "\n"
        }

        val manifest = SupportReportManifest(
            schemaVersion = 1,
            reportId = reportId,
            generatedAtMillis = generatedAtMillis,
            includesLogs = options.includeLogs,
            includesUserNote = entries.containsKey("user-note.txt"),
            breadcrumbCount = reportBreadcrumbs.size,
            sections = options.sections.keys.sorted(),
            environment = environment
        )
        entries["support-report.json"] = gson.toJson(manifest) + "\n"
        return entries
    }

    private fun validateSize(
        name: String,
        content: String,
        options: SupportReportOptions
    ): Int {
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        require(bytes <= options.maxSectionBytes) {
            "$name exceeds maxSectionBytes"
        }
        return bytes
    }

    private fun validateTotalSize(bytes: Int, options: SupportReportOptions) {
        require(bytes <= options.maxSupplementBytes) {
            "support report supplemental content exceeds maxSupplementBytes"
        }
    }

    private fun String.ensureTrailingNewline(): String =
        if (endsWith('\n')) this else "$this\n"

    private data class SupportReportManifest(
        val schemaVersion: Int,
        val reportId: String,
        val generatedAtMillis: Long,
        val includesLogs: Boolean,
        val includesUserNote: Boolean,
        val breadcrumbCount: Int,
        val sections: List<String>,
        val environment: SupportReportEnvironment
    )
}
