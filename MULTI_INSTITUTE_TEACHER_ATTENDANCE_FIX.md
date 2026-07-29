# Multi-Institute Teacher Attendance Fix

## Status

Implemented using the recommended normalized database design:

```text
teachers
T101 | Asha Sharma

teacher_institute_map
T101 | I1
T101 | I2
```

Comma-separated institute IDs are **not** used by the new attendance flow.
The old `teachers.instId` column is retained temporarily only for safe upgrade
compatibility. It is migrated into `teacher_institute_map`, and session
creation no longer reads it.

## Original problem

The previous `teachers` table used `staffId` as its only primary key and stored
one `instId`:

```kotlin
data class Teacher(
    @PrimaryKey val staffId: String,
    val staffName: String,
    val instId: String,
    ...
)
```

Teacher data was synchronized one institute at a time with
`OnConflictStrategy.REPLACE`.

For teacher `T101`:

```text
sync I1 -> T101 | I1
sync I2 -> T101 | I2 (replaces I1)
```

Session creation then called `getInstituteIdByTeacherId(T101)`. The last
synchronized institute was saved into `Session.instId`, even when the teacher
was taking attendance at another institute.

## Implemented database design

The new entity stores exact many-to-many membership:

```kotlin
@Entity(
    tableName = "teacher_institute_map",
    primaryKeys = ["teacherId", "instId"]
)
data class TeacherInstituteMap(
    val teacherId: String,
    val instId: String
)
```

The composite primary key prevents duplicate membership rows.

Available DAO operations include:

- exact teachers-by-institute lookup through a join;
- institute IDs assigned to one teacher;
- institute records and names assigned to one teacher;
- replacement of mappings for one successfully synchronized institute.

No `LIKE` query or comma parsing is used during normal application operation.

## Safe application upgrade

The application database was upgraded from version 2 to version 3.

`MIGRATION_2_3` performs an additive migration:

1. Creates `teacher_institute_map`.
2. Reads every existing teacher and legacy `instId`.
3. Trims and splits legacy comma-separated values if any exist.
4. Inserts unique teacher/institute rows.
5. Leaves the existing `teachers` table unchanged.
6. Leaves students, face embeddings, sessions, attendance, periods, courses,
   configuration, and all other tables unchanged.

The existing teacher column remains for one migration cycle to avoid rebuilding
the table that contains locally registered face embeddings.

Most importantly, `fallbackToDestructiveMigration()` was removed. An unknown
database upgrade will now fail safely instead of silently deleting local data.

The Room schema is exported at:

```text
app/schemas/com.digitaledu.selfieattendance.db.dao.AppDatabase/3.json
```

The application version was increased:

```text
versionCode: 6
versionName: 2.1.0
```

This allows the new APK to update version-code-5 installations normally.

## Latest-data synchronization

Teacher synchronization still requests one institute at a time, but a
successful response is now authoritative only for that institute.

Inside one Room transaction:

1. Validate that the sync request contains one exact institute ID.
2. Parse the complete teacher response.
3. Preserve an existing non-empty embedding if the server returns an empty one.
4. Preserve an existing non-empty fingerprint type when necessary.
5. Delete old mapping rows only for the successfully downloaded institute.
6. Insert or update the latest teacher identities.
7. Insert the latest membership rows for that institute.

Example:

```text
Initial:
T101 -> I1, I2

Successful latest sync for I1 contains T101:
T101 -> I1, I2

Successful latest sync for I1 no longer contains T101:
T101 -> I2

Failed or malformed sync for I1:
No mapping is deleted or changed
```

Teacher identity rows are retained when their final membership is removed.
This protects historical attendance references and locally saved biometric
data. A teacher with no current membership cannot start a new session.

## Session-start flow

After teacher recognition and before session creation, the application
calculates:

```text
teacher assigned institutes
INTERSECT
institutes selected and synchronized on this device
INTERSECT
institute records available in the local database
```

The result controls the flow:

- Zero institutes: block session creation and request a data sync.
- One institute: select automatically and preserve the existing quick flow.
- Multiple institutes: display a required single-choice dialog with institute
  name and ID.
- Cancel: create no session.

The dialog uses radio-button behaviour. **Continue** stays disabled until the
teacher chooses exactly one institute.

## Session institute is immutable

The chosen institute ID is passed explicitly through:

```text
chooseInstituteAndStartSession
    -> startNewTeacherSession(selectedInstId)
    -> proceedWithNewTeacherSession(selectedInstId)
    -> Session.instId
```

The old `getInstituteIdByTeacherId()` session lookup was removed.

`Session.instId` is now the source of truth for:

- school-period lookup;
- attendance creation;
- institute name;
- academic year;
- attendance review;
- delayed and logout-time upload;
- server payload validation.

A later teacher sync cannot change the institute of an existing session.

## Attendance safety checks

Before saving student attendance:

```text
student.instId must equal Session.instId
```

The application blocks the scan if:

- the active session has no institute;
- the student belongs to another institute.

Before every known attendance upload path, the application validates:

```text
every Attendance.instId must equal Session.instId
```

Inconsistent sessions remain local and are not sent with incorrect institute
data. Validation covers:

- the dedicated attendance sync screen;
- the attendance overview submission;
- settings logout synchronization;
- classroom/home logout synchronization.

## Normal single-institute flow

The normal flow is preserved:

1. Teacher is recognized.
2. The mapping query returns one institute.
3. It is selected automatically.
4. The correct periods are loaded.
5. The session starts without an extra selection dialog.

The only added visible feedback is a short institute confirmation.

## Verification completed

The following commands completed successfully:

```text
./gradlew testDebugUnitTest assembleDebug
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
```

The database migration test was also executed on a connected Android 16
physical device:

```text
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.digitaledu.selfieattendance.TeacherInstituteMigrationTest
```

Result:

```text
Finished 1 tests
BUILD SUCCESSFUL
```

The device migration test verifies:

- teacher ID and name are preserved;
- saved face embedding is preserved;
- `"I1, I2,I1"` becomes exactly `I1` and `I2`;
- unrelated database data remains preserved.

Unit tests also verify that:

- matching session and attendance institutes pass;
- a missing session institute fails;
- cross-institute attendance fails.

## Files changed

```text
app/build.gradle.kts
app/src/main/java/com/digitaledu/selfieattendance/db/entity/AllEntity.kt
app/src/main/java/com/digitaledu/selfieattendance/db/dao/AllDao.kt
app/src/main/java/com/digitaledu/selfieattendance/db/dao/AppDatabase.kt
app/src/main/java/com/digitaledu/selfieattendance/repository/DataSyncRepository.kt
app/src/main/java/com/digitaledu/selfieattendance/view/AttendanceActivity.kt
app/src/main/java/com/digitaledu/selfieattendance/view/SyncAttendanceToServer.kt
app/src/main/java/com/digitaledu/selfieattendance/view/AttendanceOverviewActivity.kt
app/src/main/java/com/digitaledu/selfieattendance/view/SettingsActivity.kt
app/src/main/java/com/digitaledu/selfieattendance/view/ClassroomScanFragment.kt
app/src/main/java/com/digitaledu/selfieattendance/utility/AttendanceInstituteValidator.kt
app/src/test/java/com/digitaledu/selfieattendance/utility/AttendanceInstituteValidatorTest.kt
app/src/androidTest/java/com/digitaledu/selfieattendance/TeacherInstituteMigrationTest.kt
app/schemas/com.digitaledu.selfieattendance.db.dao.AppDatabase/3.json
```

## Remaining recommended improvements

These are outside the current teacher-institute fix but should be completed
before assuming every cached entity is safe across unrelated institute
databases:

1. Add `instId` to `teacher_class_map`, producing the identity
   `(teacherId, instId, classId)`.
2. Confirm whether student, class, course, subject, and course-period IDs are
   globally unique. If not, add `instId` to their composite keys and joins.
3. Include `instId` in the active-class-cycle identity.
4. Replace the in-memory attendance counter with a UUID to avoid ID reuse after
   process restart.
5. In the next controlled database release, remove the deprecated
   `teachers.instId` column after field telemetry or production verification
   confirms that every active installation has migrated.

## Definition of done

The multi-institute issue is fixed when:

- a teacher can have multiple exact institute mapping rows;
- latest successful sync updates only the relevant institute membership;
- single-institute teachers continue automatically;
- multi-institute teachers must choose one institute;
- the chosen institute is saved once in the session;
- attendance from another institute is blocked;
- inconsistent upload data is blocked;
- upgrading from the previous app preserves existing data and embeddings.

