package dev.sankofa.sdk.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for the [EventEntity] table.
 * All methods are suspend functions – they MUST be called from a coroutine.
 */
@Dao
internal interface EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)

    /** Returns the [limit] oldest unprocessed events, preserving insertion order. */
    @Query("SELECT * FROM events_queue ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getOldestEvents(limit: Int): List<EventEntity>

    /** Deletes all events with the given IDs (successful uploads). */
    @Query("DELETE FROM events_queue WHERE id IN (:ids)")
    suspend fun deleteEvents(ids: List<Long>)

    /** Returns the total number of queued events for threshold checks. */
    @Query("SELECT COUNT(*) FROM events_queue")
    suspend fun countEvents(): Int
}
