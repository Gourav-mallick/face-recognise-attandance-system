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
