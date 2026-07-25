# Selfie Attendance – Face Registration Change Requirements

**Document Date**: 23 July 2026  
**Application**: Selfie Attendance System (Android & Server Backend Integration)  
**Module**: Face Registration & Biometric Synchronization  

---

## 📌 Executive Summary

This document specifies the updated functional and API requirements for the **Face Registration** module in the Selfie Attendance application. The primary change mandates storing both the **Face Signature (Embedding)** and the **Captured Face Image (uploaded to Amazon S3)** during user registration. Additionally, the registration search/selection UI is updated to render stored face photos alongside user details for immediate visual identification and verification.

---

## 🎯 Key Changes & Functional Requirements

### 1. Dual-Storage: Face Image (S3) + Face Signature (Biometric Embedding)

* **Previous Behavior**: Only the face signature string (`template` / `fingerData`) was sent to and stored on the server database.
* **New Requirement**:
  1. During face enrollment/capture, the center face frame image must be captured in addition to calculating the 128/512-dimension face embedding array.
  2. The captured face image is uploaded to **Amazon S3** using a pre-signed S3 `PUT` URL.
  3. The returned S3 relative file path (`imagePath`) is included in the registration `POST` payload alongside the face signature template.
  4. Both the face signature and the S3 face image path are saved on the server database and returned via the `GET` biometric sync API.

### 2. Enhanced Registration List UI with Image Preview

* **Previous Behavior**: The user selection/search list displayed only the user's Name and ID (e.g., `Teacher Jane (382)`).
* **New Requirement**:
  1. In the registration list and user dropdown, each user entry displays their registered face thumbnail image alongside their Name and ID.
  2. If a user is already registered, their stored face image is fetched via S3 pre-signed `GET` URL and rendered.
  3. This enables administrators/users to instantly confirm whether the correct person's face is registered, helping detect incorrect enrollments immediately.

---

## 🔄 End-to-End Workflow & Data Flow

```
[Android App] --(1. Capture Face & Compute Embedding)--> [Camera / FaceNet]
     |
     +--(2. Request Presigned PUT URL)------------------> [Backend Server (DeAws)]
     |<-(Returns S3 Signed PUT URL)---------------------+
     |
     +--(3. Upload Image HTTP PUT)----------------------> [Amazon S3 Bucket]
     |
     +--(4. POST updateUserRegistration: template+imagePath)-> [Backend API]
     |<-(Response: SUCCESS)-----------------------------+
     |
     v
[Local App Cache & UI Update] (Display user avatar + face signature stored)
```

---

## 🌐 Updated API Specifications

### III. Face Registration APIs

Used to save, update, retrieve, or clear face signature templates and S3 face image paths for student or teacher identities.

---

### 1. Saving / Updating / Deleting Face Registration

Updates biometric information and face image reference path on the server database.

* **Route**: `api/v1/User/updateUserRegistration`
* **HTTP Method**: `POST`
* **Content-Type**: `application/json`

#### Request Body (Add / Update Face Registration):
> [!NOTE]
> Added `"imagePath"` parameter inside `regParamData` items to store the relative S3 path of the registered face image.

```json
{
  "userRegParamData": {
    "userType": "student",
    "registrationType": "Biometric",
    "regParamData": [
      {
        "userId": "123",
        "metricType": "faceSignature",
        "fingerType": "faceSignature",
        "template": "0.012,-0.124,0.732,-0.045,...",
        "imagePath": "global/module_faceRegistration/student_123_face.jpg"
      }
    ]
  }
}
```

#### Request Body (Delete / Clear Registration):
*Sends an empty template and empty imagePath to clear biometric data.*

```json
{
  "userRegParamData": {
    "userType": "student",
    "registrationType": "Biometric",
    "regParamData": [
      {
        "userId": "123",
        "metricType": "faceSignature",
        "fingerType": "faceSignature",
        "template": "",
        "imagePath": ""
      }
    ]
  }
}
```

#### Success Response:
```json
{
  "collection": {
    "version": "1.0",
    "response": {
      "successStatus": "TRUE"
    }
  }
}
```

---

### 2. Getting Student / Staff Registered Biometrics & Metadata

Retrieves student or teacher profiles along with their stored face signature embedding strings and S3 face image paths.

* **Route**: `api/v1/User/GetUserRegisteredDetails`
* **HTTP Method**: `GET`
* **Query Parameter**: `data` (JSON-encoded string)

#### Request Query Payload (`data`):
```json
{
  "userRegParamData": {
    "userType": "staff",
    "registrationType": "Biometric",
    "school_id": "1"
  }
}
```

#### Success Response:
> [!NOTE]
> Returns `"imagePath"` alongside `"fingerData"` for each registered user.

```json
{
  "collection": {
    "version": "1.0",
    "response": {
      "userRegisteredData": [
        {
          "staffProfile": "teacher",
          "staffId": "382",
          "staffName": "Teacher Jane",
          "fingerType": "faceSignature",
          "fingerData": "-0.043,0.111,-0.502,0.891,...",
          "imagePath": "global/module_faceRegistration/teacher_382_face.jpg"
        },
        {
          "studentId": "123",
          "studentName": "John Doe",
          "fingerType": "faceSignature",
          "fingerData": "0.012,-0.124,0.732,-0.045,...",
          "imagePath": "global/module_faceRegistration/student_123_face.jpg"
        }
      ]
    }
  }
}
```

---

### 3. Amazon S3 Pre-Signed URL API (Image Upload & Download)

Used by the Android client to get pre-signed S3 URLs for uploading captured face images (PUT) or loading registered images (GET).

* **Route**: `sims-services/digitalsims/?r=api/v1/DeAws/Mgmt`
* **HTTP Method**: `POST`

#### Request Payload for Upload (PUT):
```json
{
  "deAwsParams": {
    "actionType": "getPresignedS3Url",
    "actionData": [
      {
        "bucketName": "prod-web9-s3-depl",
        "filePathAndName": "clientID/faceRegistration/usetype_userid_timestamp.jpg",
        "fileActivityType": "put"
      }
    ]
  }
}
```

#### Request Payload for Fetching Image URL (GET):
```json
{
  "deAwsParams": {
    "actionType": "getPresignedS3Url",
    "actionData": [
      {
        "bucketName": "prod-web9-s3-depl",
        "filePathAndName": "clientID/faceRegistration/usetype_userid_timestamp.jpg",
        "fileActivityType": "get"
      }
    ]
  }
}
```

#### Response Format:
```json
{
  "collection": {
    "response": {
      "status": "SUCCESS",
      "data": [
        {
          "signedUrl": "https://prod-web9-s3-depl.s3.ap-south-1.amazonaws.com/global/module_faceRegistration/student_123_face.jpg?X-Amz-Algorithm=..."
        }
      ]
    }
  }
}
```

---
1. during select schoollist also call this api get organization list 
https://oma.digitaledu.in/sims-services/digitalsims/?r=api/v1/App/AllOrganization&data={ "mobileAppKey": "installMobileAppKey" }

responce :
{
    "collection": {
        "version": "1.0",
        "link": "http://api.digitaledu.net/v1/docs",
        "response": {
            "statusMsg": "SUCCESS",
            "retAllOrganizationList": [
                {
                    "clientId": "c4ca4238a0b923820dcc509a6f75849b",
                    "schoolId": "c4ca4238a0b923820dcc509a6f75849b",
                    "schoolTitle": "Localhost simsGit",
                    "authority": null,
                    "instituteLongName": "Localhost SimsGit",
                    "organizationUrl": "http://localhost:8080/sims.git/",
                    "organizationUrl1": "https://m-testing8.digitaledu.in/",
                    "organizationUrl2": "https://ai-testing8.digitaledu.in/",
                    "isAdmProcessFollow": "Y",
                    "clientGrpIds": ""
                }


ao during login we get base url ...
now we have base uri and this oma api give organizationUrl match this and get client id from here and this client id used in s3 folder ...
if api faild then show error message "cliend id " not found ..or if any error show error message 
@selectInsitute .. ...

```


process is .... when register success then center image capture and upload to s3 using this api 


```

## 🛠 Summary of Field Changes

| Endpoint / Context | Key Name | Data Type | Description |
|---|---|---|---|
| `updateUserRegistration` (POST) | `imagePath` | String | S3 relative file path (e.g. `global/module_faceRegistration/student_123_face.jpg`) |
| `GetUserRegisteredDetails` (GET) | `imagePath` | String | S3 relative file path saved on server for user |
| S3 Pre-Signed URL Payload | `filePathAndName` | String | Path in S3 bucket where face image is stored |
| Local SQLite Room DB | `imagePath` / `image_url` | String | Local column in `Student` and `Teacher` tables for offline cache & UI rendering |

---

## 🛡️ Handling Existing / Legacy Registrations (Backward Compatibility)

For users who registered **before** this update, their record on the server database contains only the face embedding string (`fingerData`) and has no associated S3 face image.

### Strategy & Implementation Rules:

1. **Nullable Field in API & Data Models**:
   * The `imagePath` parameter in `GetUserRegisteredDetails` (GET API) is treated as nullable (`String?`).
   * For legacy users, `imagePath` will return `null` or `""` (empty string).

2. **UI Fallback & Visual Badge (Registration List UI)**:
   * **When `imagePath` is valid**: The app fetches the S3 pre-signed GET URL and displays the face photo thumbnail.
   * **When `imagePath` is `null` or `""`**: The UI displays a fallback placeholder avatar (e.g. default user icon / initials avatar) along with a subtle status badge: `[Signature Registered / Photo Missing]`.

3. **Selfie Attendance & Verification Continuity**:
   * Face recognition / selfie attendance matching relies **exclusively** on the biometric `faceSignature` (embedding vector).
   * **Zero Disruption**: Existing users without stored photos can continue taking selfie attendance smoothly without re-registering.

4. **Seamless Re-Registration / Photo Update Path**:
   * Administrators can filter/identify users marked as `[Photo Missing]` in the registration list.
   * Selecting the user and clicking **Update Registration** will launch the camera, capture a fresh face photo, upload it to S3, and update both the `template` and `imagePath` on the server simultaneously.


