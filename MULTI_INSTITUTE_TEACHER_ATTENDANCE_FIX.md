# Multi-Institute Teacher Attendance: Problem and Fix

## 1. Problem summary

Attendance works when a teacher is assigned to one institute. When the same
teacher is assigned to multiple institutes, attendance is saved locally but may
contain the wrong institute, academic year, period, class, or course data.

The immediate cause is the local `teachers` table:

```kotlin
@Entity(tableName = "teachers")
data class Teacher(
    @PrimaryKey val staffId: String,
    val staffName: String,
    val instId: String,
    ...
)
```

`staffId` is the only primary key. Teacher data is downloaded one institute at
a time and inserted with `OnConflictStrategy.REPLACE`. If teacher `T101` belongs
to institutes `I1` and `I2`, the sync behaves like this:

1. Insert `T101, I1`.
2. Insert `T101, I2`.
3. Room replaces the first row because both rows have the same `staffId`.
4. The final teacher row contains only `I2`.

The institute that remains depends on sync order. It is not necessarily the
institute where the teacher is taking attendance.

The issue becomes visible in `AttendanceActivity.startNewTeacherSession()` and
`proceedWithNewTeacherSession()`, which call:

```kotlin
db.teachersDao().getInstituteIdByTeacherId(teacherId)
```

That ambiguous value is written into `Session.instId`. Attendance then copies
the institute from the session and sends it to the attendance API. Therefore,
the attendance can be internally consistent but consistently assigned to the
wrong institute.

## 2. Required behaviour

The expected flow should be:

1. Store all institute IDs assigned to a teacher.
2. Recognize or select the teacher.
3. Determine which of the teacher's institutes are available on this device.
4. If there is one valid institute, select it automatically.
5. If there are multiple valid institutes, show a required single-choice dialog
   containing institute names.
6. Create the session with that chosen institute ID.
7. Use the session's institute ID for the entire session lifecycle.
8. Never change the institute of an existing session, including after a new
   sync, app restart, resume, or delayed attendance upload.

Example teacher row requested for the short-term solution:

| staffId | staffName | instId |
|---|---|---|
| T101 | Asha Sharma | I1,I2,I5 |

Store IDs in canonical form: trim whitespace, remove empty IDs, remove
duplicates, and use a stable order.

## 3. Short-term fix using comma-separated institute IDs

The existing `instId` column is already a Room `TEXT` column, so its type does
not need to change. Its meaning changes from “one institute ID” to “a
comma-separated list of institute IDs.”

### 3.1 Add one parsing utility

Do not repeat raw `split(",")` logic throughout the app. Add a single utility:

```kotlin
object InstituteIds {
    fun parse(value: String?): List<String> =
        value.orEmpty()
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    fun encode(values: Iterable<String>): String =
        values.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .joinToString(",")
}
```

### 3.2 Merge teacher institute IDs during sync

Update `DataSyncRepository.fetchAndSaveTeachers()` so another institute does
not replace the teacher's previous membership.

For each downloaded teacher:

1. Load the existing teacher by `staffId`.
2. Parse the existing `instId`.
3. Add the institute currently being synchronized.
4. Encode the combined set.
5. Preserve a non-empty face embedding.
6. Insert the merged teacher.

Conceptual implementation:

```kotlin
val existing = db.teachersDao().getTeacherById(staffId)
val mergedInstituteIds = InstituteIds.encode(
    InstituteIds.parse(existing?.instId) + currentInstituteId
)

val teacher = Teacher(
    staffId = staffId,
    staffName = staffName.ifBlank { existing?.staffName.orEmpty() },
    instId = mergedInstituteIds,
    fingerType = apiFingerType.ifBlank { existing?.fingerType.orEmpty() },
    embedding = apiEmbedding.ifBlank { existing?.embedding.orEmpty() }
)
```

This merge should run inside a Room transaction. Do not allow an empty
embedding from one institute response to erase a valid locally registered
embedding.

For a full authoritative sync, first build all teacher-to-institute membership
in memory from every selected institute, and then write each teacher once. This
also makes it possible to remove institute assignments that no longer exist on
the server. Simply appending forever will leave stale institute IDs.

### 3.3 Change DAO usage

The following query is no longer safe:

```kotlin
SELECT instId FROM teachers WHERE staffId = :teacherId
```

It returns a list encoded as text, not the institute for a session. Rename it
to make its meaning clear:

```kotlin
@Query("SELECT instId FROM teachers WHERE staffId = :teacherId")
suspend fun getInstituteIdsByTeacherId(teacherId: String): String?
```

Avoid SQL such as `instId LIKE '%I1%'`; it can confuse IDs such as `I1` and
`I10`. Parse the value and compare exact IDs in Kotlin.

### 3.4 Show institute choice before creating a session

Add the choice after the teacher is recognized and before
`startNewTeacherSession()` checks periods or creates a `Session`.

Build the allowed choices as:

```text
teacher institute IDs
INTERSECT
device selectedInstituteIds
INTERSECT
locally available institutes
```

The UI must display `Institute.shortName` or `Institute.title`, but retain the
corresponding exact ID internally.

Example:

```text
Choose institute for this attendance session

( ) Digital Education - North Campus
( ) Digital Education - South Campus

[Cancel] [Continue]
```

Rules:

- Use radio buttons or another single-choice control, not checkboxes.
- Do not enable **Continue** until one institute is selected.
- If no valid institute remains, block session creation and ask for a data sync.
- If only one valid institute remains, auto-select it and optionally show a
  short confirmation.
- Show both name and ID if institute names are not unique.
- Do not save this as a permanent global “current institute.” It belongs to a
  particular session.

Suggested function flow:

```kotlin
handleTeacherScan(teacherId, teacherName)
    -> resolveAllowedInstitutes(teacherId)
    -> chooseInstituteIfNeeded()
    -> startNewTeacherSession(
           teacherId,
           teacherName,
           classId,
           selectedInstId
       )
```

### 3.5 Pass the selected institute explicitly

Change method signatures so the selected institute cannot be lost:

```kotlin
startNewTeacherSession(
    teacherId: String,
    teacherName: String,
    classId: String,
    selectedInstId: String
)

proceedWithNewTeacherSession(
    teacherId: String,
    teacherName: String,
    classId: String,
    spId: String,
    selectedInstId: String
)
```

Remove both calls to `getInstituteIdByTeacherId()` from the new-session path.
Use `selectedInstId` for:

- incomplete-session validation;
- school-period lookup;
- flexible school-period calculation;
- `Session.instId`;
- any class/course validation performed before the session starts.

Create the session as:

```kotlin
val session = Session(
    ...
    instId = selectedInstId,
    ...
)
```

### 3.6 Make `Session.instId` the source of truth

After a session has been inserted, every downstream operation must obtain the
institute from the session:

```kotlin
val session = db.sessionDao().getSessionById(sessionId)
val sessionInstId = session?.instId ?: error("Session institute is missing")
```

The current attendance marking path already reads `sessionObj.instId`; keep
that behaviour. The following must also use the session value:

- student filtering and validation;
- institute name and academic-year lookup;
- school-period selection;
- course and subject selection;
- incomplete-session checkpoint upload/download;
- session resume;
- attendance editing;
- final submission and retry;
- server payload generation.

Do not look up the teacher's institute again after session creation. A teacher
may be assigned to another institute between capture and upload, but that must
not alter previously captured attendance.

## 4. Data validation required before saving attendance

Before accepting a student scan, validate:

```text
student.instId == session.instId
```

If the values differ, do not save attendance. Show a clear message such as:

> This student belongs to a different institute than the active session.

Also validate that the selected class, school period, course period, and
attendance code belong to `session.instId`. This prevents mixed-institute
records even if locally cached data is incorrect.

The server payload should be built from one session and should reject a group
where:

```text
attendance.instId != session.instId
```

Log and quarantine invalid records instead of uploading them.

## 5. Room migration and existing data

Because `Teacher.instId` remains `TEXT`, the short-term representation does not
require a column-type migration. However, the behaviour change should still
ship with a database version increase and a tested `MIGRATION_3_4`, especially
if any additional index, mapping table, or session field is added.

Do not use `fallbackToDestructiveMigration`; attendance and face registrations
must not be deleted during an upgrade.

Existing one-institute teacher rows remain valid because `"I1"` parses as a
one-item list. After upgrade:

1. Preserve existing local sessions and attendance unchanged.
2. Run a full teacher sync for all device-selected institutes.
3. Merge teacher memberships.
4. Do not rewrite `instId` on existing `sessions` or `attendance`.
5. Flag legacy open sessions with an empty or unknown `instId` for manual
   correction; do not guess when multiple institutes are possible.

## 6. Preferred long-term database design

Comma-separated IDs satisfy the requested quick fix, but they are not a good
relational database design. They are difficult to query, validate, index, and
update safely.

The recommended design is a mapping table:

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

Then keep `Teacher` as teacher identity only:

```kotlin
data class Teacher(
    @PrimaryKey val staffId: String,
    val staffName: String,
    val fingerType: String?,
    val embedding: String?
)
```

This supports exact queries:

```sql
SELECT i.*
FROM institutes i
INNER JOIN teacher_institute_map tim ON tim.instId = i.Id
WHERE tim.teacherId = :teacherId
```

If possible, implement the mapping table now and expose a comma-separated value
only when a legacy API requires it.

## 7. Other improvements needed

### 7.1 Make cached academic data institute-aware

Several tables use only a server ID as their primary key and can overwrite data
when different institutes reuse the same ID. Review at least:

- `students` (`studentId`);
- `classes` (`classId`);
- `courses` (`courseId`);
- `subjects` (`subjectId`);
- `teacher_class_map` (`teacherId`, `classId`);
- `course_periods`;
- `attendance_codes` (`atcCode`);
- `program_config` (`title`);
- `ActiveClassCycle` (`classroomId`).

Where IDs are not guaranteed globally unique, add `instId` and use a composite
primary key such as `(instId, classId)`. All joins and DAO queries must include
`instId`.

`teacher_class_map` especially should become:

```text
(teacherId, instId, classId)
```

Otherwise a class assignment from one institute can authorize attendance in
another.

### 7.2 Include institute in active-session identity

The in-memory map currently uses `(classroomId, teacherId)`, and
`ActiveClassCycle` uses only `classroomId` as its primary key. For
multi-institute operation, use:

```text
(instId, classroomId, teacherId)
```

This avoids collisions when institute databases reuse classroom or teacher IDs.

### 7.3 Scope face recognition candidates

Teacher face matching currently loads all teachers. Once the class/institute
relationship is reliable, filter candidates to teachers assigned to the
scanned class and selected device institutes. After recognition, still require
institute selection when that teacher has multiple valid choices.

### 7.4 Preserve institute while resuming

An incomplete session already stores `instId`. Resume that exact institute and
display it to the teacher. Do not ask the teacher to choose another institute
for a resumed session.

Pending-session checks should normally be scoped to the institute chosen for
the new session. Product policy must decide whether a pending session in
institute `I1` should block a new session in `I2`; implement that policy
explicitly rather than accidentally.

### 7.5 Improve attendance IDs

`AttendanceIdGenerator` is an in-memory counter that resets when the process is
restarted. It can produce duplicate primary keys and replace or reject
attendance. Use a UUID or a durable server-compatible unique ID.

### 7.6 Add database constraints and diagnostics

- Reject blank `Session.instId` before insertion.
- Reject blank teacher, class, student, and session IDs.
- Add an index to `sessions(instId, teacherId, endTime)`.
- Add an index to `attendance(sessionId, instId)`.
- Record selected institute ID and name in session-start logs.
- Never log face embeddings, credentials, or complete sensitive payloads.
- Display the active institute name on the student-scan and review screens.

### 7.7 Make sync atomic

Stage the downloaded data, validate it, and commit it in one Room transaction
per full sync. Do not clear live mapping tables before replacement data is
ready. A failed partial sync must not leave teacher membership and class
mapping from different snapshots.

## 8. Recommended implementation order

1. Add and test the institute ID parser/encoder.
2. Merge all institute memberships for duplicate teacher IDs during sync.
3. Rename DAO methods that return multiple encoded institute IDs.
4. Add the single-choice institute dialog after teacher recognition.
5. Pass `selectedInstId` explicitly through session creation.
6. Remove teacher-institute re-queries from session creation.
7. Validate student, class, course, and period against `Session.instId`.
8. Scope incomplete-session checks and resume by the session institute.
9. Add payload consistency validation before upload.
10. Add institute to teacher-class and active-session identity.
11. Migrate to `teacher_institute_map` when possible.
12. Replace the attendance counter with a UUID.

## 9. Acceptance tests

### Teacher sync

- Sync teacher `T101` from `I1`, then `I2`: stored memberships are `I1,I2`.
- Sync in reverse order: the result is still the same canonical set.
- Re-sync one institute: no duplicate ID is added.
- An empty API embedding does not erase a valid local embedding.
- Removed server membership is removed after an authoritative full sync.

### Session start

- A teacher with one valid institute starts with that institute.
- A teacher with two institutes must choose exactly one.
- The dialog displays institute names and retains the correct IDs.
- Cancelling the dialog creates no session.
- No valid institute blocks session creation.
- The chosen institute determines the available periods/classes/courses.

### Attendance

- A session started for `I1` stores `Session.instId = I1`.
- Students from `I1` can be marked.
- Students from `I2` are rejected in an `I1` session.
- Every attendance row matches its session institute.
- The upload payload contains `I1`, even after teacher data is later synced in
  a different order.

### Restart and resume

- Restarting the app preserves the original session institute.
- Resuming an incomplete `I1` session does not ask for or switch to `I2`.
- A delayed offline upload uses the institute captured at session creation.

### Collision testing

- Identical class or course IDs in two institutes do not mix cached data.
- Active sessions with equivalent local IDs in different institutes remain
  separate.

## 10. Definition of done

The issue is fixed when the institute is explicitly selected before a
multi-institute teacher starts a session, stored permanently in
`Session.instId`, validated throughout the workflow, and used unchanged in the
attendance upload. Sync order must no longer affect attendance institute data.

