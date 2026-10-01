package com.example.filelogger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filelogger.FileLogger
import com.filelogger.LogLevel
import com.filelogger.LoggerConfig
import com.filelogger.SupportReportOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class FileLoggerPrivacyInstrumentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        runCatching { FileLogger.shutdown() }
    }

    @Test
    fun structuredContextAndWriteTimeRedactionReachStoredLogs() {
        FileLogger.init(
            context,
            LoggerConfig.prod().copy(
                minimumLogLevel = LogLevel.DEBUG,
                sessionLoggingEnabled = false,
                logFolder = "privacy_test_logs"
            )
        )
        FileLogger.deleteAllLogs()
        FileLogger.putContext("user", "user@example.com")

        FileLogger.log(
            level = LogLevel.INFO,
            tag = "Checkout",
            message = "token=secret-token",
            attributes = mapOf("user" to "event@example.com", "order" to "A-1")
        )
        assertTrue(FileLogger.flush())

        val entry = FileLogger.readLogs().single()
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("A-1", entry.attributes["order"])
        assertFalse(entry.message.contains("secret-token"))
        assertFalse(entry.attributes.getValue("user").contains("event@example.com"))
    }

    @Test
    fun disablingCollectionDropsNewLogsAndCanDeleteExistingData() {
        FileLogger.init(
            context,
            LoggerConfig.dev().copy(
                sessionLoggingEnabled = false,
                logFolder = "consent_test_logs"
            )
        )
        FileLogger.deleteAllLogs()
        FileLogger.i("Consent", "before-disable")
        assertTrue(FileLogger.flush())
        assertEquals(1, FileLogger.readLogs().size)

        assertTrue(FileLogger.setCollectionEnabled(false))
        assertFalse(FileLogger.addBreadcrumb("must-not-be-collected"))
        FileLogger.e("Consent", "must-not-be-written")
        assertTrue(FileLogger.flush())
        assertEquals(1, FileLogger.readLogs().size)

        assertTrue(FileLogger.setCollectionEnabled(false, deleteExistingData = true))
        assertFalse(FileLogger.isCollectionEnabled())
        assertTrue(FileLogger.readLogs().isEmpty())
        assertTrue(FileLogger.breadcrumbs().isEmpty())
    }

    @Test
    fun supportReportContainsRedactedDiagnosticsAndEnvironment() {
        FileLogger.init(
            context,
            LoggerConfig.prod().copy(
                minimumLogLevel = LogLevel.DEBUG,
                sessionLoggingEnabled = false,
                logFolder = "support_report_test_logs"
            )
        )
        FileLogger.deleteAllLogs()
        FileLogger.i("Support", "token=log-secret")
        assertTrue(
            FileLogger.addBreadcrumb(
                message = "Opened owner@example.com",
                category = "navigation",
                attributes = mapOf("token" to "token=breadcrumb-secret")
            )
        )
        val output = File(context.cacheDir, "support-report-test.zip")
        output.delete()

        val report = FileLogger.createSupportReport(
            options = SupportReportOptions(
                userNote = "Contact owner@example.com",
                sections = mapOf("feature-flags" to "api_key=section-secret")
            ),
            outputFile = output
        )

        ZipFile(report).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("diagnostics.json" in names)
            assertTrue("support-report.json" in names)
            assertTrue("user-note.txt" in names)
            assertTrue("sections/feature-flags.txt" in names)
            assertTrue("breadcrumbs.json" in names)
            assertTrue(names.any { it.startsWith("app_log") })

            val completeText = names.joinToString("\n") { zip.readEntry(it) }
            assertFalse(completeText.contains("log-secret"))
            assertFalse(completeText.contains("owner@example.com"))
            assertFalse(completeText.contains("section-secret"))
            assertFalse(completeText.contains("breadcrumb-secret"))
            assertTrue(completeText.contains(context.packageName))
            assertTrue(completeText.contains("androidSdk"))
        }
    }

    @Test
    fun supportReportCanExcludeLogs() {
        FileLogger.init(
            context,
            LoggerConfig.dev().copy(
                sessionLoggingEnabled = false,
                logFolder = "support_report_without_logs_test"
            )
        )
        FileLogger.deleteAllLogs()
        FileLogger.i("Support", "must-not-be-exported")
        val output = File(context.cacheDir, "support-report-without-logs-test.zip")
        output.delete()

        val report = FileLogger.createSupportReport(
            options = SupportReportOptions(includeLogs = false),
            outputFile = output
        )

        ZipFile(report).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertFalse(names.any { it.startsWith("app_log") })
            assertTrue("diagnostics.json" in names)
            assertTrue("support-report.json" in names)
        }
    }

    private fun ZipFile.readEntry(name: String): String =
        getInputStream(getEntry(name)).bufferedReader().readText()
}
