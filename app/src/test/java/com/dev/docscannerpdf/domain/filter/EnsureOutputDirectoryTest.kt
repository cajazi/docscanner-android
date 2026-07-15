package com.dev.docscannerpdf.domain.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

class EnsureOutputDirectoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsMissingDirectory() {
        val target = File(temporaryFolder.root, "id_card_filtered")

        assertTrue(ensureOutputDirectory(target))
        assertTrue(target.isDirectory)
    }

    @Test
    fun succeedsWhenDirectoryAlreadyExists() {
        val target = temporaryFolder.newFolder("already_there")

        assertTrue(ensureOutputDirectory(target))
    }

    @Test
    fun failsWhenPathIsARegularFile() {
        val file = temporaryFolder.newFile("not_a_directory")

        assertFalse(ensureOutputDirectory(file))
    }

    @Test
    fun repeatedCallsRemainSuccessful() {
        val target = File(temporaryFolder.root, "repeated")

        repeat(5) {
            assertTrue("call ${it + 1} must succeed", ensureOutputDirectory(target))
        }
    }

    @Test
    fun backToBackCallersNeverTreatAnAlreadyCreatedDirectoryAsFailure() {
        // The front/back race shape: caller A creates the directory, caller B's mkdirs()
        // then returns false — B must still succeed because a valid directory exists.
        val target = File(temporaryFolder.root, "raced")

        assertTrue("first caller creates", ensureOutputDirectory(target))
        assertFalse("second caller's mkdirs is a no-op", target.mkdirs())
        assertTrue("second caller must still succeed", ensureOutputDirectory(target))
    }

    @Test
    fun concurrentCallersAllSucceed() {
        val target = File(temporaryFolder.root, "concurrent")
        val threadCount = 8
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        val threads = (1..threadCount).map {
            Thread {
                start.await()
                results.add(ensureOutputDirectory(target))
            }.also(Thread::start)
        }
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(threadCount, results.size)
        assertTrue("all $threadCount concurrent callers must succeed", results.all { it })
        assertTrue(target.isDirectory)
    }
}
