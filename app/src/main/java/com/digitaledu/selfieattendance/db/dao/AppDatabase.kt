package com.digitaledu.selfieattendance.db.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.db.entity.Student
import com.digitaledu.selfieattendance.db.entity.Teacher
import com.digitaledu.selfieattendance.db.entity.Course
import com.digitaledu.selfieattendance.db.entity.Subject
import com.digitaledu.selfieattendance.db.entity.Class
import com.digitaledu.selfieattendance.db.entity.CoursePeriod
import com.digitaledu.selfieattendance.db.entity.Session
import com.digitaledu.selfieattendance.db.entity.ActiveClassCycle
import com.digitaledu.selfieattendance.db.entity.TeacherClassMap
import com.digitaledu.selfieattendance.db.entity.StudentSchedule
import com.digitaledu.selfieattendance.db.entity.PendingScheduleEntity
import com.digitaledu.selfieattendance.db.entity.Institute
import com.digitaledu.selfieattendance.db.entity.PendingTeacherAllocationEntity
import com.digitaledu.selfieattendance.db.entity.SchoolPeriod
import com.digitaledu.selfieattendance.db.entity.AttendanceCode
import com.digitaledu.selfieattendance.db.entity.ProgramConfig
import com.digitaledu.selfieattendance.db.entity.IncompleteSession





@Database(entities = [
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
    AttendanceCode::class,
    ProgramConfig::class,
    IncompleteSession::class
    ],
    version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun studentsDao(): StudentsDao
    abstract fun teachersDao(): TeachersDao
    abstract fun courseDao(): CourseDao
    abstract fun subjectDao(): SubjectDao
    abstract fun classDao(): ClassDao
    abstract fun coursePeriodDao(): CoursePeriodDao
    abstract fun sessionDao(): SessionDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun activeClassCycleDao(): ActiveClassCycleDao
    abstract fun teacherClassMapDao(): TeacherClassMapDao

    abstract fun studentScheduleDao(): StudentScheduleDao

    abstract fun pendingScheduleDao(): PendingScheduleDao

    abstract fun instituteDao(): InstituteDao

    abstract fun pendingTeacherAllocationDao(): PendingTeacherAllocationDao

    abstract fun schoolPeriodDao(): SchoolPeriodDao
    abstract fun attendanceCodeDao(): AttendanceCodeDao
    abstract fun programConfigDao(): ProgramConfigDao
    abstract fun incompleteSessionDao(): IncompleteSessionDao




    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_config` (
                        `title` TEXT NOT NULL,
                        `program` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `schoolId` TEXT NOT NULL,
                        `syear` TEXT NOT NULL,
                        `programConfId` TEXT NOT NULL,
                        PRIMARY KEY(`title`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Additive migration only: existing sessions, attendance, registrations,
         * configuration and embeddings are left untouched.
         */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `incomplete_sessions` (
                        `sessionId` TEXT NOT NULL,
                        `instId` TEXT NOT NULL,
                        `teacherId` TEXT NOT NULL,
                        `teacherName` TEXT,
                        `classIds` TEXT NOT NULL,
                        `classNames` TEXT NOT NULL,
                        `schoolPeriodId` TEXT NOT NULL,
                        `currentStage` TEXT NOT NULL,
                        `sessionDate` TEXT NOT NULL,
                        `startTime` TEXT NOT NULL,
                        `markedStudentCount` INTEGER NOT NULL,
                        `sourceDeviceGuid` TEXT NOT NULL,
                        `sessionJson` TEXT NOT NULL,
                        `attendancesJson` TEXT NOT NULL,
                        `syncStatus` TEXT NOT NULL,
                        `recordStatus` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
