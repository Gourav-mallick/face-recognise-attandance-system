package com.digitaledu.selfieattendance

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeacherInstituteMigrationTest {
    private lateinit var context: Context
    private val databaseName = "teacher-institute-migration-test.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationPreservesTeacherAndOtherDataAndSplitsLegacyMemberships() {
        openDatabase(version = 2, onCreate = { db ->
            db.execSQL(
                """
                CREATE TABLE teachers (
                    staffId TEXT NOT NULL,
                    staffName TEXT NOT NULL,
                    instId TEXT NOT NULL,
                    fingerType TEXT,
                    embedding TEXT,
                    PRIMARY KEY(staffId)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO teachers(staffId, staffName, instId, fingerType, embedding)
                VALUES('T101', 'Asha Sharma', 'I1, I2,I1', 'face', 'saved-embedding')
                """.trimIndent()
            )
            db.execSQL(
                "CREATE TABLE migration_sentinel(id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO migration_sentinel(id, value) VALUES('attendance', 'preserved')"
            )
        }).close()

        openDatabase(version = 3, onUpgrade = { db, oldVersion, newVersion ->
            assertEquals(2, oldVersion)
            assertEquals(3, newVersion)
            AppDatabase.MIGRATION_2_3.migrate(db)
        }).use { helper ->
            helper.writableDatabase.query(
                "SELECT staffName, embedding FROM teachers WHERE staffId = 'T101'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Asha Sharma", cursor.getString(0))
                assertEquals("saved-embedding", cursor.getString(1))
            }

            helper.writableDatabase.query(
                "SELECT instId FROM teacher_institute_map WHERE teacherId = 'T101' ORDER BY instId"
            ).use { cursor ->
                val instituteIds = mutableListOf<String>()
                while (cursor.moveToNext()) instituteIds += cursor.getString(0)
                assertEquals(listOf("I1", "I2"), instituteIds)
            }

            helper.writableDatabase.query(
                "SELECT value FROM migration_sentinel WHERE id = 'attendance'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserved", cursor.getString(0))
            }
        }
    }

    private fun openDatabase(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) = onUpgrade(db, oldVersion, newVersion)
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase
        }
    }
}
