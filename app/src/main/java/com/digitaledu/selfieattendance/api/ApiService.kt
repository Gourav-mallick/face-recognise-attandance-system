
package com.digitaledu.selfieattendance.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query


interface ApiService {

    @GET("sims-services/digitalsims/")
    suspend fun getUserAuthenticatedDataRaw(
        @Query("r") r: String,  //endpoint
        @Query("data") data: String
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun authenticateStaff(
        @Query("r") r: String = "api/v1/Staff/AuthStaff",
        @Query("data") data: String
    ): Response<ResponseBody>


    @GET("sims-services/digitalsims/")
    suspend fun getUserAssignedAccessPrivileges(
        @Query("r") r: String = "api/v1/User/GetUserAssignedAccessPrivileges",
        @Query(value = "data", encoded = true) data: String
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun getSchoolList(
        @Query("r") r: String = "api/v1/School/SchoolList"
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun getSchoolAttendanceCodes(
        @Query("r") r: String = "api/v1/att/schoolAttCodeToMarkAtt",
        @Query("data") data: String
    ): Response<ResponseBody>




    // Fetch student list by passing JSON query
    @GET("sims-services/digitalsims/")
    suspend fun getStudents(
        @Query("r") r: String = "api/v1/User/GetUserRegisteredDetails",
        @Query("data") data: String
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun getTeachers(
        @Query("r") r: String = "api/v1/User/GetUserRegisteredDetails",
        @Query("data") data: String
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun getSubjectInstances(
        @Query("r") r: String = "api/v1/CoursePeriod/SubjectInstances",
        @Query("data") data: String
    ): Response<ResponseBody>




    @GET("sims-services/digitalsims/")
    suspend fun getDeveiceDataToserver(
        @Query("r") r: String = "api/v1/Hardware/DeviceUtilityMgmt",
        @Query("data") data: String
    ): Response<ResponseBody>


    @GET("sims-services/digitalsims/")
    suspend fun getStudentScheduleList(
        @Query("r") r: String = "api/v1/Schedule/GetStudList",
        @Query("data") data: String
    ): Response<ResponseBody>


    @POST("sims-services/digitalsims/")
    suspend fun postAttendanceSync(
        @Query("r") r: String = "api/v1/Att/ManageMarkingGlobalAtt",
        @Body requestBody: RequestBody
    ): Response<ResponseBody>


    @POST("sims-services/digitalsims/")
    suspend fun postUserRegistration(
        @Query("r") r: String = "api/v1/User/updateUserRegistration",
        @Body body: RequestBody
    ): Response<ResponseBody>


    @POST("sims-services/digitalsims/")
    suspend fun postStudentSubjectSchedule(
        @Query("r") r: String = "api/v1/SubjectManager/ManageStudentSubjectScheduling",
        @Body body: RequestBody
    ): Response<ResponseBody>



    @POST("api/v1/CoursePeriod/ManageTeacherAllocation")
    suspend fun postTeacherAllocation(
        @Body body: RequestBody
    ): Response<ResponseBody>


    @GET("sims-services/digitalsims/")
    suspend fun getPeriodDetails(
        @Query("r") r: String,
        @Query("data") data: String
    ): Response<ResponseBody>


    @GET("sims-services/digitalsims/")
    suspend fun getProgramConfig(
        @Query("r") r: String = "api/v1/Config/ManageProgramConfig",
        @Query("data") data: String
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun getAttendanceReport(
        @Query("r") r: String = "api/v1/Att/AttReport",
        @Query("data") data: String
    ): Response<ResponseBody>


    @retrofit2.http.Multipart
    @POST("sims-services/digitalsims/")
    suspend fun uploadStudentPhotos(
        @Query("r") r: String = "api/v1/FileUpload/UploadStudentPhotos",
        @retrofit2.http.Part("uploadfile_folderyear") folderYear: RequestBody,
        @retrofit2.http.Part file: okhttp3.MultipartBody.Part
    ): Response<ResponseBody>

}