package com.jarvis.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the update decision table: fail-open, downgrade block, mandatory force
 * and the never-hide-optional rule (an update keeps showing until the user actually updates).
 */
class UpdateDecisionTest {

    private fun manifest(
        latest: Int,
        mandatory: Boolean = false,
        minSupported: Int = 0,
    ) = UpdateManifest(
        latestVersionCode = latest,
        latestVersionName = "1.16.0",
        minSupportedVersionCode = minSupported,
        apkUrl = "https://example.com/jarvis.apk",
        apkSha256 = "a".repeat(64),
        apkSizeBytes = 1000L,
        releaseNotes = "",
        mandatory = mandatory,
        publishedAt = "",
    )

    @Test
    fun noManifestIsFailOpen() {
        assertEquals(UpdateAction.NONE, UpdateDecision.decide(161, null, 0))
    }

    @Test
    fun sameOrOlderLatestIsNeverShown() {
        assertEquals(UpdateAction.NONE, UpdateDecision.decide(161, manifest(161), 0))
        assertEquals(UpdateAction.NONE, UpdateDecision.decide(161, manifest(160), 0))
    }

    @Test
    fun optionalWhenNewerAndNotMandatory() {
        assertEquals(UpdateAction.OPTIONAL, UpdateDecision.decide(161, manifest(162), 0))
    }

    @Test
    fun mandatoryForcesEvenWhenNewer() {
        assertEquals(UpdateAction.FORCE, UpdateDecision.decide(161, manifest(162, mandatory = true), 0))
    }

    @Test
    fun belowMinSupportedForcesEvenWhenNotMandatory() {
        assertEquals(UpdateAction.FORCE, UpdateDecision.decide(150, manifest(162, minSupported = 160), 0))
    }

    @Test
    fun minSupportedZeroMeansNoFloor() {
        assertEquals(UpdateAction.OPTIONAL, UpdateDecision.decide(161, manifest(162, minSupported = 0), 0))
    }

    @Test
    fun skippedVersionNeverHidesOptionalUpdate() {
        // "Later" must NOT hide the update: it keeps appearing until the user actually updates.
        assertEquals(UpdateAction.OPTIONAL, UpdateDecision.decide(161, manifest(162), 162))
        assertEquals(UpdateAction.FORCE, UpdateDecision.decide(161, manifest(162, mandatory = true), 162))
    }

    @Test
    fun skippedOlderThanLatestStillOptional() {
        // User skipped 162, server now offers 163 -> show again.
        assertEquals(UpdateAction.OPTIONAL, UpdateDecision.decide(161, manifest(163), 162))
    }
}
