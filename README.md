# Attendance Profiler

An Android app (Kotlin + Jetpack Compose) for checking people in at recurring events such as
Hack the North, and profiling each person by their attendance history across events.

## Features

- **Events**: create events (name, location, date) and see how many people checked in to each.
- **Check-in desk**: search existing attendees by name, email or school and check them in with
  one tap, or register a walk-up attendee. Attendees are identified by email, so someone who
  registers again at a later event keeps a single profile. Duplicate check-ins are blocked, and a
  check-in can be undone.
- **Event summary**: checked-in count, first-timers and returning attendees, and a breakdown by role
  (Hacker, Mentor, Volunteer, Organizer, Sponsor).
- **Attendee profiles**: events attended, attendance rate, current and longest streak, first and
  last seen, a tier (First-timer, Returning, Veteran), and a timeline of attended and missed events.
- **Attendee directory**: search everyone and sort by name, most events, or attendance rate.

## How profiling works

The logic is in `profiling/AttendanceProfiler.kt`. It's plain Kotlin and has unit tests.

- Only events from a person's **first check-in onward** count toward their attendance rate, so a
  first-timer at HTN 2026 isn't penalised for missing HTN 2022.
- Events dated in the future don't count until the person checks in to them.
- **Current streak** is the number of most recent eligible events attended in a row. **Longest
  streak** is the best run of consecutive events they attended.
- **Tiers**: 1 event is First-timer, 2 to 3 is Returning, 4 or more is Veteran.
- An attendee counts as a **first-timer at an event** if they have no check-in at any earlier event.

## Project layout

```
app/src/main/java/com/example/android/htn/
├── data/        Room entities, DAO, database, repository
├── profiling/   AttendanceProfiler: attendee profiles and event summaries
└── ui/          Compose screens (events, event check-in, attendees, attendee profile)
```

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 26).

```
./gradlew assembleDebug           # build the APK
./gradlew testDebugUnitTest       # profiler unit tests
./gradlew connectedDebugAndroidTest  # Room/repository tests (needs a device or emulator)
```
