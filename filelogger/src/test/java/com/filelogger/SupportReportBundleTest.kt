package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportBundleTest {

    private val environment = SupportReportEnvironment(
        packageName = "com.example.app",
        versionName = "1.2.3",
        versionCode = 12,
        androidSdk = 35,
        androidRelease = "15",
        manufacturer = "Example",
        model = "Test Phone",
        supportedAbis = listOf("arm64-v8a"),
        locale = "tr-TR"
    )

    @Test
    fun `creates redacted note sections and manifest`() {
        val entries = SupportReportBundle.metadataEntries(
            options = SupportReportOptions(
                userNote = "Contact user@example.com token=secret-token",
                sections = mapOf("feature-flags" to "api_key=top-secret")
            ),
            environment = environment,
            generatedAtMillis = 42,
            breadcrumbs = listOf(
                Breadcrumb(
                    timestampMillis = 40,
                    category = "navigation",
                    message = "Opened user@example.com",
                    attributes = mapOf("token" to "token=secret-token")
                )
            ),
            reportId = "report-1"
        )

        assertFalse(entries.getValue("user-note.txt").contains("user@example.com"))
        assertFalse(entries.getValue("user-note.txt").contains("secret-token"))
        assertFalse(entries.getValue("sections/feature-flags.txt").contains("top-secret"))
        assertFalse(entries.getValue("breadcrumbs.json").contains("user@example.com"))
        assertFalse(entries.getValue("breadcrumbs.json").contains("secret-token"))
        val manifest = entries.getValue("support-report.json")
        assertTrue(manifest.contains("\"reportId\": \"report-1\""))
        assertTrue(manifest.contains("\"packageName\": \"com.example.app\""))
        assertTrue(manifest.contains("\"feature-flags\""))
        assertTrue(manifest.contains("\"breadcrumbCount\": 1"))
    }

    @Test
    fun `omits blank user note`() {
        val entries = SupportReportBundle.metadataEntries(
            options = SupportReportOptions(userNote = "  "),
            environment = environment,
            generatedAtMillis = 42
        )

        assertFalse(entries.containsKey("user-note.txt"))
        assertTrue(entries.getValue("support-report.json").contains("\"includesUserNote\": false"))
    }

    @Test
    fun `rejects unsafe section names`() {
        val error = runCatching {
            SupportReportBundle.metadataEntries(
                options = SupportReportOptions(sections = mapOf("../private" to "value")),
                environment = environment,
                generatedAtMillis = 42
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `enforces individual and aggregate content bounds`() {
        val individual = runCatching {
            SupportReportBundle.metadataEntries(
                options = SupportReportOptions(
                    sections = mapOf("large" to "12345"),
                    maxSectionBytes = 4
                ),
                environment = environment,
                generatedAtMillis = 42
            )
        }.exceptionOrNull()
        val aggregate = runCatching {
            SupportReportBundle.metadataEntries(
                options = SupportReportOptions(
                    userNote = "1234",
                    sections = mapOf("state" to "5678"),
                    maxSectionBytes = 8,
                    maxSupplementBytes = 7
                ),
                environment = environment,
                generatedAtMillis = 42
            )
        }.exceptionOrNull()

        assertEquals("large exceeds maxSectionBytes", individual?.message)
        assertEquals(
            "support report supplemental content exceeds maxSupplementBytes",
            aggregate?.message
        )
    }

    @Test
    fun `limits number of custom sections`() {
        val error = runCatching {
            SupportReportBundle.metadataEntries(
                options = SupportReportOptions(
                    sections = mapOf("one" to "1", "two" to "2"),
                    maxSections = 1
                ),
                environment = environment,
                generatedAtMillis = 42
            )
        }.exceptionOrNull()

        assertEquals("support report sections exceed maxSections", error?.message)
    }

    @Test
    fun `can exclude breadcrumbs`() {
        val entries = SupportReportBundle.metadataEntries(
            options = SupportReportOptions(includeBreadcrumbs = false),
            environment = environment,
            generatedAtMillis = 42,
            breadcrumbs = listOf(
                Breadcrumb(1, "user", "Tapped export")
            )
        )

        assertFalse(entries.containsKey("breadcrumbs.json"))
        assertTrue(entries.getValue("support-report.json").contains("\"breadcrumbCount\": 0"))
    }
}
