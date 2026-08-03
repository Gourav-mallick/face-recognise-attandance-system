package com.digitaledu.selfieattendance.utility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodLockLogicTest {

    fun isPeriodLocked(
        selectedCourseId: String,
        selectedSubjectType: String,
        existingCourseId: String,
        existingSubjectType: String
    ): Boolean {
        val isSameCourse = selectedCourseId.isNotBlank() && existingCourseId == selectedCourseId
        val isCompulsoryLock = selectedSubjectType.equals("Compulsory", true) || existingSubjectType.equals("Compulsory", true)
        return isSameCourse || isCompulsoryLock
    }

    fun calculateElectiveAbsentCount(totalAssigned: Int, scannedPresent: Int): Int {
        return (totalAssigned - scannedPresent).coerceAtLeast(0)
    }

    @Test
    fun testElectiveAfterElectiveDifferentCourseId_isNotLocked() {
        val locked = isPeriodLocked(
            selectedCourseId = "COURSE_6", // Infrastructure & Project Financing (cpId 23)
            selectedSubjectType = "Elective",
            existingCourseId = "COURSE_5", // Risk in Financial Services (cpId 22)
            existingSubjectType = "Elective"
        )
        assertFalse("Different elective subjects (courseId 5 vs 6) in the same period should remain OPEN", locked)
    }

    @Test
    fun testElectiveAfterElectiveSameCourseId_isLocked() {
        val locked = isPeriodLocked(
            selectedCourseId = "COURSE_5",
            selectedSubjectType = "Elective",
            existingCourseId = "COURSE_5",
            existingSubjectType = "Elective"
        )
        assertTrue("Same elective subject (courseId 5) already submitted should be LOCKED", locked)
    }

    @Test
    fun testCompulsoryAfterElective_isLocked() {
        val locked = isPeriodLocked(
            selectedCourseId = "COURSE_MATH",
            selectedSubjectType = "Compulsory",
            existingCourseId = "COURSE_5",
            existingSubjectType = "Elective"
        )
        assertTrue("Compulsory subject after Elective subject in the same period should be LOCKED", locked)
    }

    @Test
    fun testElectiveAfterCompulsory_isLocked() {
        val locked = isPeriodLocked(
            selectedCourseId = "COURSE_5",
            selectedSubjectType = "Elective",
            existingCourseId = "COURSE_MATH",
            existingSubjectType = "Compulsory"
        )
        assertTrue("Elective subject after Compulsory subject in the same period should be LOCKED", locked)
    }

    @Test
    fun testCompulsoryAfterCompulsory_isLocked() {
        val locked = isPeriodLocked(
            selectedCourseId = "COURSE_ENGLISH",
            selectedSubjectType = "Compulsory",
            existingCourseId = "COURSE_MATH",
            existingSubjectType = "Compulsory"
        )
        assertTrue("Compulsory subject after Compulsory subject in the same period should be LOCKED", locked)
    }

    @Test
    fun testElectiveAbsentCountCalculation() {
        val absent = calculateElectiveAbsentCount(totalAssigned = 10, scannedPresent = 2)
        assert(absent == 8) { "Expected 8 absent students for elective out of 10 assigned" }
    }
}
