package io.github.mayueanyou.liferecorder.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** A place you named yourself. Always wins over anything looked up. */
@Entity(tableName = "place")
data class PlaceRow(
    @PrimaryKey val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
)

/**
 * A name looked up once for a spot and kept, so a place costs one request,
 * ever. [kind] is "building" (OpenStreetMap containment or nearest) or
 * "street" (where you parked). [name] is null when nothing was found.
 */
@Entity(tableName = "lookup")
data class LookupRow(
    @PrimaryKey val key: String,
    val kind: String,
    val name: String?,
    val exact: Boolean,
    val fetchedAt: Long,
)

@Dao
interface PlaceDao {
    @Query("SELECT * FROM place ORDER BY name") suspend fun places(): List<PlaceRow>
    @Upsert suspend fun upsert(place: PlaceRow)
    @Query("DELETE FROM place WHERE name = :name") suspend fun delete(name: String)

    @Query("SELECT * FROM lookup") suspend fun lookups(): List<LookupRow>
    @Upsert suspend fun upsertLookup(row: LookupRow)
}
