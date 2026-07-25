# Android Room Database Schema Migration & Data Loss Prevention Guide

> **Author**: Senior Android Engineer  
> **Topic**: Preventing Local Data Loss During Database Schema Updates in Production  
> **Target Framework**: Android Room Persistence Library (Kotlin)

---

## 1. Problem Overview & Root Cause Analysis

### What Happened?
When you release **Version 1** of an Android app, Room creates an SQLite database on the user's device matching your `v1` schema definitions. 

When you update the app to **Version 2** and modify any Room `@Entity` (adding columns, changing data types, creating new tables, removing columns), Room detects a database version mismatch or schema mismatch on startup.

### Why Data Lost Occurs:
1. **Unregistered Version Bump / Missing Migration Path**:
   If you change the DB `version = 2` without providing an explicit `Migration(1, 2)` object, Room throws a runtime crash:
   ```text
   java.lang.IllegalStateException: A migration from 1 to 2 was required but not found. Please provide a Migration in the builder or call fallbackToDestructiveMigration in the builder...
   ```
2. **`fallbackToDestructiveMigration()` Called**:
   If `.fallbackToDestructiveMigration()` is enabled in `AppDatabase.kt`, Room will **DROP ALL TABLES** and re-create empty ones. The app stops crashing, but **ALL USER DATA IS WIPED (ATTENDANCE, LOCAL CACHE, TEACHER/STUDENT RECORDS)**.
3. **Manual App Data Clearing**:
   Users or developers clear app storage manually to get past the crash, resulting in total data loss.

---

## 2. The Solution Architecture

To update the database schema **WITHOUT wiping user data**, you must implement **Explicit Room Database Migrations**.

```
                   ┌───────────────────────────────────┐
                   │    App Upgrade (v1 -> v2)         │
                   └─────────────────┬─────────────────┘
                                     │
                        Does DB Schema Change?
                                     │
                   ┌─────────────────┴─────────────────┐
                   │ YES                               │
                   ▼                                   ▼
       ┌────────────────────────┐         ┌────────────────────────┐
       │ WITH MIGRATIONS        │         │ WITHOUT MIGRATIONS     │
       │ (Migration(1, 2))      │         │ (Destructive Migration)│
       └───────────┬────────────┘         └───────────┬────────────┘
                   │                                   │
                   ▼                                   ▼
        Executes SQL ALTER TABLE            DROPS ALL TABLES & DATA
      Preserves Existing User Data            DATA LOST FOREVER ❌
         Data Safe & Intact ✅
```

---

## 3. Step-by-Step Implementation Guide

### Step 1: Remove `fallbackToDestructiveMigration()` in Production
Open `app/src/main/java/com/example/login/db/dao/AppDatabase.kt` and update the builder to remove destructive migration fallback in production releases:

```kotlin
// BEFORE (DO NOT USE IN PRODUCTION):
val instance = Room.databaseBuilder(
    context.applicationContext,
    AppDatabase::class.java,
    "app_database"
)
.fallbackToDestructiveMigration() // ❌ Wipes all data on schema mismatch
.build()
```

---

### Step 2: Define Version Bump & Migration Strategy

Whenever you modify any `@Entity`, perform the following steps:

1. Increase `version` in `@Database(...)` annotation (e.g., from `version = 1` to `version = 2`).
2. Write a `Migration(fromVersion, toVersion)` object executing SQL commands to transform the schema.
3. Register the migration using `.addMigrations(...)`.

---

## 4. Practical Migration Scenarios & Code Examples

### Scenario A: Adding a New Column to an Existing Table
**Use Case**: You added a `phone_number` string column to the `Student` entity in Version 2.

```kotlin
// Define the migration from v1 to v2
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Execute SQL ALTER TABLE statement
        db.execSQL("ALTER TABLE `student` ADD COLUMN `phone_number` TEXT DEFAULT NULL")
    }
}
```

---

### Scenario B: Adding a Brand New Entity / Table
**Use Case**: You added a new entity class (e.g., `NotificationEntity`) to `AppDatabase`.

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Execute SQL CREATE TABLE statement matching Room's exact schema requirement
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `notifications` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `message` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

---

### Scenario C: Modifying/Deleting Columns or Changing Data Types
SQLite does not support `ALTER TABLE DROP COLUMN` or modifying column types directly in older SQLite engines. Room requires a 4-step migration pattern:

1. Create a temporary table with the target schema.
2. Copy existing data from the old table to the temporary table.
3. Drop the old table.
4. Rename the temporary table to the original table name.

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Step 1: Create temp table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `student_new` (
                `student_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `roll_no` TEXT NOT NULL
            )
        """.trimIndent())

        // Step 2: Copy data
        db.execSQL("""
            INSERT INTO `student_new` (`student_id`, `name`, `roll_no`)
            SELECT `student_id`, `name`, `roll_no` FROM `student`
        """.trimIndent())

        // Step 3: Drop old table
        db.execSQL("DROP TABLE `student` ")

        // Step 4: Rename temp table to old table name
        db.execSQL("ALTER TABLE `student_new` RENAME TO `student` ")
    }
}
```

---

## 5. Integrating Migrations into `AppDatabase.kt`

Here is how your updated `AppDatabase.kt` companion object will look:

```kotlin
package com.example.login.db.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.login.db.entity.*

@Database(
    entities = [
        Student::class,
        Teacher::class,
        Course::class,
        Subject::class,
        Class::class,
        CoursePeriod::class,
        Session::class,
        Attendance::class,
        ActiveClassCycle::class,
        TeacherClassMap::class,
        StudentSchedule::class,
        PendingScheduleEntity::class,
        Institute::class,
        PendingTeacherAllocationEntity::class,
        SchoolPeriod::class,
        AttendanceCode::class
    ],
    version = 2, // 👈 Incremented version from 1 to 2
    exportSchema = true // 👈 Set to true to generate schema JSON files for verification
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun studentsDao(): StudentsDao
    abstract fun attendanceDao(): AttendanceDao
    // ... rest of your DAOs ...

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 🔹 Define Migration from Version 1 to 2
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Example SQL for adding a column or table update:
                // db.execSQL("ALTER TABLE `attendance` ADD COLUMN `sync_timestamp` INTEGER DEFAULT 0 NOT NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                // 🔹 Add migrations here to prevent data loss
                .addMigrations(MIGRATION_1_2)
                // 🔹 Safety rule: Enable destructive migration ONLY on downgrade (e.g. testing older build over newer)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
```

---

## 6. Best Practices to Prevent Data Loss

| Practice | Details | Benefit |
| :--- | :--- | :--- |
| **1. Export Schema** | Set `exportSchema = true` in `@Database` and configure schema location in `build.gradle.kts`. | Automatically generates `.json` schema files per version for verification. |
| **2. AutoMigrations (Room 2.4+)** | For simple changes (adding column, creating table), use `@AutoMigration(from = 1, to = 2)`. | No manual SQL required for basic column/table additions. |
| **3. Offline Data Sync Safety** | Ensure pending local records (e.g., un-synced attendance) are synced to AWS S3 / backend before major app releases. | Dual layer protection: local DB safe, backend cloud DB safe. |
| **4. Testing Migrations** | Use `MigrationTestHelper` in Android Instrumented Unit Tests. | Tests migrations on a real SQLite DB before releasing to Google Play Store / Users. |
| **5. Downgrade Guard** | Use `.fallbackToDestructiveMigrationOnDowngrade()` instead of unconditional destructive migration. | Protects users upgrading forward while handling internal dev testing. |

---

## 7. AutoMigration Feature (Room 2.4+)

If using Room `2.4.0` or newer, Room can write migrations for simple schema additions automatically!

```kotlin
@Database(
    version = 2,
    entities = [Student::class, Attendance::class, ...],
    autoMigrations = [
        AutoMigration(from = 1, to = 2)
    ],
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() { ... }
```
> **Note**: For AutoMigrations to work, `exportSchema = true` must be configured in `app/build.gradle.kts`.

---

## Summary Action Plan for Your Team

1. **Remove `.fallbackToDestructiveMigration()`** from `AppDatabase.kt`.
2. **Increment DB version** to `2` when schema changes are introduced.
3. **Write `MIGRATION_1_2`** with exact `db.execSQL(...)` statements.
4. **Register `addMigrations(MIGRATION_1_2)`** in `Room.databaseBuilder`.
5. Test upgrading an installed v1 app to v2—all user data will be preserved without errors or app data clearing!
