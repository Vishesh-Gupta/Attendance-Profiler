# Attendance Profiler

An Android app (Kotlin + Jetpack Compose + Firebase) for running check-in at recurring events like
Hack the North, and profiling everyone by their attendance history across events.

Organizers, participants, judges and volunteers all use the same app. What each person can do
depends on their role.

## Roles

| | Participant | Judge | Volunteer | Organizer |
|---|:-:|:-:|:-:|:-:|
| Sign up, show a QR pass, see own attendance | ✓ | ✓ | ✓ | ✓ |
| Register for upcoming events | ✓ | ✓ | ✓ | ✓ |
| See people, attendance stats and profiles | | ✓ | ✓ | ✓ |
| Check people in (QR scan or manual search), undo check-ins | | | ✓ | ✓ |
| Create and delete events, change people's roles | | | | ✓ |

Everyone signs up as a **participant**. Organizers promote people from the person's profile
(People tab → tap someone → *Change role*). The Firestore security rules in
`firebase/firestore.rules` enforce this table on the server, so a modified client can't get around it.

## Features

- **Sign up / sign in** with email and password, including password reset.
- **My pass**: a personal QR code shown at full screen brightness, plus your own attendance history.
- **Events**: everyone sees the event list and can register for upcoming events. Organizers create
  and delete events.
- **QR check-in desk** (volunteers and organizers): tap *Scan QR pass* and point the camera at
  someone's pass. A large green, amber or red card shows who was checked in, who is already in, or
  that the code isn't valid. Anyone without their phone can be found by name or email and checked in
  manually. Each person can only check in once per event.
- **Event attendance** (staff): checked in, registered, not yet arrived, first-timers, returning,
  walk-ins (checked in without registering), a breakdown by role, and a live list of arrivals.
- **People directory** (staff): search, filter by role, and sort by name, events attended,
  attendance rate or no-shows.
- **Attendee profiles**: events attended, attendance rate, no-shows, current and longest streak,
  first and last seen, a tier (First-timer / Returning / Veteran), and a timeline of every event.

## How profiling works

The logic is in `profiling/AttendanceProfiler.kt`. It's plain Kotlin and has unit tests.

- A person's history starts at their **first engagement**: the first event they checked in to, or
  registered for and missed. Events before that don't count against them.
- A **no-show** is a past event they registered for but never checked in to. Events happening today
  or later never count as missed.
- **Attendance rate** = events attended ÷ eligible events since first engagement.
- **Tiers**: 1 event is First-timer, 2 to 3 is Returning, 4 or more is Veteran.
- At an event, someone is a **first-timer** if they have no check-in at any earlier event.

## Backend: Firebase

Data lives in **Cloud Firestore**, and accounts use **Firebase Authentication**.

```
users/{uid}                              name, email, organization, role, createdAt
events/{eventId}                         name, location, startEpochDay, createdBy, createdAt
events/{eventId}/checkIns/{uid}          userId, checkedInAt, checkedInBy, method (QR | MANUAL)
events/{eventId}/registrations/{uid}     userId, registeredAt
```

Using the attendee's uid as the document id gives each person at most one check-in and one
registration per event.

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
├── profiling/   AttendanceProfiler: attendee profiles and event summaries
└── ui/          Compose screens: auth, pass, events + check-in desk, people
firebase/        Firestore security rules, indexes and their tests
```

## Building and testing

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 26). QR scanning uses Google's code
scanner, which needs Google Play services on the device and no camera permission.

```
./gradlew assembleDebug         # build the APK
./gradlew testDebugUnitTest     # profiler, QR pass and role unit tests
cd firebase && npm install && npm test   # security rules tests (runs the Firestore emulator; needs Java)
```
