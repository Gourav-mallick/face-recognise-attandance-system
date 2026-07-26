package com.example.selfieAttendance.db.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.selfieAttendance.db.entity.Attendance
import com.example.selfieAttendance.db.entity.Student
import com.example.selfieAttendance.db.entity.Teacher
import com.example.selfieAttendance.db.entity.Course
import com.example.selfieAttendance.db.entity.Subject
import com.example.selfieAttendance.db.entity.Class
import com.example.selfieAttendance.db.entity.CoursePeriod
import com.example.selfieAttendance.db.entity.Session
import com.example.selfieAttendance.db.entity.ActiveClassCycle
import com.example.selfieAttendance.db.entity.TeacherClassMap
import com.example.selfieAttendance.db.entity.StudentSchedule
import com.example.selfieAttendance.db.entity.PendingScheduleEntity
import com.example.selfieAttendance.db.entity.Institute
import com.example.selfieAttendance.db.entity.PendingTeacherAllocationEntity
import com.example.selfieAttendance.db.entity.SchoolPeriod
import com.example.selfieAttendance.db.entity.AttendanceCode





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
    AttendanceCode::class
    ],
    version = 1, exportSchema = false)
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




    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    // 👇 Add this line
                .fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
