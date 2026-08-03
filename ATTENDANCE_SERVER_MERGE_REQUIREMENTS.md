# Attendance Server Merge and Period Update Requirements

## Goal

Attendance for an already submitted period must be updateable. Selecting a submitted period must never block the teacher.

The server attendance for the exact selection is the baseline. Only a face capture or an explicit teacher edit may change that baseline.

## Exact Attendance Selection

Attendance is resolved using all of these values:

- Institute ID
- Academic year
- Marking period ID (`mpId`)
- Class ID
- Course-period ID (`cpId`)
- Today's date
- School-period ID and title

Only one school period can be selected at a time. The report column must exactly match today's date and the selected school-period title, for example `2026-08-03(Afternoon Session)`. A different or first available period column must never be used as a fallback.

## Server Check

After the teacher selects a school period, call the `landscapeMusterAttSubjectPeriodWise` attendance report for each exact class/course selection.

- `SUCCESS` with matching period data means existing server attendance must be used as the baseline.
- `FAILED`, `data: false`, and `Attendance record details is not found` means this is fresh attendance.
- A network, HTTP, authentication, or parsing error is not the same as “not found.” Keep the attendance pending locally in that case.

## Merge Precedence

For each student, apply rules in this order:

1. An explicit teacher edit wins and may set `P`, `L`, `E`, or `A`.
2. A student captured by face in the current session becomes `P`.
3. Otherwise, preserve the existing server status (`P`, `L`, `E`, or `A`).
4. If no server attendance exists, an unscanned and unedited student is `A`.

An automatically created local `A` is only a roster default. It must never overwrite a server `P`, `L`, or `E`. A teacher explicitly choosing `A` in Edit Attendance is an override and must be submitted.

Example baseline:

- Server: `3P, 2L, 1E, 4A`
- Four students are face-captured: one existing `P`, two existing `A`, and the existing `E`
- Final: `6P, 2L, 0E, 2A`

## Local Persistence and Screens

- Room must contain exactly one attendance object per student for the selected attendance selection.
- Persist whether a row was face-captured and whether its status was explicitly edited.
- Save the merged server baseline before opening the overview so Overview and Edit display the correct current statuses.
- Edit Attendance must allow all four statuses for every student.
- Overview must refresh all four counts after an edit.

## Submission and Offline Sync

Before direct submission, fetch the server baseline again and reapply the same precedence rules.

When offline, retain the complete local roster plus face-capture and explicit-edit intent. During sync, first fetch the server baseline for the exact selection and then apply the same merge rules. If the report says attendance was not found, submit the local attendance as fresh. If the report cannot be checked because of a technical error, keep the session pending.

Face scanning, Mass Bunk, class/subject setup, and the existing server payload format must otherwise remain unchanged.
