package com.digitaledu.selfieattendance.utility

import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.CoursePeriod
import com.digitaledu.selfieattendance.db.entity.Student

/** Resolves the students who belong to the selected attendance roster. */
object AttendanceRosterResolver {

    suspend fun forSelection(
        db: AppDatabase,
        classIds: List<String>,
        selectedCourseIds: List<String>,
        validCoursePeriods: List<CoursePeriod>
    ): List<Student> {
        val classStudents = db.studentsDao().getStudentsByClasses(classIds.distinct())
        if (!isElectiveSelection(db, selectedCourseIds)) {
            // Preserve the existing compulsory/unknown-subject behaviour.
            return classStudents
        }

        val selectedCourses = selectedCourseIds.toSet()
        val validCpIds = validCoursePeriods
            .filter { it.classId in classIds && it.courseId in selectedCourses }
            .map { it.cpId }
            .toSet()

        val eligibleStudentIds = db.studentScheduleDao().getAll()
            .asSequence()
            .filter { it.courseId in selectedCourses && it.cpId in validCpIds }
            .map { it.studentId }
            .toSet()

        return classStudents.filter { it.studentId in eligibleStudentIds }
    }

    suspend fun forSessionClass(
        db: AppDatabase,
        sessionId: String,
        classId: String
    ): List<Student> {
        val session = db.sessionDao().getSessionById(sessionId)
        val attendances = db.attendanceDao().getAttendancesForClass(sessionId, classId)

        val selectedCourseIds = buildSet {
            session?.subjectId
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.let(::addAll)

            attendances
                .flatMap { it.courseId.orEmpty().split(',') }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .let(::addAll)
        }.toList()

        val validCoursePeriods = db.coursePeriodDao().getAllCoursePeriods().filter {
            it.classId == classId && it.courseId in selectedCourseIds
        }

        return forSelection(
            db = db,
            classIds = listOf(classId),
            selectedCourseIds = selectedCourseIds,
            validCoursePeriods = validCoursePeriods
        )
    }

    private suspend fun isElectiveSelection(
        db: AppDatabase,
        selectedCourseIds: List<String>
    ): Boolean {
        val distinctIds = selectedCourseIds.filter(String::isNotBlank).distinct()
        if (distinctIds.isEmpty()) return false

        val coursesById = db.courseDao().getAllCourses().associateBy { it.courseId }
        val subjectTypesByCourse = db.courseDao().getCourseDetailsForIds(distinctIds)
            .groupBy { it.courseId }
            .mapValues { (_, rows) -> rows.firstNotNullOfOrNull { it.subjectType?.takeIf(String::isNotBlank) } }

        // Unknown/null types remain compulsory so existing attendance behaviour is unchanged.
        return distinctIds.all { courseId ->
            val type = subjectTypesByCourse[courseId] ?: coursesById[courseId]?.subjectType
            type?.trim()?.equals("elective", ignoreCase = true) == true
        }
    }
}
