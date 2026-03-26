package dev.sankofa.sdk.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The single Room database instance for the Sankofa SDK.
 * Uses a double-checked locking singleton so it is safe to call from multiple threads.
 */
@Database(
    entities = [EventEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class AppDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        private const val DB_NAME = "sankofa_events.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room
                    .databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    .fallbackToDestructiveMigration() // SDK events are transient; safe to drop on schema change
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** Clears the singleton – used in unit tests to get a fresh in-memory DB. */
        internal fun clearInstance() {
            INSTANCE = null
        }
    }
}
