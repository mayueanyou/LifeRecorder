package io.github.mayueanyou.lifetracker.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * A place you named yourself. Always wins over anything looked up. Two
 * places may share a name -- two cafés of one chain -- so each has an id.
 */
@Entity(tableName = "place")
data class PlaceRow(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    @Query("DELETE FROM place WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE place SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)

    @Query("SELECT * FROM lookup") suspend fun lookups(): List<LookupRow>
    @Upsert suspend fun upsertLookup(row: LookupRow)
}
