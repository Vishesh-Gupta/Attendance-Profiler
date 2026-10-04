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
| Own profile, QR pass, own attendance, event calendar, apply to events | ✓ | ✓ | ✓ | ✓ |
| Create, join or switch team projects at events they're approved for | ✓ | | | |
| See published results; teams see their own scores and notes | ✓ | ✓ | ✓ | ✓ |
| See and score the projects assigned to them (only their own scores) | | ✓ | | |
| Check people in (QR or manual), see arrivals; participants must be approved | | | ✓ | ✓ |
| See everyone: people directory, attendance profiles, event stats, all scores, leaderboard | | | | ✓ |
| Review applications (approve, waitlist, decline); applicants are emailed | | | | ✓ |
| Assign judges, publish results, create/delete events, remove projects, change roles | | | | ✓ |

Everyone signs up as a **participant**. Organizers promote people from the person's profile
(People tab → tap someone → *Change role*). The Firestore security rules in
`firebase/firestore.rules` enforce this table on the server, so a modified client can't get around it.

## Features

- **Sign in / create account** with email and password, including password reset.
- **Profile**: your details (editable), a full-brightness QR check-in pass, your attendance history,
  and a role-specific section:
  - Participants: your team projects.
  - Judges: how many projects you've been assigned, scored and have left.
  - Volunteers: how many people you've checked in, total and today.
  - Organizers: an overview of people by role, upcoming events, check-ins and projects.
- **Events: list or calendar.** The calendar is a month grid with a dot on days that have events.
  Multi-day events (like a hackathon weekend) appear on every day they span. Tap a day to see its
  events. Organizers can tap + to create an event starting on the selected day, then pick its
  dates from a calendar.
- **Applications**: registering for an event is an application that organizers review by hand.
  - Applicants see their status on the event page: *Pending review*, *Approved*, *Waitlisted* or
    *Declined*. They can withdraw; applying again puts them back in the queue.
  - Organizers open **Applications** from the event page and filter by status. Each applicant
    shows their attendance history from other events (tier, attendance rate, no-shows) to help
    decide. Approve, Waitlist and Decline each ask for confirmation, and Undo moves a decision
    back to pending.
  - **Each decision is emailed to the applicant automatically**: the `onRegistrationDecision` Cloud
    Function queues the email and the Firebase Trigger Email extension sends it. Organizers can see
    whether the email has gone out ("Emailed: approved").
  - **Only approved people continue.** Only approved participants can form or join teams, and
    participants can't be checked in unless they're approved. Judges, volunteers and organizers
    can be checked in without applying.
  - Organizers can approve someone on the spot from the check-in desk ("Approve & check in"),
    even if they never applied.
- **QR check-in desk** (volunteers and organizers): scan someone's pass, or search for them by name
  if they don't have their phone. A large green, amber or red card shows the result. Each person can
  only check in once per event.
- **Teams**: everyone registers with their own account, then builds a project together. Teams can
  form as soon as people are approved; nobody has to wait for check-in. One teammate creates the team
  project and gets a **team code** (like `ABCDE-FGHJK`) with a QR code and a Share button.
  Teammates join by scanning that QR code or typing the code. Rules:
  - Teams have at most 4 members.
  - You must be registered (or checked in as a walk-in) to create or join a team.
  - Each person can be on only one team per event. To switch, leave your current team first, then
    join or start another.
  - Any member can edit the project until the event ends. The last member to leave deletes the
    project.
- **Judge assignment** (organizers): *Auto-assign judges* gives every project the chosen number of
  judges and balances the load across judges, keeping any existing assignments. Organizers can also
  assign or unassign judges by hand on each project. Judges only see and score the projects
  assigned to them.
- **Judging**: judges get a to-do list with progress for each event. They score each project 1–10
  on Innovation, Technical difficulty, Design and Impact, plus notes. Judges can't see each other's
  scores.
- **Leaderboard** (organizers): projects ranked by average total across judges (ties share a rank),
  showing how many assigned judges have scored each one. Tap a project to see every judge's
  breakdown.
- **Published results**: organizers publish a snapshot of the leaderboard, and can update or
  unpublish it later. Once results are published:
  - Everyone can see the rankings from the event page.
  - Each team also sees its own per-criterion averages and judges' notes. Judges are shown as
    "Judge 1", "Judge 2" and so on, not by name.
- **People** (organizers): search, filter by role, sort by attendance, and open anyone's profile
  with their attendance history and projects.

## How profiling works

The logic is in `profiling/AttendanceProfiler.kt`. It's plain Kotlin and has unit tests.

- A person's history starts at their **first engagement**: the first event they checked in to, or
  registered for and missed. Events before that don't count against them.
- A **no-show** is an event that has ended, which they were approved for but never checked in to.
  Pending, waitlisted and declined applications never count against anyone.
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
events/{eventId}/registrations/{uid}              userId, registeredAt, status (PENDING | APPROVED |
                                                  WAITLISTED | DECLINED), reviewedBy, reviewedAt,
                                                  notifiedStatus, notifiedAt
events/{eventId}/projects/{teamCode}              title, description, link, memberIds,
                                                  members {uid: name}, createdBy, assignedJudges,
                                                  createdAt, updatedAt
events/{eventId}/projects/{teamCode}/scores/{judgeUid}
                                                  judgeId, innovation, technical, design, impact,
                                                  comment, updatedAt
events/{eventId}/teamMembers/{uid}                projectId
events/{eventId}/results/leaderboard              entries, publishedAt, publishedBy
mail/{id}                                         outgoing email (Cloud Functions only)
```

Using people's uids as document ids gives each person at most one check-in and registration per
event, and each judge one score per project. `teamMembers/{uid}` can only be created once, which
limits each person to one team per event. The rules use `getAfter()` to require that every write
changing a team updates the project and these records together.

### One-time setup

1. Create a project in the [Firebase console](https://console.firebase.google.com/).
2. **Authentication** → Sign-in method → enable **Email/Password**.
3. **Firestore Database** → create a database.
4. **Project settings** → add an Android app with package name `com.example.android.htn`, download
   `google-services.json` and put it in `app/`. Without this file the app still builds, but it only
   shows setup instructions.
5. **Email** (for application decisions). This needs the pay-as-you-go **Blaze** plan, because it
   uses Cloud Functions and an extension.
   - Upgrade the project to Blaze in the Firebase console.
   - Get SMTP credentials from an email provider (for example SendGrid, Mailgun, or a Gmail app
     password).
   - Install the Trigger Email extension, and set its collection to `mail` and its SMTP connection
     to your provider:
     ```
     cd firebase
     npx firebase ext:install firebase/firestore-send-email
     ```
6. Deploy the security rules, indexes and the email function:
   ```
   cd firebase
   npm install && (cd functions && npm install)
   npx firebase login
   npx firebase use --add        # pick your project
   npm run deploy
   ```
7. **Create the first organizer**: sign up in the app, then in the Firebase console open
   Firestore → `users` → your document and change `role` to `ORGANIZER`. After that, promote
   everyone else from inside the app.

## Project layout

```
app/src/main/java/com/example/android/htn/
├── data/        Firebase auth + Firestore repositories, models, QR pass format
├── profiling/   AttendanceProfiler (attendance profiles, event stats) and Judging (leaderboard)
└── ui/          Compose screens: auth, profile, events + calendar + check-in desk, projects, people
firebase/        Firestore security rules, indexes, the email Cloud Function, and their tests
```

## Building and testing

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 26). QR scanning uses Google's code
scanner, which needs Google Play services on the device and no camera permission.

```
./gradlew assembleDebug         # build the APK
./gradlew testDebugUnitTest     # profiler, judging, calendar, QR pass and role unit tests
cd firebase && npm install && (cd functions && npm install) && npm test
                                # security rules, email templates and the email function
                                # (runs the Firestore and Functions emulators; needs Java)
```
