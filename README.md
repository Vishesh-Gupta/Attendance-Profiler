# Attendance Profiler

An Android app (Kotlin + Jetpack Compose + Firebase) for running check-in at recurring events like
Hack the North, and profiling everyone by their attendance history across events.

Organizers, participants, judges and volunteers all use the same app. What each person can do
depends on their role.

## Roles

After signing in, everyone lands on their **Profile**. Each role gets its own tabs and only sees
data that's meant for it.

| | Participant | Judge | Volunteer | Organizer |
|---|:-:|:-:|:-:|:-:|
| Tabs | Profile, Events, My projects | Profile, Events, Judging | Profile, Events | Profile, Events, People, Projects |
| Own profile, QR pass, own attendance, event calendar, register for events | ✓ | ✓ | ✓ | ✓ |
| Submit a project for an event they were checked in to | ✓ | | | |
| See every project submission | | ✓ | | ✓ |
| Score projects (only their own scores are visible to them) | | ✓ | | |
| Check people in (QR or manual), see arrivals | | | ✓ | ✓ |
| See everyone: people directory, attendance profiles, event stats, all scores, leaderboard | | | | ✓ |
| Create/delete events, remove projects, change roles | | | | ✓ |

Everyone signs up as a **participant**. Organizers promote people from the person's profile
(People tab → tap someone → *Change role*). The Firestore security rules in
`firebase/firestore.rules` enforce this table on the server, so a modified client can't get around it.

## Features

- **Sign in / create account** with email and password, including password reset.
- **Profile**: your details (editable), a full-brightness QR check-in pass, your attendance history,
  and a role-specific section:
  - Participants: projects submitted.
  - Judges: how many projects you've scored and how many are left.
  - Volunteers: how many people you've checked in, total and today.
  - Organizers: an overview of people by role, upcoming events, check-ins and projects.
- **Events: list or calendar.** The calendar is a month grid with a dot on days that have events.
  Multi-day events (like a hackathon weekend) appear on every day they span. Tap a day to see its
  events. Organizers can tap + to create an event starting on the selected day.
- **QR check-in desk** (volunteers and organizers): scan someone's pass, or search for them by name
  if they don't have their phone. A large green, amber or red card shows the result. Each person can
  only check in once per event.
- **Project submissions**: once checked in, participants submit their project (name, description,
  link, teammates) from the event page. They can edit or withdraw it until the event ends.
- **Judging**: judges see each event's projects with what they've scored and what's left. They score
  each project from 1 to 10 on Innovation, Technical difficulty, Design and Impact, plus notes. Judges
  can't see each other's scores.
- **Leaderboard** (organizers): projects ranked by average total across judges. Ties share a rank.
  Tap a project to see every judge's breakdown and notes.
- **People** (organizers): search, filter by role, sort by attendance, and open anyone's profile
  with their attendance history and projects.

## How profiling works

The logic is in `profiling/AttendanceProfiler.kt`. It's plain Kotlin and has unit tests.

- A person's history starts at their **first engagement**: the first event they checked in to, or
  registered for and missed. Events before that don't count against them.
- A **no-show** is an event that has ended, which they registered for but never checked in to.
  Events that are still running or haven't started never count as missed.
- **Attendance rate** = events attended ÷ eligible events since first engagement.
- **Tiers**: 1 event is First-timer, 2 to 3 is Returning, 4 or more is Veteran.
- At an event, someone is a **first-timer** if they have no check-in at any earlier event.

## Backend: Firebase

Data lives in **Cloud Firestore**, and accounts use **Firebase Authentication**.

```
users/{uid}                                       name, email, organization, role, createdAt
events/{eventId}                                  name, location, description, startEpochDay,
                                                  endEpochDay, createdBy, createdAt
events/{eventId}/checkIns/{uid}                   userId, checkedInAt, checkedInBy, method (QR | MANUAL)
events/{eventId}/registrations/{uid}              userId, registeredAt
events/{eventId}/projects/{uid}                   title, description, link, teamMembers,
                                                  submittedBy, submitterName, createdAt, updatedAt
events/{eventId}/projects/{uid}/scores/{judgeUid} judgeId, innovation, technical, design, impact,
                                                  comment, updatedAt
```

Using people's uids as document ids gives each person at most one check-in, registration and
project per event, and each judge one score per project.

### One-time setup

1. Create a project in the [Firebase console](https://console.firebase.google.com/).
2. **Authentication** → Sign-in method → enable **Email/Password**.
3. **Firestore Database** → create a database.
4. **Project settings** → add an Android app with package name `com.example.android.htn`, download
   `google-services.json` and put it in `app/`. Without this file the app still builds, but it only
   shows setup instructions.
5. Deploy the security rules and indexes:
   ```
   cd firebase
   npm install
   npx firebase login
   npx firebase use --add        # pick your project
   npm run deploy
   ```
6. **Create the first organizer**: sign up in the app, then in the Firebase console open
   Firestore → `users` → your document and change `role` to `ORGANIZER`. After that, promote
   everyone else from inside the app.

## Project layout

```
app/src/main/java/com/example/android/htn/
├── data/        Firebase auth + Firestore repositories, models, QR pass format
├── profiling/   AttendanceProfiler (attendance profiles, event stats) and Judging (leaderboard)
└── ui/          Compose screens: auth, profile, events + calendar + check-in desk, projects, people
firebase/        Firestore security rules, indexes and their tests
```

## Building and testing

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 26). QR scanning uses Google's code
scanner, which needs Google Play services on the device and no camera permission.

```
./gradlew assembleDebug         # build the APK
./gradlew testDebugUnitTest     # profiler, judging, calendar, QR pass and role unit tests
cd firebase && npm install && npm test   # security rules tests (runs the Firestore emulator; needs Java)
```
