import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  arrayRemove, arrayUnion, collection, collectionGroup, deleteDoc, deleteField, doc, getDoc, getDocs, query,
  serverTimestamp, setDoc, updateDoc, where, writeBatch,
} from 'firebase/firestore';

let env;
const TODAY = Math.floor(Date.now() / 86400000);

const people = {
  org: 'ORGANIZER',
  vol: 'VOLUNTEER',
  judge: 'JUDGE',
  ada: 'PARTICIPANT',
  bob: 'PARTICIPANT',
};

const db = (uid) => env.authenticatedContext(uid, { email: `${uid}@example.com` }).firestore();

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-attendance',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});

after(() => env.cleanup());

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    for (const [uid, role] of Object.entries(people)) {
      await setDoc(doc(admin, 'users', uid), {
        name: uid, email: `${uid}@example.com`, organization: '', role, createdAt: new Date(),
      });
    }
    await setDoc(doc(admin, 'events/htn'), {
      name: 'HTN', location: 'Waterloo', description: '', startEpochDay: TODAY - 1, endEpochDay: TODAY + 1,
      createdBy: 'org', createdAt: new Date(),
    });
    await setDoc(doc(admin, 'events/old'), {
      name: 'Old', location: '', description: '', startEpochDay: TODAY - 30, endEpochDay: TODAY - 28,
      createdBy: 'org', createdAt: new Date(),
    });
    await setDoc(doc(admin, 'events/old/checkIns/ada'), {
      userId: 'ada', checkedInAt: new Date(), checkedInBy: 'vol', method: 'QR',
    });
    await setDoc(doc(admin, 'events/htn/checkIns/bob'), {
      userId: 'bob', checkedInAt: new Date(), checkedInBy: 'vol', method: 'QR',
    });
  });
});

const newProfile = (uid, overrides = {}) => ({
  name: 'New', email: `${uid}@example.com`, organization: 'UW', role: 'PARTICIPANT',
  createdAt: serverTimestamp(), ...overrides,
});

const checkIn = (by, userId, overrides = {}) => ({
  userId, checkedInAt: serverTimestamp(), checkedInBy: by, method: 'QR', ...overrides,
});

describe('sign up', () => {
  test('new user can create their own participant profile', async () => {
    await assertSucceeds(setDoc(doc(db('newbie'), 'users/newbie'), newProfile('newbie')));
  });

  test('cannot sign up with an elevated role', async () => {
    await assertFails(setDoc(doc(db('newbie'), 'users/newbie'), newProfile('newbie', { role: 'ORGANIZER' })));
  });

  test('cannot create a profile for someone else or with a different email', async () => {
    await assertFails(setDoc(doc(db('newbie'), 'users/other'), newProfile('other')));
    await assertFails(setDoc(doc(db('newbie'), 'users/newbie'),
      newProfile('newbie', { email: 'spoof@example.com' })));
  });

  test('unauthenticated users cannot read anything', async () => {
    const anon = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(anon, 'users/ada')));
    await assertFails(getDoc(doc(anon, 'events/htn')));
  });
});

describe('roles', () => {
  test('participants cannot promote themselves', async () => {
    await assertFails(updateDoc(doc(db('ada'), 'users/ada'), { role: 'ORGANIZER' }));
  });

  test('participants can edit their own name', async () => {
    await assertSucceeds(updateDoc(doc(db('ada'), 'users/ada'), { name: 'Ada L' }));
  });

  test('organizers can change other people\'s roles but not their own', async () => {
    await assertSucceeds(updateDoc(doc(db('org'), 'users/ada'), { role: 'VOLUNTEER' }));
    await assertFails(updateDoc(doc(db('org'), 'users/ada'), { role: 'SUPERUSER' }));
    await assertFails(updateDoc(doc(db('org'), 'users/org'), { role: 'PARTICIPANT' }));
  });

  test('volunteers cannot change roles', async () => {
    await assertFails(updateDoc(doc(db('vol'), 'users/ada'), { role: 'VOLUNTEER' }));
  });

  test('participants can only read their own profile', async () => {
    await assertSucceeds(getDoc(doc(db('ada'), 'users/ada')));
    await assertFails(getDoc(doc(db('ada'), 'users/bob')));
  });

  test('organizers and volunteers can read everyone; judges cannot', async () => {
    await assertSucceeds(getDoc(doc(db('org'), 'users/ada')));
    await assertSucceeds(getDoc(doc(db('vol'), 'users/ada')));
    await assertFails(getDoc(doc(db('judge'), 'users/ada')));
    await assertSucceeds(getDoc(doc(db('judge'), 'users/judge')));
  });
});

describe('events', () => {
  const event = (by) => ({
    name: 'HTN 2026', location: 'Waterloo', description: 'Hackathon', startEpochDay: 200, endEpochDay: 202,
    createdBy: by, createdAt: serverTimestamp(),
  });

  test('only organizers create and delete events', async () => {
    await assertSucceeds(setDoc(doc(db('org'), 'events/new'), event('org')));
    await assertFails(setDoc(doc(db('vol'), 'events/new2'), event('vol')));
    await assertFails(deleteDoc(doc(db('vol'), 'events/htn')));
    await assertSucceeds(deleteDoc(doc(db('org'), 'events/htn')));
  });

  test('events must end on or after they start', async () => {
    await assertFails(setDoc(doc(db('org'), 'events/bad'), { ...event('org'), endEpochDay: 199 }));
  });

  test('any signed-in user can read events', async () => {
    await assertSucceeds(getDoc(doc(db('ada'), 'events/htn')));
  });
});

const approve = (admin, eventId, uid, status = 'APPROVED') =>
  setDoc(doc(admin, `events/${eventId}/registrations/${uid}`), { userId: uid, registeredAt: new Date(), status });

describe('check-ins', () => {
  beforeEach(() => env.withSecurityRulesDisabled((ctx) => approve(ctx.firestore(), 'htn', 'ada')));

  test('participants must be approved before they can be checked in', async () => {
    await env.withSecurityRulesDisabled((ctx) => approve(ctx.firestore(), 'htn', 'ada', 'WAITLISTED'));
    await assertFails(setDoc(doc(db('vol'), 'events/htn/checkIns/ada'), checkIn('vol', 'ada')));
    await env.withSecurityRulesDisabled((ctx) => deleteDoc(doc(ctx.firestore(), 'events/htn/registrations/ada')));
    await assertFails(setDoc(doc(db('vol'), 'events/htn/checkIns/ada'), checkIn('vol', 'ada')));
  });

  test('judges, volunteers and organizers can be checked in without applying', async () => {
    await assertSucceeds(setDoc(doc(db('org'), 'events/htn/checkIns/judge'), checkIn('org', 'judge')));
    await assertSucceeds(setDoc(doc(db('org'), 'events/htn/checkIns/vol'), checkIn('org', 'vol')));
  });

  test('volunteers and organizers can check people in', async () => {
    await assertSucceeds(setDoc(doc(db('vol'), 'events/htn/checkIns/ada'), checkIn('vol', 'ada')));
    await assertSucceeds(setDoc(doc(db('org'), 'events/htn/checkIns/judge'), checkIn('org', 'judge', { method: 'MANUAL' })));
  });

  test('participants and judges cannot check anyone in, including themselves', async () => {
    await assertFails(setDoc(doc(db('ada'), 'events/htn/checkIns/ada'), checkIn('ada', 'ada')));
    await assertFails(setDoc(doc(db('judge'), 'events/htn/checkIns/ada'), checkIn('judge', 'ada')));
  });

  test('check-ins must be attributed to the scanner and reference a real user', async () => {
    await assertFails(setDoc(doc(db('vol'), 'events/htn/checkIns/ada'), checkIn('org', 'ada')));
    await assertFails(setDoc(doc(db('vol'), 'events/htn/checkIns/ghost'), checkIn('vol', 'ghost')));
    await assertFails(setDoc(doc(db('vol'), 'events/htn/checkIns/ada'), checkIn('vol', 'bob')));
  });

  test('an existing check-in cannot be overwritten', async () => {
    await assertFails(setDoc(doc(db('vol'), 'events/htn/checkIns/bob'), checkIn('vol', 'bob')));
  });

  test('volunteers can undo check-ins; participants cannot', async () => {
    await assertFails(deleteDoc(doc(db('bob'), 'events/htn/checkIns/bob')));
    await assertSucceeds(deleteDoc(doc(db('vol'), 'events/htn/checkIns/bob')));
  });

  test('participants can query only their own check-ins', async () => {
    await assertSucceeds(getDocs(query(collectionGroup(db('bob'), 'checkIns'), where('userId', '==', 'bob'))));
    await assertFails(getDocs(collectionGroup(db('bob'), 'checkIns')));
    await assertFails(getDoc(doc(db('ada'), 'events/htn/checkIns/bob')));
  });

  test('organizers and volunteers can read all check-ins; judges cannot', async () => {
    await assertSucceeds(getDocs(collectionGroup(db('org'), 'checkIns')));
    await assertSucceeds(getDocs(collectionGroup(db('vol'), 'checkIns')));
    await assertFails(getDocs(collectionGroup(db('judge'), 'checkIns')));
  });
});

describe('registrations', () => {
  const registration = (uid, status = 'PENDING') => ({ userId: uid, registeredAt: serverTimestamp(), status });
  const decide = (status, by = 'org') => ({ status, reviewedBy: by, reviewedAt: serverTimestamp() });

  test('applications start as pending; applicants cannot approve themselves', async () => {
    await assertFails(setDoc(doc(db('ada'), 'events/htn/registrations/ada'), registration('ada', 'APPROVED')));
    await assertSucceeds(setDoc(doc(db('ada'), 'events/htn/registrations/ada'), registration('ada')));
    await assertFails(updateDoc(doc(db('ada'), 'events/htn/registrations/ada'), decide('APPROVED', 'ada')));
  });

  test('only organizers decide on applications', async () => {
    await setDoc(doc(db('ada'), 'events/htn/registrations/ada'), registration('ada'));
    await assertFails(updateDoc(doc(db('vol'), 'events/htn/registrations/ada'), decide('APPROVED', 'vol')));
    await assertFails(updateDoc(doc(db('judge'), 'events/htn/registrations/ada'), decide('APPROVED', 'judge')));
    for (const status of ['APPROVED', 'WAITLISTED', 'DECLINED', 'PENDING']) {
      await assertSucceeds(updateDoc(doc(db('org'), 'events/htn/registrations/ada'), decide(status)));
    }
    await assertFails(updateDoc(doc(db('org'), 'events/htn/registrations/ada'), decide('MAYBE')));
    await assertFails(updateDoc(doc(db('org'), 'events/htn/registrations/ada'), decide('APPROVED', 'vol')));
    await assertFails(updateDoc(doc(db('org'), 'events/htn/registrations/ada'), { ...decide('APPROVED'), userId: 'bob' }));
  });

  test('organizers can approve someone who never applied; volunteers cannot', async () => {
    const onBehalf = (by) => ({ userId: 'ada', registeredAt: serverTimestamp(), status: 'APPROVED', reviewedBy: by, reviewedAt: serverTimestamp() });
    await assertFails(setDoc(doc(db('vol'), 'events/htn/registrations/ada'), onBehalf('vol')));
    await assertFails(setDoc(doc(db('org'), 'events/htn/registrations/ghost'), { ...onBehalf('org'), userId: 'ghost' }));
    await assertSucceeds(setDoc(doc(db('org'), 'events/htn/registrations/ada'), onBehalf('org')));
  });

  test('cannot apply to an event that is over', async () => {
    await assertFails(setDoc(doc(db('ada'), 'events/old/registrations/ada'), registration('ada')));
  });

  test('users register themselves only', async () => {
    await assertSucceeds(setDoc(doc(db('ada'), 'events/htn/registrations/ada'), registration('ada')));
    await assertFails(setDoc(doc(db('ada'), 'events/htn/registrations/bob'), registration('bob')));
  });

  test('cannot register for an event that does not exist', async () => {
    await assertFails(setDoc(doc(db('ada'), 'events/nope/registrations/ada'), registration('ada')));
  });

  test('users can cancel their own registration', async () => {
    await assertSucceeds(setDoc(doc(db('ada'), 'events/htn/registrations/ada'), registration('ada')));
    await assertSucceeds(deleteDoc(doc(db('ada'), 'events/htn/registrations/ada')));
  });

  test('organizers can remove anyone\'s registration (deleting an event); others cannot', async () => {
    await assertSucceeds(setDoc(doc(db('ada'), 'events/htn/registrations/ada'), registration('ada')));
    await assertFails(deleteDoc(doc(db('vol'), 'events/htn/registrations/ada')));
    await assertFails(deleteDoc(doc(db('bob'), 'events/htn/registrations/ada')));
    await assertSucceeds(deleteDoc(doc(db('org'), 'events/htn/registrations/ada')));
  });

  test('participants cannot list other people\'s registrations', async () => {
    await assertFails(getDocs(collectionGroup(db('ada'), 'registrations')));
    await assertSucceeds(getDocs(query(collectionGroup(db('ada'), 'registrations'), where('userId', '==', 'ada'))));
  });
});

// --- Teams, projects, judging, results -------------------------------------------------------

const checkInAll = (admin, eventId, uids) => {
  return Promise.all(uids.map((uid) => setDoc(doc(admin, `events/${eventId}/checkIns/${uid}`), {
    userId: uid, checkedInAt: new Date(), checkedInBy: 'vol', method: 'QR',
  })));
};

// Same writes the app makes (AttendanceRepository.createProject / joinTeam / leaveTeam).
const createProject = (uid, projectId, eventId = 'htn', overrides = {}) => {
  const fs = db(uid);
  const batch = writeBatch(fs);
  batch.set(doc(fs, `events/${eventId}/projects/${projectId}`), {
    title: 'Hackalytics', description: 'Profiles hackers', link: '', memberIds: [uid], members: { [uid]: uid },
    createdBy: uid, assignedJudges: [], createdAt: serverTimestamp(), updatedAt: serverTimestamp(), ...overrides,
  });
  batch.set(doc(fs, `events/${eventId}/teamMembers/${uid}`), { projectId });
  return batch.commit();
};

const joinTeam = (uid, projectId, eventId = 'htn') => {
  const fs = db(uid);
  const batch = writeBatch(fs);
  batch.update(doc(fs, `events/${eventId}/projects/${projectId}`), {
    memberIds: arrayUnion(uid), [`members.${uid}`]: uid,
  });
  batch.set(doc(fs, `events/${eventId}/teamMembers/${uid}`), { projectId });
  return batch.commit();
};

const leaveTeam = (uid, projectId) => {
  const fs = db(uid);
  const batch = writeBatch(fs);
  batch.update(doc(fs, `events/htn/projects/${projectId}`), {
    memberIds: arrayRemove(uid), [`members.${uid}`]: deleteField(),
  });
  batch.delete(doc(fs, `events/htn/teamMembers/${uid}`));
  return batch.commit();
};

const seedTeam = (admin, projectId, memberIds, assignedJudges = []) => {
  return Promise.all([
    setDoc(doc(admin, `events/htn/projects/${projectId}`), {
      title: projectId, description: 'd', link: '', memberIds,
      members: Object.fromEntries(memberIds.map((m) => [m, m])), createdBy: memberIds[0],
      assignedJudges, createdAt: new Date(), updatedAt: new Date(),
    }),
    ...memberIds.map((m) => setDoc(doc(admin, `events/htn/teamMembers/${m}`), { projectId })),
  ]);
};

describe('teams', () => {
  beforeEach(() => env.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await checkInAll(admin, 'htn', ['ada', 'cat', 'dan', 'eve', 'fay']);
    for (const uid of ['ada', 'cat', 'dan', 'eve', 'fay', 'gus', 'hal']) await approve(admin, 'htn', uid);
    await approve(admin, 'htn', 'ivy', 'PENDING');
    await approve(admin, 'htn', 'jo', 'WAITLISTED');
  }));

  test('a checked-in participant creates a team project', async () => {
    await assertSucceeds(createProject('ada', 'TEAMCODE01'));
  });

  test('approved participants form teams before checking in', async () => {
    await assertSucceeds(createProject('gus', 'TEAMCODE16'));
    await assertSucceeds(joinTeam('hal', 'TEAMCODE16'));
  });

  test('pending or waitlisted applicants cannot create or join teams', async () => {
    await createProject('gus', 'TEAMCODE21');
    await assertFails(createProject('ivy', 'TEAMCODE22'));
    await assertFails(joinTeam('ivy', 'TEAMCODE21'));
    await assertFails(joinTeam('jo', 'TEAMCODE21'));
  });

  test('cannot create a project without approval, or after the event', async () => {
    await assertFails(createProject('judge', 'TEAMCODE02'));
    await assertFails(createProject('ada', 'TEAMCODE03', 'old'));
  });

  test('cannot create a project without the membership record, or with other members', async () => {
    const fs = db('ada');
    await assertFails(setDoc(doc(fs, 'events/htn/projects/SOLO'), {
      title: 'x', description: 'x', link: '', memberIds: ['ada'], members: { ada: 'ada' }, createdBy: 'ada',
      assignedJudges: [], createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
    }));
    await assertFails(createProject('ada', 'TEAMCODE04', 'htn', { memberIds: ['ada', 'cat'], members: { ada: 'a', cat: 'c' } }));
    await assertFails(createProject('ada', 'TEAMCODE05', 'htn', { assignedJudges: ['judge'] }));
  });

  test('teammates join with the team code', async () => {
    await createProject('ada', 'TEAMCODE06');
    await assertSucceeds(joinTeam('cat', 'TEAMCODE06'));
    await assertSucceeds(joinTeam('dan', 'TEAMCODE06'));
    const project = await getDoc(doc(db('cat'), 'events/htn/projects/TEAMCODE06'));
    if (project.data().memberIds.length !== 3) throw new Error('expected 3 members');
  });

  test('one team per person per event', async () => {
    await createProject('ada', 'TEAMCODE07');
    await createProject('cat', 'TEAMCODE08');
    await assertFails(joinTeam('cat', 'TEAMCODE07'));
    await assertFails(createProject('ada', 'TEAMCODE09'));
  });

  test('teams are capped at four members', async () => {
    await createProject('ada', 'TEAMCODE10');
    for (const uid of ['cat', 'dan', 'eve']) await joinTeam(uid, 'TEAMCODE10');
    await assertFails(joinTeam('fay', 'TEAMCODE10'));
  });

  test('switching teams: must leave the current team before joining another', async () => {
    await createProject('ada', 'TEAMCODE17');
    await createProject('cat', 'TEAMCODE18');
    await joinTeam('dan', 'TEAMCODE17');
    await assertFails(joinTeam('dan', 'TEAMCODE18'));
    await assertSucceeds(leaveTeam('dan', 'TEAMCODE17'));
    await assertSucceeds(joinTeam('dan', 'TEAMCODE18'));
    const old = await getDoc(doc(db('ada'), 'events/htn/projects/TEAMCODE17'));
    if (old.data().memberIds.includes('dan')) throw new Error('dan should have left');
  });

  test('cannot leave someone else\'s membership behind to sneak into a second team', async () => {
    await createProject('ada', 'TEAMCODE19');
    await createProject('cat', 'TEAMCODE20');
    const fs = db('ada');
    const batch = writeBatch(fs);
    batch.update(doc(fs, 'events/htn/projects/TEAMCODE20'), { memberIds: arrayUnion('ada'), 'members.ada': 'ada' });
    await assertFails(batch.commit()); // ada's existing teamMembers record blocks a second membership
  });

  test('cannot join without being approved', async () => {
    await createProject('ada', 'TEAMCODE11');
    await assertFails(joinTeam('judge', 'TEAMCODE11'));
  });

  test('cannot add someone else to a team', async () => {
    await createProject('ada', 'TEAMCODE12');
    const fs = db('ada');
    const batch = writeBatch(fs);
    batch.update(doc(fs, 'events/htn/projects/TEAMCODE12'), { memberIds: arrayUnion('cat'), 'members.cat': 'cat' });
    await assertFails(batch.commit());
  });

  test('members leave; the last member deletes the project', async () => {
    await createProject('ada', 'TEAMCODE13');
    await joinTeam('cat', 'TEAMCODE13');
    await assertSucceeds(leaveTeam('cat', 'TEAMCODE13'));
    const fs = db('ada');
    const batch = writeBatch(fs);
    batch.delete(doc(fs, 'events/htn/projects/TEAMCODE13'));
    batch.delete(doc(fs, 'events/htn/teamMembers/ada'));
    await assertSucceeds(batch.commit());
  });

  test('members edit details; others cannot', async () => {
    await createProject('ada', 'TEAMCODE14');
    await joinTeam('cat', 'TEAMCODE14');
    await assertSucceeds(updateDoc(doc(db('cat'), 'events/htn/projects/TEAMCODE14'), { title: 'New', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db('dan'), 'events/htn/projects/TEAMCODE14'), { title: 'Hijack', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db('ada'), 'events/htn/projects/TEAMCODE14'), { createdBy: 'cat' }));
  });

  test('members query their projects; non-members cannot read them', async () => {
    await createProject('ada', 'TEAMCODE15');
    await joinTeam('cat', 'TEAMCODE15');
    await assertSucceeds(getDocs(query(collectionGroup(db('cat'), 'projects'), where('memberIds', 'array-contains', 'cat'))));
    await assertFails(getDoc(doc(db('dan'), 'events/htn/projects/TEAMCODE15')));
    await assertSucceeds(getDoc(doc(db('cat'), 'events/htn/teamMembers/cat')));
    await assertSucceeds(getDoc(doc(db('dan'), 'events/htn/teamMembers/dan'))); // missing doc: "not in a team"
    await assertFails(getDoc(doc(db('dan'), 'events/htn/teamMembers/cat')));
  });
});

describe('judging', () => {
  const score = (judgeId, overrides = {}) => ({
    judgeId, innovation: 8, technical: 7, design: 6, impact: 9, comment: 'Nice', updatedAt: serverTimestamp(),
    ...overrides,
  });

  beforeEach(() => env.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await setDoc(doc(admin, 'users/judge2'), {
      name: 'judge2', email: 'judge2@example.com', organization: '', role: 'JUDGE', createdAt: new Date(),
    });
    await seedTeam(admin, 'P1', ['ada', 'cat'], ['judge']);
    await seedTeam(admin, 'P2', ['dan'], ['judge2']);
    await setDoc(doc(admin, 'events/htn/projects/P2/scores/judge2'), { ...score('judge2'), updatedAt: new Date() });
  }));

  test('organizers assign judges; nobody else can', async () => {
    await assertSucceeds(updateDoc(doc(db('org'), 'events/htn/projects/P1'), { assignedJudges: ['judge', 'judge2'] }));
    await assertFails(updateDoc(doc(db('judge'), 'events/htn/projects/P2'), { assignedJudges: ['judge'] }));
    await assertFails(updateDoc(doc(db('ada'), 'events/htn/projects/P1'), { assignedJudges: [] }));
  });

  test('judges see only projects assigned to them', async () => {
    await assertSucceeds(getDoc(doc(db('judge'), 'events/htn/projects/P1')));
    await assertFails(getDoc(doc(db('judge'), 'events/htn/projects/P2')));
    await assertSucceeds(getDocs(query(collectionGroup(db('judge'), 'projects'), where('assignedJudges', 'array-contains', 'judge'))));
    await assertFails(getDocs(collectionGroup(db('judge'), 'projects')));
    await assertSucceeds(getDocs(collectionGroup(db('org'), 'projects')));
    await assertFails(getDocs(collectionGroup(db('vol'), 'projects')));
  });

  test('assigned judges score and revise; unassigned judges cannot', async () => {
    await assertSucceeds(setDoc(doc(db('judge'), 'events/htn/projects/P1/scores/judge'), score('judge')));
    await assertSucceeds(setDoc(doc(db('judge'), 'events/htn/projects/P1/scores/judge'), score('judge', { impact: 10 })));
    await assertFails(setDoc(doc(db('judge'), 'events/htn/projects/P2/scores/judge'), score('judge')));
  });

  test('scores must be whole numbers from 1 to 10, by the judge themselves', async () => {
    const ref = doc(db('judge'), 'events/htn/projects/P1/scores/judge');
    await assertFails(setDoc(ref, score('judge', { impact: 11 })));
    await assertFails(setDoc(ref, score('judge', { design: 0 })));
    await assertFails(setDoc(ref, score('judge', { design: 5.5 })));
    await assertFails(setDoc(doc(db('org'), 'events/htn/projects/P1/scores/org'), score('org')));
    await assertFails(setDoc(doc(db('ada'), 'events/htn/projects/P1/scores/ada'), score('ada')));
  });

  test('judges see only their own scores; organizers see all', async () => {
    await assertFails(getDoc(doc(db('judge'), 'events/htn/projects/P2/scores/judge2')));
    await assertSucceeds(getDocs(query(collectionGroup(db('judge'), 'scores'), where('judgeId', '==', 'judge'))));
    await assertFails(getDocs(collectionGroup(db('judge'), 'scores')));
    await assertSucceeds(getDocs(collectionGroup(db('org'), 'scores')));
  });
});

describe('results', () => {
  const results = (by) => ({ entries: [{ projectId: 'P2', title: 'P2', rank: 1, average: 30 }], publishedAt: serverTimestamp(), publishedBy: by });

  beforeEach(() => env.withSecurityRulesDisabled(async (ctx) => {
    const admin = ctx.firestore();
    await seedTeam(admin, 'P2', ['dan'], ['judge2']);
    await setDoc(doc(admin, 'events/htn/projects/P2/scores/judge2'), {
      judgeId: 'judge2', innovation: 8, technical: 7, design: 6, impact: 9, comment: '', updatedAt: new Date(),
    });
  }));

  test('only organizers publish and unpublish results', async () => {
    await assertFails(setDoc(doc(db('judge'), 'events/htn/results/leaderboard'), results('judge')));
    await assertSucceeds(setDoc(doc(db('org'), 'events/htn/results/leaderboard'), results('org')));
    await assertFails(deleteDoc(doc(db('vol'), 'events/htn/results/leaderboard')));
    await assertSucceeds(deleteDoc(doc(db('org'), 'events/htn/results/leaderboard')));
  });

  test('team members see their scores only after results are published', async () => {
    const scores = collection(db('dan'), 'events/htn/projects/P2/scores');
    await assertFails(getDocs(scores));
    await setDoc(doc(db('org'), 'events/htn/results/leaderboard'), results('org'));
    await assertSucceeds(getDocs(scores));
    await assertSucceeds(getDoc(doc(db('ada'), 'events/htn/results/leaderboard')));
    await assertFails(getDocs(collection(db('ada'), 'events/htn/projects/P2/scores')));
  });
});
