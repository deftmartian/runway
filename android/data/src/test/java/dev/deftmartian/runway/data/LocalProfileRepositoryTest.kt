package dev.deftmartian.runway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalProfileRepositoryTest {
    @Test
    fun `heart rate profile accepts ordered bounded floors`() {
        assertNull(validateHeartRateProfile(profile()))
    }

    @Test
    fun `heart rate profile rejects a non increasing floor sequence`() {
        assertEquals(
            LocalProfileIssue.HEART_RATE_ZONES_NOT_INCREASING,
            validateHeartRateProfile(profile(zone3FloorBpm = 120, zone4FloorBpm = 120)),
        )
    }

    @Test
    fun `heart rate profile rejects zone five above selected maximum`() {
        assertEquals(
            LocalProfileIssue.ZONE_FIVE_ABOVE_MAXIMUM,
            validateHeartRateProfile(profile(maxHeartRateBpm = 175, zone5FloorBpm = 180)),
        )
    }

    @Test
    fun `heart rate profile keeps age optional but bounded when present`() {
        assertEquals(
            LocalProfileIssue.AGE_OUT_OF_RANGE,
            validateHeartRateProfile(profile(ageYears = 17)),
        )
        assertNull(validateHeartRateProfile(profile(ageYears = null)))
    }

    private fun profile(
        ageYears: Int? = 38,
        maxHeartRateBpm: Int = 190,
        zone3FloorBpm: Int = 140,
        zone4FloorBpm: Int = 160,
        zone5FloorBpm: Int = 180,
    ) = LocalHeartRateProfile(
        sexForEstimate = SexForEstimate.NotSpecified,
        ageYears = ageYears,
        source = HeartRateSettingsSource.Custom,
        maxHeartRateBpm = maxHeartRateBpm,
        zone2FloorBpm = 120,
        zone3FloorBpm = zone3FloorBpm,
        zone4FloorBpm = zone4FloorBpm,
        zone5FloorBpm = zone5FloorBpm,
    )
}
