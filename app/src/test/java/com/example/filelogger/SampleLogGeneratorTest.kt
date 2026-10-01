package com.example.filelogger

import com.filelogger.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLogGeneratorTest {
    @Test
    fun `generate emits debug info warning and throwable error`() {
        val logger = RecordingLogger()
        SampleLogGenerator.generate(logger)
        assertEquals(listOf("Debug event generated"), logger.debug)
        assertEquals(listOf("Info event generated"), logger.info)
        assertEquals(listOf("Warning event generated"), logger.warning)
        assertEquals("Error event generated", logger.errors.single().first)
        assertTrue(logger.errors.single().second is IllegalStateException)
    }

    @Test
    fun `stress emits requested records and final error`() {
        val logger = RecordingLogger()
        SampleLogGenerator.stress(logger, count = 20)
        assertEquals(20, logger.debug.size)
        assertEquals("record-0", logger.debug.first())
        assertEquals("record-19", logger.debug.last())
        assertEquals("stress-final", logger.errors.single().first)
    }

    private class RecordingLogger : Logger {
        val debug = mutableListOf<String>()
        val warning = mutableListOf<String>()
        val info = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()
        override fun d(tag: String, msg: String) { debug += msg }
        override fun i(tag: String, msg: String) { info += msg }
        override fun w(tag: String, msg: String) { warning += msg }
        override fun e(tag: String, msg: String, tr: Throwable?) { errors += msg to tr }
    }
}
