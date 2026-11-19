package io.github.mayueanyou.lifetracker.places

import io.github.mayueanyou.lifetracker.data.LookupRow
import io.github.mayueanyou.lifetracker.data.PlaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private fun lookup(lat: Double, lon: Double, name: String?) =
        Places.key("spot", lat, lon).let { LookupRow(it, "spot", name, false, 0) }

    @Test fun aNameStaysWhenTheCentreDrifts() {
        val named = lookup(43.0380, -76.1300, "Life Sciences Complex")
        val places = Places(emptyList(), mapOf(named.key to named))
        // ~15 m away: another 11 m square, the same place.
        assertEquals("Life Sciences Complex", places.stayName(43.03812, -76.13018)?.name)
        assertFalse(places.missing("spot", 43.03812, -76.13018))
        // 200 m away is somewhere else, still to be looked up.
        assertNull(places.stayName(43.0398, -76.1300))
        assertTrue(places.missing("spot", 43.0398, -76.1300))
    }

    @Test fun aNamedSpotBeatsANothingFoundOneNextToIt() {
        val nothing = lookup(43.03812, -76.13018, null)
        val named = lookup(43.0380, -76.1300, "Life Sciences Complex")
        val places = Places(emptyList(), mapOf(nothing.key to nothing, named.key to named))
        assertEquals("Life Sciences Complex", places.stayName(43.03812, -76.13018)?.name)
    }

    @Test fun yourNameWins() {
        val named = lookup(43.0380, -76.1300, "Life Sciences Complex")
        val places = Places(listOf(PlaceRow("My lab", 43.0380, -76.1300, 60.0, id = 1)), mapOf(named.key to named))
        assertEquals("My lab", places.stayName(43.03812, -76.13018)?.name)
    }
}
