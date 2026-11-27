package com.mobiledivecontrol.core

import com.mobiledivecontrol.core.HousingSealTransitions.Location
import com.mobiledivecontrol.core.HousingSealTransitions.Observation
import com.mobiledivecontrol.core.HousingSealTransitions.Vacuum
import kotlin.test.Test
import kotlin.test.assertEquals

class HousingSealTransitionsTest {
    @Test
    fun `disconnect maps each validated observation to the historical snapshot and B01`() {
        val expected = listOf("S01", "S02", "S03", "S04", "S05", "S06")
        val observations = Location.entries.flatMap { location ->
            Vacuum.entries.map { Observation(location, it) }
        }
        observations.forEachIndexed { index, observation ->
            val disconnected = HousingSealTransitions.disconnected(observation)
            assertEquals(expected[index], disconnected.snapshot.name)
            assertEquals(observation, disconnected.snapshot.observation)
            assertEquals(HousingSealTransitions.Banner.B01, disconnected.banner)
        }
    }

    @Test
    fun `all 36 reconnect candidates follow the specification`() {
        val expected = listOf(
            "B00 B04 B05 B00 B06 B07",
            "B04 B04 B05 B06 B06 B07",
            "B00 B04 B03 B08 B08 B10",
            "B00 B13 B14 B00 B06 B07",
            "B14 B13 B14 B06 B06 B07",
            "B09 B09 B09 B08 B08 B08",
        )
        val observations = Location.entries.flatMap { location ->
            Vacuum.entries.map { Observation(location, it) }
        }
        observations.forEachIndexed { row, previous ->
            val snapshot = HousingSealTransitions.disconnected(previous).snapshot
            observations.forEachIndexed { column, current ->
                assertEquals(expected[row].split(' ')[column],
                    HousingSealTransitions.reconnect(snapshot, current).name, "$previous to $current")
            }
        }
    }

    @Test
    fun `connected vacuum and depth transitions use live entry and resurfacing rules`() {
        val expected = listOf(
            "B00 B04 B05 B00 B06 B10",
            "B04 B04 B05 B00 B06 B10",
            "B00 B04 B03 B00 B06 B10",
            "B00 B04 B14 B00 B06 B07",
            "B14 B13 B14 B06 B06 B07",
            "B09 B09 B09 B08 B08 B08",
        )
        val observations = Location.entries.flatMap { location ->
            Vacuum.entries.map { Observation(location, it) }
        }
        observations.forEachIndexed { row, previous ->
            observations.forEachIndexed { column, current ->
                assertEquals(expected[row].split(' ')[column],
                    HousingSealTransitions.connected(previous, current).name, "$previous to $current")
            }
        }
    }
}
