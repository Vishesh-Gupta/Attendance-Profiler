import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collectionGroup, deleteDoc, doc, getDoc, getDocs, query, serverTimestamp, setDoc, updateDoc, where,
} from 'firebase/firestore';

let env;

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
      name: 'HTN', location: 'Waterloo', startEpochDay: 100, createdBy: 'org', createdAt: new Date(),
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

  test('participants can only read their own profile; staff can read everyone', async () => {
    await assertSucceeds(getDoc(doc(db('ada'), 'users/ada')));
    await assertFails(getDoc(doc(db('ada'), 'users/bob')));
    await assertSucceeds(getDoc(doc(db('judge'), 'users/ada')));
  });
});

describe('events', () => {
  const event = (by) => ({
    name: 'HTN 2026', location: 'Waterloo', startEpochDay: 200, createdBy: by, createdAt: serverTimestamp(),
  });

  test('only organizers create and delete events', async () => {
    await assertSucceeds(setDoc(doc(db('org'), 'events/new'), event('org')));
    await assertFails(setDoc(doc(db('vol'), 'events/new2'), event('vol')));
    await assertFails(deleteDoc(doc(db('vol'), 'events/htn')));
    await assertSucceeds(deleteDoc(doc(db('org'), 'events/htn')));
  });

  test('any signed-in user can read events', async () => {
    await assertSucceeds(getDoc(doc(db('ada'), 'events/htn')));
  });
});

describe('check-ins', () => {
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

  test('staff can read all check-ins', async () => {
    await assertSucceeds(getDocs(collectionGroup(db('judge'), 'checkIns')));
  });
});

describe('registrations', () => {
  const registration = (uid) => ({ userId: uid, registeredAt: serverTimestamp() });

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
