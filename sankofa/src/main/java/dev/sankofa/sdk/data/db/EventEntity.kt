package dev.sankofa.sdk.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single event stored in the local Room database.
 * The [payload] is the full JSON string representation of the event.
 * Events are written immediately on [Dispatchers.IO] and batched for upload.
 */
@Entity(tableName = "events_queue")
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
)
