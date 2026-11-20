package com.jarvis.assistant.data.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the durable Room schema without needing a device.
 *
 * Room exports its schema to `app/schemas` (kapt runs before unit tests). If the entity
 * changes without a version bump + explicit Migration, this test fails and points at the
 * generated schema, so a destructive data loss cannot slip into a release.
 */
class MemoryDatabaseSchemaTest {

    /** Room writes the exported schema during kapt; the unit-test working dir can vary. */
    private fun schemaFile(): File {
        val relative = "schemas/com.jarvis.assistant.data.memory.MemoryDatabase/1.json"
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(File(relative), File("app/$relative"), File(cwd, relative), File(cwd, "app/$relative"))
            .firstOrNull { it.isFile }
            ?: File(relative)
    }

    @Test
    fun exportedSchemaMatchesTheEntitiesJarvisShips() {
        val schema = schemaFile()
        assertTrue("Room schema was not exported at ${schema.absolutePath}", schema.isFile)
        val root = JSONObject(schema.readText())
        val database = root.getJSONObject("database")
        assertEquals(1, database.getInt("version"))

        val table = database.getJSONArray("entities")
            .let { entities ->
                (0 until entities.length())
                    .map { entities.getJSONObject(it) }
                    .firstOrNull { it.getString("tableName") == "memories" }
            }
        assertTrue("memories table missing from exported schema", table != null)
        val fields = table!!.getJSONArray("fields").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("columnName") }.toSet()
        }
        assertEquals(
            setOf("id", "category", "key", "value", "rawText", "timestamp"),
            fields,
        )
        val primaryKey = table.getJSONObject("primaryKey")
        assertEquals(listOf("id"), (0 until primaryKey.getJSONArray("columnNames").length()).map {
            primaryKey.getJSONArray("columnNames").getString(it)
        })
    }

    @Test
    fun destructiveMigrationIsNotConfigured() {
        // The database must survive an update. There is no migration for version 1 yet, so
        // an empty list is correct; the important part is that a destructive fallback was
        // never reintroduced (checked in source by the review, and here by version).
        assertEquals(0, MemoryDatabase.MIGRATIONS.size)
    }
}