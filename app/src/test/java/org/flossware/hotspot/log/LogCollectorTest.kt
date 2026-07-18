package org.flossware.hotspot.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogCollectorTest {

    @Before
    fun setUp() {
        LogCollector.clear()
    }

    @After
    fun tearDown() {
        LogCollector.clear()
    }

    @Test
    fun `log entries are stored in order`() {
        LogCollector.i("Tag1", "First")
        LogCollector.d("Tag2", "Second")
        LogCollector.w("Tag3", "Third")

        val entries = LogCollector.getEntries()
        assertEquals(3, entries.size)
        assertEquals("First", entries[0].message)
        assertEquals("Second", entries[1].message)
        assertEquals("Third", entries[2].message)
    }

    @Test
    fun `log levels are captured correctly`() {
        LogCollector.i("T", "info")
        LogCollector.d("T", "debug")
        LogCollector.w("T", "warn")
        LogCollector.e("T", "error")

        val entries = LogCollector.getEntries()
        assertEquals("I", entries[0].level)
        assertEquals("D", entries[1].level)
        assertEquals("W", entries[2].level)
        assertEquals("E", entries[3].level)
    }

    @Test
    fun `tags are captured correctly`() {
        LogCollector.i("MyTag", "message")

        val entries = LogCollector.getEntries()
        assertEquals(1, entries.size)
        assertEquals("MyTag", entries[0].tag)
    }

    @Test
    fun `ring buffer drops oldest entries when full`() {
        for (i in 1..LogCollector.MAX_ENTRIES + 50) {
            LogCollector.i("T", "msg$i")
        }

        val entries = LogCollector.getEntries()
        assertEquals(LogCollector.MAX_ENTRIES, entries.size)
        // Oldest 50 should be gone; first entry should be msg51
        assertEquals("msg51", entries[0].message)
        // Last entry should be the most recent
        assertEquals("msg${LogCollector.MAX_ENTRIES + 50}", entries[entries.size - 1].message)
    }

    @Test
    fun `clear removes all entries`() {
        LogCollector.i("T", "message1")
        LogCollector.i("T", "message2")
        assertEquals(2, LogCollector.size)

        LogCollector.clear()
        assertEquals(0, LogCollector.size)
        assertTrue(LogCollector.getEntries().isEmpty())
    }

    @Test
    fun `getEntries returns a snapshot copy`() {
        LogCollector.i("T", "before")
        val snapshot = LogCollector.getEntries()

        // Adding more entries after snapshot should not affect it
        LogCollector.i("T", "after")

        assertEquals(1, snapshot.size)
        assertEquals(2, LogCollector.getEntries().size)
    }

    @Test
    fun `error with throwable includes exception message`() {
        LogCollector.e("T", "Something failed", RuntimeException("root cause"))

        val entries = LogCollector.getEntries()
        assertEquals(1, entries.size)
        assertEquals("E", entries[0].level)
        assertTrue(entries[0].message.contains("root cause"))
    }

    @Test
    fun `format produces expected output`() {
        val entry = LogEntry(
            timestamp = 0L,
            tag = "TestTag",
            level = "I",
            message = "Hello world",
        )
        val formatted = entry.format()
        assertTrue(formatted.contains("I/TestTag: Hello world"))
    }

    @Test
    fun `size tracks entry count`() {
        assertEquals(0, LogCollector.size)
        LogCollector.i("T", "one")
        assertEquals(1, LogCollector.size)
        LogCollector.d("T", "two")
        assertEquals(2, LogCollector.size)
    }

    @Test
    fun `concurrent access does not crash`() {
        val threads = (1..10).map { threadId ->
            Thread {
                for (i in 1..100) {
                    LogCollector.i("Thread$threadId", "msg$i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // All 1000 entries should be present (10 threads * 100 messages = exactly MAX_ENTRIES)
        assertEquals(LogCollector.MAX_ENTRIES, LogCollector.size)
    }
}
