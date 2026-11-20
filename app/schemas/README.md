# Exported Room schemas

These JSON files are written by the Room annotation processor during a build
(`room.schemaLocation` in `app/build.gradle.kts`). They are build inputs for the
instrumented migration test `app/src/androidTest/.../MemoryDatabaseMigrationTest.kt`.

Commit every generated file. The migration test needs the *previous* schema version to
create the old database, and Room does not regenerate an old version after the entity
changes, so a schema that is never committed can never be migrated or tested.

When you change a Room entity:

1. Bump `MEMORY_DATABASE_VERSION` in `MemoryDatabase.kt`.
2. Add an explicit `Migration(old, new)` to `MemoryDatabase.MIGRATIONS`.
3. Build once so the new `N.json` appears here, then commit it.
