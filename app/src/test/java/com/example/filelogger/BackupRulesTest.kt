package com.example.filelogger

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {

    @Test
    fun `legacy backup rules exclude logger data`() {
        assertExclusions(
            File("src/main/res/xml/backup_rules.xml"),
            expectedCopies = 1
        )
    }

    @Test
    fun `android 12 backup rules exclude logger data from cloud and transfer`() {
        assertExclusions(
            File("src/main/res/xml/data_extraction_rules.xml"),
            expectedCopies = 2
        )
    }

    private fun assertExclusions(file: File, expectedCopies: Int) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val exclusions = document.getElementsByTagName("exclude")
        val paths = buildList {
            repeat(exclusions.length) { index ->
                val node = exclusions.item(index)
                if (node.attributes.getNamedItem("domain")?.nodeValue == "file") {
                    add(node.attributes.getNamedItem("path")?.nodeValue.orEmpty())
                }
            }
        }
        listOf("logs/", "log_exports/", "pending_logs/").forEach { path ->
            assertTrue(
                "$path must be excluded $expectedCopies time(s) in ${file.name}: $paths",
                paths.count { it == path } == expectedCopies
            )
        }
    }
}
