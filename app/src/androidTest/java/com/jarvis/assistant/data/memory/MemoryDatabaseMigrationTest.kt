package com.jarvis.assistant.data.memory

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the stored Room schema migrates from the previous version to the current
 * one without dropping the user's saved memories.
 *
 * REQUIRES A DEVICE OR EMULATOR: this is an instrumented test, and it reads the exported
 * schema JSON from `app/schemas`, which the kapt `room.schemaLocation` step writes during
 * a build. It is intentionally not part of the PR "Compile check (no APK)" job, which only
 * compiles `main` and runs JVM unit tests. Run it with
 * `./gradlew :app:connectedDebugAndroidTest` after building once so that
 * `app/schemas/com.jarvis.assistant.data.memory.MemoryDatabase/1.json` exists and is
 * committed. When the database version is bumped, add the matching Migration to
 * `MemoryDatabase.MIGRATIONS` and this test will exercise the upgrade path.
 */
@RunWith(AndroidJUnit4::class)
class MemoryDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemoryDatabase::class.java,
    )

    @Test
    fun migratesPreviousVersionPreservingSavedMemories() {
        helper.createDatabase(DATABASE_NAME, PREVIOUS_VERSION).use { database ->
            database.execSQL(
                "INSERT INTO `memories` (`category`, `key`, `value`, `rawText`, `timestamp`) " +
                    "VALUES ('Notes', 'wifi-password', 'secret', 'remember the wifi password', 1)",
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            MEMORY_DATABASE_VERSION,
            true,
            *MemoryDatabase.MIGRATIONS,
        ).use { migrated ->
            migrated.query("SELECT `rawText` FROM `memories`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("remember the wifi password", cursor.getString(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "memory-migration-test.db"

        /** Oldest stored schema; kept at 1 so the same test covers every future upgrade. */
        const val PREVIOUS_VERSION = 1
    }
}
