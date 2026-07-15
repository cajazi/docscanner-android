package com.dev.docscannerpdf.domain.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterOutputFileNameTest {

    @Test
    fun twoOutputsForTheSameFilterNeverShareAFilename() {
        // Front and Back rendering the same filter concurrently (same millisecond) was the
        // real-world collision: names must differ even for back-to-back calls.
        assertNotEquals(
            filterOutputFileName(DocumentFilter.ENHANCE),
            filterOutputFileName(DocumentFilter.ENHANCE)
        )
    }

    @Test
    fun rapidlyGeneratingManyNamesProducesNoDuplicates() {
        val names = (1..1000).map { filterOutputFileName(DocumentFilter.ENHANCE) }

        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun frontAndBackUsingSameFilterCannotShareAnOutputPath() {
        val frontName = filterOutputFileName(DocumentFilter.SEPIA)
        val backName = filterOutputFileName(DocumentFilter.SEPIA)

        assertNotEquals(frontName, backName)
    }

    @Test
    fun filenameRetainsSafeReadableFilterIdentifier() {
        DocumentFilter.CATALOG.forEach { filter ->
            val name = filterOutputFileName(filter)
            assertTrue(
                "$name must embed the readable identifier",
                name.startsWith("filter-${filter.name.lowercase()}-")
            )
            assertTrue("$name must be a jpg", name.endsWith(".jpg"))
            // A plain, path-safe filename: no separators, spaces, or display-name punctuation
            // (the identifier comes from the enum NAME, never the "B&W"-style display name).
            assertTrue(
                "$name must be path-safe",
                name.matches(Regex("filter-[a-z]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"))
            )
        }
    }
}
