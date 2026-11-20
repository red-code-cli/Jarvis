package com.jarvis.harness.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProbeTest {

    @Test
    fun `probe runs once per project and engine version`() {
        val cache = CapabilityProbeCache()
        var calls = 0
        val probe = { calls++; VisualTestResult.AVAILABLE }

        val first = cache.probeOnce("project-1", "1.14.1", probe)
        val second = cache.probeOnce("project-1", "1.14.1", probe)

        assertTrue(first.available)
        assertEquals(first, second)
        assertEquals("probe must run exactly once per key", 1, calls)
        assertEquals(1, cache.size)
    }

    @Test
    fun `a new engine version re-probes`() {
        val cache = CapabilityProbeCache()
        var calls = 0
        val probe = { calls++; VisualTestResult.unavailable("no chrome") }

        cache.probeOnce("project-1", "1.14.1", probe)
        cache.probeOnce("project-1", "1.15.0", probe)

        assertEquals(2, calls)
    }

    @Test
    fun `a different project re-probes`() {
        val cache = CapabilityProbeCache()
        var calls = 0
        val probe = { calls++; VisualTestResult.AVAILABLE }

        cache.probeOnce("project-1", "1.14.1", probe)
        cache.probeOnce("project-2", "1.14.1", probe)

        assertEquals(2, calls)
    }

    @Test
    fun `cached returns the stored verdict and null before probing`() {
        val cache = CapabilityProbeCache()
        assertNull(cache.cached("p", "v"))
        val result = cache.probeOnce("p", "v") { VisualTestResult.unavailable("no module") }
        assertEquals(result, cache.cached("p", "v"))
        assertFalse(result.available)
        assertEquals("unavailable", result.label)
    }

    @Test
    fun `a throwing probe degrades to unavailable instead of crashing`() {
        val cache = CapabilityProbeCache()
        val result = cache.probeOnce("p", "v") { error("boom") }
        assertFalse(result.available)
        assertTrue(result.reason.contains("boom"))
    }

    @Test
    fun `available label is the exact word the prompt tells the agent`() {
        assertEquals("available", VisualTestResult.AVAILABLE.label)
    }
}
