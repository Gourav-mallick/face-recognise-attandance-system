package com.digitaledu.selfieattendance.utility

import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.db.entity.SchoolPeriod
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive test suite executing end-to-end scenario testing against all dataset components
 * provided in data.md (Student Scheduling, Subject Instances, Periods, School List, Attendance Codes).
 */
class DataMdAttendanceSystemTest {

    // =========================================================================
    // RAW DATA FROM data.md
    // =========================================================================

    private val studentSchedulingDataInst1Json = """
    [
        {"studentId":"1","cpId":"7","courseId":"7","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"105"},
        {"studentId":"2","cpId":"7","courseId":"7","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"110"},
        {"studentId":"3","cpId":"7","courseId":"7","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"115"},
        {"studentId":"4","cpId":"7","courseId":"7","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"120"},
        {"studentId":"5","cpId":"7","courseId":"7","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"125"}
    ]
    """.trimIndent()

    private val studentSchedulingDataInst2Json = """
    [
        {"studentId":"425","cpId":"53","courseId":"40","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"2122"},
        {"studentId":"426","cpId":"53","courseId":"40","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"2123"},
        {"studentId":"427","cpId":"53","courseId":"40","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"2124"},
        {"studentId":"428","cpId":"53","courseId":"40","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"2125"},
        {"studentId":"429","cpId":"53","courseId":"40","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"2126"},
        {"studentId":"430","cpId":"53","courseId":"40","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"2127"},
        {"studentId":"431","cpId":"53","courseId":"40","scheduleStartDate":"2026-06-01","scheduleEndDate":"","scheduleId":"2128"}
    ]
    """.trimIndent()

    private val subjectInstancesDataJson = """
    [
        {"cpIds":"7","courseIds":"7","courseTitles":"Research Project-Practical","subjectIds":"7","courseShortName":"Research Project-Practical","subjectTitles":"Research Project","classIds":"1","classShortNames":"Msc Finance Part II#1#7","mpId":"5","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Compulsory"},
        {"cpIds":"18","courseIds":"1","courseTitles":"Portfolio Analysis and Management-Theory","subjectIds":"1","courseShortName":"Portfolio Analysis and Management-Theory","subjectTitles":"Portfolio Analysis and Management","classIds":"1","classShortNames":"Msc Finance Part II#1#18","mpId":"5","mpLongTitle":"TERM 1","teacherIds":",76,","subjectType":"Compulsory"},
        {"cpIds":"19","courseIds":"2","courseTitles":"Derivatives-Theory","subjectIds":"2","courseShortName":"Derivatives-Theory","subjectTitles":"Derivatives","classIds":"1","classShortNames":"Msc Finance Part II#1#19","mpId":"5","mpLongTitle":"TERM 1","teacherIds":",98,","subjectType":"Compulsory"},
        {"cpIds":"20","courseIds":"3","courseTitles":"Mergers, Acquisitions and Corporate Restructuring-Theory","subjectIds":"3","courseShortName":"Mergers, Acquisitions and Corporate Restructuring-Theory","subjectTitles":"Mergers, Acquisitions and Corporate Restructuring","classIds":"1","classShortNames":"Msc Finance Part II#1#20","mpId":"5","mpLongTitle":"TERM 1","teacherIds":",78,","subjectType":"Compulsory"},
        {"cpIds":"21","courseIds":"4","courseTitles":"Technical Analysis-Theory","subjectIds":"4","courseShortName":"Technical Analysis-Theory","subjectTitles":"Technical Analysis","classIds":"1","classShortNames":"Msc Finance Part II#1#21","mpId":"5","mpLongTitle":"TERM 1","teacherIds":",79,","subjectType":"Compulsory"},
        {"cpIds":"22","courseIds":"5","courseTitles":"Risk in Financial Services-Theory","subjectIds":"5","courseShortName":"Risk in Financial Services-Theory","subjectTitles":"Risk in Financial Services","classIds":"1","classShortNames":"Msc Finance Part II#1#22","mpId":"5","mpLongTitle":"TERM 1","teacherIds":",80,","subjectType":"Elective"},
        {"cpIds":"23","courseIds":"6","courseTitles":"Infrastructure and Project Financing-Theory","subjectIds":"6","courseShortName":"Infrastructure and Project Financing-Theory","subjectTitles":"Infrastructure and Project Financing","classIds":"1","classShortNames":"Msc Finance Part II#1#23","mpId":"5","mpLongTitle":"TERM 1","teacherIds":",81,","subjectType":"Elective"},
        {"cpIds":"24","courseIds":"13","courseTitles":"International Business-Theory","subjectIds":"13","courseShortName":"International Business-Theory","subjectTitles":"International Business","classIds":"3","classShortNames":"Third Year MMS#3#24","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Compulsory"},
        {"cpIds":"25","courseIds":"14","courseTitles":"Financial Markets and Institutions-Theory","subjectIds":"14","courseShortName":"Financial Markets and Institutions-Theory","subjectTitles":"Financial Markets and Institutions","classIds":"3","classShortNames":"Third Year MMS#3#25","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"26","courseIds":"15","courseTitles":"Corporate Valuation-Theory","subjectIds":"15","courseShortName":"Corporate Valuation-Theory","subjectTitles":"Corporate Valuation","classIds":"3","classShortNames":"Third Year MMS#3#26","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"27","courseIds":"16","courseTitles":"Security Analysis & Portfolio Management-Theory","subjectIds":"16","courseShortName":"Security Analysis & Portfolio Management-Theory","subjectTitles":"Security Analysis & Portfolio Management","classIds":"3","classShortNames":"Third Year MMS#3#27","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"28","courseIds":"17","courseTitles":"Derivatives and Risk Management-Theory","subjectIds":"17","courseShortName":"Derivatives and Risk Management-Theory","subjectTitles":"Derivatives and Risk Management","classIds":"3","classShortNames":"Third Year MMS#3#28","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"29","courseIds":"18","courseTitles":"Behavioural Finance-Theory","subjectIds":"18","courseShortName":"Behavioural Finance-Theory","subjectTitles":"Behavioural Finance","classIds":"3","classShortNames":"Third Year MMS#3#29","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"30","courseIds":"19","courseTitles":"Investment Banking and Alternate Investment Fund-Theory","subjectIds":"19","courseShortName":"Investment Banking and Alternate Investment Fund-Theory","subjectTitles":"Investment Banking and Alternate Investment Fund","classIds":"3","classShortNames":"Third Year MMS#3#30","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"31","courseIds":"20","courseTitles":"Competency-based HRM & Performance Management System-Theory","subjectIds":"20","courseShortName":"Competency-based HRM & Performance Management System-Theory","subjectTitles":"Competency-based HRM & Performance Management System","classIds":"3","classShortNames":"Third Year MMS#3#31","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"32","courseIds":"21","courseTitles":"Artificial Intelligence (AI) in Human Resource Management-Theory","subjectIds":"21","courseShortName":"Artificial Intelligence (AI) in Human Resource Management-Theory","subjectTitles":"Artificial Intelligence (AI) in Human Resource Management","classIds":"3","classShortNames":"Third Year MMS#3#32","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"33","courseIds":"22","courseTitles":"Compensation and Benefits-Theory","subjectIds":"22","courseShortName":"Compensation and Benefits-Theory","subjectTitles":"Compensation and Benefits","classIds":"3","classShortNames":"Third Year MMS#3#33","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"34","courseIds":"23","courseTitles":"HR Analytics-Theory","subjectIds":"23","courseShortName":"HR Analytics-Theory","subjectTitles":"HR Analytics","classIds":"3","classShortNames":"Third Year MMS#3#34","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"35","courseIds":"24","courseTitles":"HR Planning and Application of Technology in HR-Theory","subjectIds":"24","courseShortName":"HR Planning and Application of Technology in HR-Theory","subjectTitles":"HR Planning and Application of Technology in HR","classIds":"3","classShortNames":"Third Year MMS#3#35","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"},
        {"cpIds":"36","courseIds":"25","courseTitles":"Learning and Development-Theory","subjectIds":"25","courseShortName":"Learning and Development-Theory","subjectTitles":"Learning and Development","classIds":"3","classShortNames":"Third Year MMS#3#36","mpId":"7","mpLongTitle":"TERM 1","teacherIds":",82,","subjectType":"Elective"}
    ]
    """.trimIndent()

    private val schoolListJson = """
    [
        {"ID":"4","SYEAR":"2026","SHORT_NAME":"MHRD","TITLE":"MHRD","schoolHash":"a87ff679a2f3e71d9181a67b7542122c","grpSchoolIds":"4"},
        {"ID":"2","SYEAR":"2026","SHORT_NAME":"MMS Full Time","TITLE":"MMS Full Time","schoolHash":"c81e728d9d4c2f636f067f89cc14862c","grpSchoolIds":"2"},
        {"ID":"3","SYEAR":"2026","SHORT_NAME":"MMS Part Time","TITLE":"MMS Part Time","schoolHash":"eccbc87e4b5ce2fe28308fd9f2a7baf3","grpSchoolIds":"3"},
        {"ID":"1","SYEAR":"2026","SHORT_NAME":"MSc Finance","TITLE":"MSc Finance","schoolHash":"c4ca4238a0b923820dcc509a6f75849b","grpSchoolIds":"1"}
    ]
    """.trimIndent()

    private val attCodeInst1Json = """
    [
        {"atcId":"1","atcSchoolId":"1","atcLongName":"Present","atcShortName":"Present","atcCode":"P","atcIsMarkable":"Y"},
        {"atcId":"2","atcSchoolId":"1","atcLongName":"Absent","atcShortName":"Absent","atcCode":"A","atcIsMarkable":"Y"},
        {"atcId":"3","atcSchoolId":"1","atcLongName":"Exempted","atcShortName":"Exempted","atcCode":"E","atcIsMarkable":"Y"},
        {"atcId":"7","atcSchoolId":"1","atcLongName":"Late","atcShortName":"Late","atcCode":"L","atcIsMarkable":"Y"}
    ]
    """.trimIndent()

    private val attCodeInst2Json = """
    [
        {"atcId":"4","atcSchoolId":"2","atcLongName":"Present","atcShortName":"Present","atcCode":"P","atcIsMarkable":"Y"},
        {"atcId":"5","atcSchoolId":"2","atcLongName":"Absent","atcShortName":"Absent","atcCode":"A","atcIsMarkable":"Y"},
        {"atcId":"6","atcSchoolId":"2","atcLongName":"Exempted","atcShortName":"Exempted","atcCode":"E","atcIsMarkable":"Y"},
        {"atcId":"8","atcSchoolId":"2","atcLongName":"Late","atcShortName":"Late","atcCode":"L","atcIsMarkable":"Y"}
    ]
    """.trimIndent()

    // =========================================================================
    // SCENARIO TEST 1: Student Scheduling Data Parsing & Integrity
    // =========================================================================

    @Test
    fun testStudentSchedulingData_Institute1() {
        val jsonArray = JSONArray(studentSchedulingDataInst1Json)
        assertEquals(5, jsonArray.length())

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val studentId = obj.getString("studentId")
            val cpId = obj.getString("cpId")
            val courseId = obj.getString("courseId")
            val startDate = obj.getString("scheduleStartDate")

            assertEquals((i + 1).toString(), studentId)
            assertEquals("7", cpId)
            assertEquals("7", courseId)
            assertEquals("2026-06-01", startDate)
            assertTrue("Schedule ID must be non-empty", obj.getString("scheduleId").isNotEmpty())
        }
    }

    @Test
    fun testStudentSchedulingData_Institute2() {
        val jsonArray = JSONArray(studentSchedulingDataInst2Json)
        assertEquals(7, jsonArray.length())

        val expectedStudentIds = listOf("425", "426", "427", "428", "429", "430", "431")
        val expectedScheduleIds = listOf("2122", "2123", "2124", "2125", "2126", "2127", "2128")

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            assertEquals(expectedStudentIds[i], obj.getString("studentId"))
            assertEquals("53", obj.getString("cpId"))
            assertEquals("40", obj.getString("courseId"))
            assertEquals("2026-06-01", obj.getString("scheduleStartDate"))
            assertEquals(expectedScheduleIds[i], obj.getString("scheduleId"))
        }
    }

    // =========================================================================
    // SCENARIO TEST 2: Subject Instances & Teacher Class Mappings
    // =========================================================================

    @Test
    fun testSubjectInstancesData_ParsingAndTeacherExtraction() {
        val jsonArray = JSONArray(subjectInstancesDataJson)
        assertEquals(20, jsonArray.length())

        // Validate Compulsory vs Elective counts
        var compulsoryCount = 0
        var electiveCount = 0

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val cpId = obj.getString("cpIds")
            val courseId = obj.getString("courseIds")
            val teacherIdsRaw = obj.getString("teacherIds")
            val subjectType = obj.getString("subjectType")

            if (subjectType == "Compulsory") compulsoryCount++
            if (subjectType == "Elective") electiveCount++

            // Extract teacher ID list
            val teacherList = teacherIdsRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            assertTrue("Each subject instance must have at least one teacher ID", teacherList.isNotEmpty())
            assertTrue("Course ID must be non-empty", courseId.isNotEmpty())
            assertTrue("CP ID must be non-empty", cpId.isNotEmpty())
        }

        assertEquals(6, compulsoryCount) // cp 7, 18, 19, 20, 21, 24
        assertEquals(14, electiveCount)  // cp 22, 23, 25..36
    }

    @Test
    fun testSubjectInstancesData_ClassShortNamePattern() {
        val jsonArray = JSONArray(subjectInstancesDataJson)

        // Verify "ClassShortName#InstId#CpId" string structure pattern
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val classShortName = obj.getString("classShortNames")
            val cpId = obj.getString("cpIds")
            val classId = obj.getString("classIds")

            val parts = classShortName.split("#")
            assertEquals(3, parts.size)
            assertEquals(classId, parts[1])
            assertEquals(cpId, parts[2])
        }
    }

    // =========================================================================
    // SCENARIO TEST 3: School Period Time Resolver with data.md Periods
    // =========================================================================

    @Test
    fun testSchoolPeriodTimeResolver_Institute1Periods() {
        val inst1Periods = listOf(
            SchoolPeriod(spId = "10", spTitle = "Morning Session - MSc Finance - 2026 [7:00 AM to 11:59 AM]", spStartTime = "7:00 AM", spEndTime = "11:59 AM", spIstTime = "07:00:00", instId = "1"),
            SchoolPeriod(spId = "11", spTitle = "Afternoon Session - MSc Finance - 2026 [12:00 PM to 4:59 PM]", spStartTime = "12:00 PM", spEndTime = "4:59 PM", spIstTime = "12:00:00", instId = "1"),
            SchoolPeriod(spId = "12", spTitle = "Evening Session - MSc Finance - 2026 [5:00 PM to 9:00 PM]", spStartTime = "5:00 PM", spEndTime = "9:00 PM", spIstTime = "17:00:00", instId = "1")
        )

        // Morning resolution (08:30 AM)
        val morningRes = SchoolPeriodTimeResolver.resolveAutoPeriod(inst1Periods, "08:30")
        assertNotNull(morningRes)
        assertEquals("10", morningRes?.spId)

        // Afternoon resolution (14:15 / 02:15 PM)
        val afternoonRes = SchoolPeriodTimeResolver.resolveAutoPeriod(inst1Periods, "14:15")
        assertNotNull(afternoonRes)
        assertEquals("11", afternoonRes?.spId)

        // Evening resolution (18:45 / 06:45 PM)
        val eveningRes = SchoolPeriodTimeResolver.resolveAutoPeriod(inst1Periods, "18:45")
        assertNotNull(eveningRes)
        assertEquals("12", eveningRes?.spId)

        // Early morning before school (05:30 AM) -> Defaults to first period
        val earlyRes = SchoolPeriodTimeResolver.resolveAutoPeriod(inst1Periods, "05:30")
        assertNotNull(earlyRes)
        assertEquals("10", earlyRes?.spId)

        // Late night after school (22:00 / 10:00 PM) -> Defaults to last period
        val lateRes = SchoolPeriodTimeResolver.resolveAutoPeriod(inst1Periods, "22:00")
        assertNotNull(lateRes)
        assertEquals("12", lateRes?.spId)
    }

    @Test
    fun testSchoolPeriodTitleCanonicalization() {
        assertEquals("Morning Session", AttendanceSyncMerger.canonicalReportPeriodTitle("Morning Session - MSc Finance - 2026 [7:00 AM to 11:59 AM]"))
        assertEquals("Afternoon Session", AttendanceSyncMerger.canonicalReportPeriodTitle("Afternoon Session - MMS Full Time - 2026 [12:00 PM to 4:59 PM]"))
        assertEquals("Evening Session", AttendanceSyncMerger.canonicalReportPeriodTitle("Evening Session - MMS Full Time - 2026 [5:00 PM to 9:00 PM]"))
    }

    // =========================================================================
    // SCENARIO TEST 4: School List & Hash Validation
    // =========================================================================

    @Test
    fun testSchoolList_VerificationAndHashes() {
        val jsonArray = JSONArray(schoolListJson)
        assertEquals(4, jsonArray.length())

        val schoolMap = mutableMapOf<String, String>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val id = obj.getString("ID")
            val hash = obj.getString("schoolHash")
            schoolMap[id] = hash
        }

        assertTrue(schoolMap.containsKey("1"))
        assertTrue(schoolMap.containsKey("2"))
        assertTrue(schoolMap.containsKey("3"))
        assertTrue(schoolMap.containsKey("4"))

        assertEquals("c4ca4238a0b923820dcc509a6f75849b", schoolMap["1"])
        assertEquals("c81e728d9d4c2f636f067f89cc14862c", schoolMap["2"])
    }

    @Test
    fun testAttendanceInstituteValidator_SchoolListScenarios() {
        // Valid same-institute attendance
        assertNull(AttendanceInstituteValidator.validate("1", listOf("1", "1")))
        assertNull(AttendanceInstituteValidator.validate("2", listOf("2")))

        // Cross-institute error
        assertEquals("Attendance institute does not match session institute", AttendanceInstituteValidator.validate("1", listOf("1", "2")))

        // Missing session institute error
        assertEquals("Session institute is missing", AttendanceInstituteValidator.validate("", listOf("1")))
    }

    // =========================================================================
    // SCENARIO TEST 5: Attendance Code Mapping & Validity
    // =========================================================================

    @Test
    fun testAttendanceCodes_Institute1And2() {
        val inst1Array = JSONArray(attCodeInst1Json)
        val inst2Array = JSONArray(attCodeInst2Json)

        assertEquals(4, inst1Array.length())
        assertEquals(4, inst2Array.length())

        val inst1Codes = (0 until inst1Array.length()).map { inst1Array.getJSONObject(it).getString("atcCode") }
        val inst2Codes = (0 until inst2Array.length()).map { inst2Array.getJSONObject(it).getString("atcCode") }

        assertEquals(listOf("P", "A", "E", "L"), inst1Codes)
        assertEquals(listOf("P", "A", "E", "L"), inst2Codes)

        // Verify markable flags
        for (i in 0 until inst1Array.length()) {
            assertEquals("Y", inst1Array.getJSONObject(i).getString("atcIsMarkable"))
            assertEquals("1", inst1Array.getJSONObject(i).getString("atcSchoolId"))
        }
        for (i in 0 until inst2Array.length()) {
            assertEquals("Y", inst2Array.getJSONObject(i).getString("atcIsMarkable"))
            assertEquals("2", inst2Array.getJSONObject(i).getString("atcSchoolId"))
        }
    }

    // =========================================================================
    // SCENARIO TEST 6: Full Attendance Merging & Workflow Test with data.md
    // =========================================================================

    @Test
    fun testAttendanceSyncMerger_Institute1_Students1To5_Workflow() {
        // Institute 1: Students 1, 2, 3, 4, 5
        // Server status pre-set: Student 3='L' (Late), Student 4='E' (Exempted), Student 1,2,5='A'
        val serverStatusMap = mapOf(
            "1" to "A",
            "2" to "A",
            "3" to "L",
            "4" to "E",
            "5" to "A"
        )

        // Local face scanning: Student 1 & 2 captured via facial recognition ('P')
        // Student 5 explicitly modified by teacher to 'P'
        val localAttendanceList = listOf(
            createAttendance("1", "1", "P", isFaceCaptured = true, isExplicitEdit = false),
            createAttendance("2", "1", "P", isFaceCaptured = true, isExplicitEdit = false),
            createAttendance("3", "1", "A", isFaceCaptured = false, isExplicitEdit = false),
            createAttendance("4", "1", "A", isFaceCaptured = false, isExplicitEdit = false),
            createAttendance("5", "1", "P", isFaceCaptured = false, isExplicitEdit = true)
        )

        val mergedResult = AttendanceSyncMerger.mergeAttendance(localAttendanceList, serverStatusMap)
        val resultMap = mergedResult.associate { it.studentId to it.status }

        assertEquals("P", resultMap["1"]) // Captured face -> Present
        assertEquals("P", resultMap["2"]) // Captured face -> Present
        assertEquals("L", resultMap["3"]) // Preserved Server Late status
        assertEquals("E", resultMap["4"]) // Preserved Server Exempted status
        assertEquals("P", resultMap["5"]) // Explicit teacher edit -> Present
    }

    @Test
    fun testAttendanceSyncMerger_Institute2_Students425To431_Workflow() {
        // Institute 2: Students 425..431
        // Server pre-marked: 427='L', 428='E'
        val serverStatusMap = mapOf(
            "425" to "A", "426" to "A", "427" to "L",
            "428" to "E", "429" to "A", "430" to "A", "431" to "A"
        )

        // Local face scanning: 425 & 426 recognized
        val localList = listOf("425", "426", "427", "428", "429", "430", "431").map { id ->
            val captured = id in setOf("425", "426")
            createAttendance(id, "2", if (captured) "P" else "A", isFaceCaptured = captured, isExplicitEdit = false)
        }

        val merged = AttendanceSyncMerger.mergeAttendance(localList, serverStatusMap)

        val presentCount = merged.count { it.status == "P" }
        val lateCount = merged.count { it.status == "L" }
        val exemptedCount = merged.count { it.status == "E" }
        val absentCount = merged.count { it.status == "A" }

        assertEquals(2, presentCount)  // 425, 426
        assertEquals(1, lateCount)     // 427
        assertEquals(1, exemptedCount) // 428
        assertEquals(3, absentCount)   // 429, 430, 431
        assertEquals(7, merged.size)
    }

    // =========================================================================
    // SCENARIO TEST 7: Multi-Institute & Multi-Class Attendance Object Field Validation
    // (Institute 1 & Institute 2 / Class 1, 8, 3, 10 - Field-by-field verification)
    // =========================================================================

    @Test
    fun testMultiInstituteMultiClassStudentAttendanceObjectAssignment_Institute1And2() {
        // Build attendance object for Present student in Institute 1 (Class 1 / Class 8)
        val studentInst1Class1Att = createFullAttendance(
            studentId = "1",
            studentName = "Rahul Sharma",
            instId = "1",
            instShortName = "MSc Finance",
            academicYear = "2026",
            classId = "1",
            classShortName = "Msc Finance Part II#1#7",
            cpId = "7",
            courseId = "7",
            courseTitle = "Research Project-Practical",
            courseShortName = "Research Project-Practical",
            subjectId = "7",
            subjectTitle = "Research Project",
            mpId = "5",
            mpLongTitle = "TERM 1",
            teacherId = "82",
            teacherName = "Prof. Smith",
            attSchoolPeriodId = "10", // Morning Session for Inst 1
            period = "Morning Session",
            status = "P", // Attendance Code 'P' for Inst 1 (atcId 1)
            isFaceCaptured = true
        )

        // Build attendance object for Present student in Institute 2 (Class 3 / Class 10)
        val studentInst2Class3Att = createFullAttendance(
            studentId = "425",
            studentName = "Ananya Patel",
            instId = "2",
            instShortName = "MMS Full Time",
            academicYear = "2026",
            classId = "3",
            classShortName = "Third Year MMS#3#24",
            cpId = "53",
            courseId = "40",
            courseTitle = "International Business-Theory",
            courseShortName = "International Business-Theory",
            subjectId = "13",
            subjectTitle = "International Business",
            mpId = "7",
            mpLongTitle = "TERM 1",
            teacherId = "82",
            teacherName = "Prof. Smith",
            attSchoolPeriodId = "13", // Morning Session for Inst 2
            period = "Morning Session",
            status = "P", // Attendance Code 'P' for Inst 2 (atcId 4)
            isFaceCaptured = true
        )

        // Build attendance object for Class 8 student in Institute 1
        val studentInst1Class8Att = createFullAttendance(
            studentId = "801",
            studentName = "Vikas Kumar",
            instId = "1",
            instShortName = "MSc Finance",
            academicYear = "2026",
            classId = "8",
            classShortName = "Msc Finance Part I#1#18",
            cpId = "18",
            courseId = "1",
            courseTitle = "Portfolio Analysis and Management-Theory",
            courseShortName = "Portfolio Analysis-Theory",
            subjectId = "1",
            subjectTitle = "Portfolio Analysis and Management",
            mpId = "5",
            mpLongTitle = "TERM 1",
            teacherId = "76",
            teacherName = "Prof. Davis",
            attSchoolPeriodId = "11", // Afternoon Session for Inst 1
            period = "Afternoon Session",
            status = "P",
            isFaceCaptured = true
        )

        // Build attendance object for Class 10 student in Institute 2
        val studentInst2Class10Att = createFullAttendance(
            studentId = "1001",
            studentName = "Neha Verma",
            instId = "2",
            instShortName = "MMS Full Time",
            academicYear = "2026",
            classId = "10",
            classShortName = "MMS Second Year#2#53",
            cpId = "53",
            courseId = "40",
            courseTitle = "International Business-Theory",
            courseShortName = "International Business-Theory",
            subjectId = "13",
            subjectTitle = "International Business",
            mpId = "7",
            mpLongTitle = "TERM 1",
            teacherId = "82",
            teacherName = "Prof. Smith",
            attSchoolPeriodId = "14", // Afternoon Session for Inst 2
            period = "Afternoon Session",
            status = "P",
            isFaceCaptured = true
        )

        val allAttendanceList = listOf(studentInst1Class1Att, studentInst2Class3Att, studentInst1Class8Att, studentInst2Class10Att)

        // 1. Verify Institute 1 Class 1 student attendance object fields
        val att1 = allAttendanceList.first { it.studentId == "1" }
        assertEquals("1", att1.instId)
        assertEquals("MSc Finance", att1.instShortName)
        assertEquals("2026", att1.academicYear)
        assertEquals("1", att1.classId)
        assertEquals("Msc Finance Part II#1#7", att1.classShortName)
        assertEquals("7", att1.cpId)
        assertEquals("7", att1.courseId)
        assertEquals("Research Project-Practical", att1.courseTitle)
        assertEquals("7", att1.subjectId)
        assertEquals("Research Project", att1.subjectTitle)
        assertEquals("5", att1.mpId)
        assertEquals("10", att1.attSchoolPeriodId) // Must be spId 10 from Inst 1
        assertEquals("Morning Session", att1.period)
        assertEquals("P", att1.status)
        assertTrue(att1.isFaceCaptured)

        // 2. Verify Institute 2 Class 3 student attendance object fields
        val att2 = allAttendanceList.first { it.studentId == "425" }
        assertEquals("2", att2.instId)
        assertEquals("MMS Full Time", att2.instShortName)
        assertEquals("2026", att2.academicYear)
        assertEquals("3", att2.classId)
        assertEquals("Third Year MMS#3#24", att2.classShortName)
        assertEquals("53", att2.cpId)
        assertEquals("40", att2.courseId)
        assertEquals("International Business-Theory", att2.courseTitle)
        assertEquals("13", att2.subjectId)
        assertEquals("International Business", att2.subjectTitle)
        assertEquals("7", att2.mpId)
        assertEquals("13", att2.attSchoolPeriodId) // Must be spId 13 from Inst 2
        assertEquals("Morning Session", att2.period)
        assertEquals("P", att2.status)
        assertTrue(att2.isFaceCaptured)

        // 3. Verify Institute 1 Class 8 student attendance object fields
        val att3 = allAttendanceList.first { it.studentId == "801" }
        assertEquals("1", att3.instId)
        assertEquals("8", att3.classId)
        assertEquals("18", att3.cpId)
        assertEquals("1", att3.courseId)
        assertEquals("1", att3.subjectId)
        assertEquals("11", att3.attSchoolPeriodId) // Afternoon session for Inst 1
        assertEquals("Afternoon Session", att3.period)

        // 4. Verify Institute 2 Class 10 student attendance object fields
        val att4 = allAttendanceList.first { it.studentId == "1001" }
        assertEquals("2", att4.instId)
        assertEquals("10", att4.classId)
        assertEquals("53", att4.cpId)
        assertEquals("40", att4.courseId)
        assertEquals("13", att4.subjectId)
        assertEquals("14", att4.attSchoolPeriodId) // Afternoon session for Inst 2
        assertEquals("Afternoon Session", att4.period)

        // 5. Verify absolute isolation: Institute 1 objects must NOT carry Institute 2 period IDs or metadata
        assertNotEquals(att1.instId, att2.instId)
        assertNotEquals(att1.attSchoolPeriodId, att2.attSchoolPeriodId)
        assertNotEquals(att3.attSchoolPeriodId, att4.attSchoolPeriodId)
    }

    // Helper method to instantiate Attendance entity with all detailed fields
    private fun createFullAttendance(
        studentId: String,
        studentName: String,
        instId: String,
        instShortName: String,
        academicYear: String,
        classId: String,
        classShortName: String,
        cpId: String,
        courseId: String,
        courseTitle: String,
        courseShortName: String,
        subjectId: String,
        subjectTitle: String,
        mpId: String,
        mpLongTitle: String,
        teacherId: String,
        teacherName: String,
        attSchoolPeriodId: String,
        period: String,
        status: String,
        isFaceCaptured: Boolean
    ): Attendance {
        return Attendance(
            atteId = "att_$studentId",
            instId = instId,
            instShortName = instShortName,
            academicYear = academicYear,
            classId = classId,
            classShortName = classShortName,
            markedAt = "2026-08-06 09:30:00",
            sessionId = "session_$instId",
            status = status,
            studentId = studentId,
            studentName = studentName,
            syncStatus = "pending",
            teacherId = teacherId,
            teacherName = teacherName,
            date = "2026-08-06",
            startTime = "09:30",
            endTime = "11:59",
            period = period,
            cpId = cpId,
            courseId = courseId,
            courseTitle = courseTitle,
            courseShortName = courseShortName,
            subjectId = subjectId,
            subjectTitle = subjectTitle,
            mpId = mpId,
            mpLongTitle = mpLongTitle,
            attSchoolPeriodId = attSchoolPeriodId,
            isFaceCaptured = isFaceCaptured,
            isExplicitEdit = false
        )
    }

    // Helper method to instantiate Attendance entity for testing
    private fun createAttendance(
        studentId: String,
        instId: String,
        status: String,
        isFaceCaptured: Boolean,
        isExplicitEdit: Boolean
    ): Attendance {
        return Attendance(
            atteId = "att_$studentId",
            instId = instId,
            academicYear = "2026",
            classId = if (instId == "1") "1" else "3",
            markedAt = "2026-08-06 09:30:00",
            sessionId = "session_001",
            status = status,
            studentId = studentId,
            studentName = "Student $studentId",
            syncStatus = "pending",
            teacherId = "82",
            date = "2026-08-06",
            startTime = "09:30",
            endTime = "11:59",
            period = "Morning Session",
            cpId = if (instId == "1") "7" else "53",
            mpId = if (instId == "1") "5" else "7",
            attSchoolPeriodId = if (instId == "1") "10" else "13",
            isFaceCaptured = isFaceCaptured,
            isExplicitEdit = isExplicitEdit
        )
    }
}

