package com.jarvis.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Room schema version. It is the single source of truth for the version in the
 * [Database] annotation and for the instrumented migration test, so the two can never
 * drift. Bump it and add a matching [Migration] to [MemoryDatabase.MIGRATIONS] whenever
 * an entity changes.
 */
const val MEMORY_DATABASE_VERSION = 1

@Database(
    entities = [MemoryEntity::class],
    version = MEMORY_DATABASE_VERSION,
    exportSchema = true,
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DATABASE_NAME = "jarvis_memory.db"

        /**
         * Explicit migrations between stored schema versions. Version 1 is the baseline
         * shipped by Jarvis 1.9.5, so there is nothing to migrate yet.
         *
         * This list is deliberately non-empty-capable instead of using
         * `fallbackToDestructiveMigration()`: that fallback silently deleted every saved
         * memory during an app update that changed the schema. A schema change must now
         * ship a [Migration] here, and the app fails loudly instead of losing user data.
         * The instrumented test in app/src/androidTest exercises this path.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
