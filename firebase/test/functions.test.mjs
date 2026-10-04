// Runs against the Functions + Firestore emulators (see `npm test`).
import { after, before, beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDocs, getDoc, serverTimestamp, setDoc, updateDoc } from 'firebase/firestore';

let env;

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
    await setDoc(doc(admin, 'users/org'), { name: 'Org', email: 'org@example.com', organization: '', role: 'ORGANIZER', createdAt: new Date() });
    await setDoc(doc(admin, 'users/ada'), { name: 'Ada', email: 'ada@example.com', organization: '', role: 'PARTICIPANT', createdAt: new Date() });
    await setDoc(doc(admin, 'events/htn'), {
      name: 'Hack the North', location: 'Waterloo', description: '', startEpochDay: 99999, endEpochDay: 99999,
      createdBy: 'org', createdAt: new Date(),
    });
  });
});

// withSecurityRulesDisabled doesn't pass the callback's result back, so capture it.
async function asAdmin(fn) {
  let result;
  await env.withSecurityRulesDisabled(async (ctx) => { result = await fn(ctx.firestore()); });
  return result;
}

async function waitFor(check, timeoutMs = 15000) {
  const start = Date.now();
  for (;;) {
    const result = await check();
    if (result) return result;
    if (Date.now() - start > timeoutMs) throw new Error('timed out');
    await new Promise((r) => setTimeout(r, 250));
  }
}

const mailFor = (status) => asAdmin(async (admin) =>
  (await getDocs(collection(admin, 'mail'))).docs.map((d) => d.data()).filter((m) => m.status === status));

describe('onRegistrationDecision', () => {
  test('an organizer approving an application queues one email and records it', async () => {
    const ada = env.authenticatedContext('ada', { email: 'ada@example.com' }).firestore();
    const org = env.authenticatedContext('org', { email: 'org@example.com' }).firestore();
    await setDoc(doc(ada, 'events/htn/registrations/ada'), { userId: 'ada', registeredAt: serverTimestamp(), status: 'PENDING' });
    await updateDoc(doc(org, 'events/htn/registrations/ada'), { status: 'APPROVED', reviewedBy: 'org', reviewedAt: serverTimestamp() });

    const [mail] = await waitFor(async () => { const m = await mailFor('APPROVED'); return m.length ? m : null; });
    assert.equal(mail.to, 'ada@example.com');
    assert.match(mail.message.subject, /Hack the North/);

    const reg = await waitFor(() => asAdmin(async (admin) => {
      const d = (await getDoc(doc(admin, 'events/htn/registrations/ada'))).data();
      return d.notifiedStatus ? d : null;
    }));
    assert.equal(reg.notifiedStatus, 'APPROVED');
    await new Promise((r) => setTimeout(r, 1500)); // the function's own update must not send a second email
    assert.equal((await mailFor('APPROVED')).length, 1);
  });

  test('a new application (pending) sends nothing; a later decline does', async () => {
    const ada = env.authenticatedContext('ada', { email: 'ada@example.com' }).firestore();
    const org = env.authenticatedContext('org', { email: 'org@example.com' }).firestore();
    await setDoc(doc(ada, 'events/htn/registrations/ada'), { userId: 'ada', registeredAt: serverTimestamp(), status: 'PENDING' });
    await new Promise((r) => setTimeout(r, 1500));
    assert.equal((await mailFor('PENDING')).length, 0);

    await updateDoc(doc(org, 'events/htn/registrations/ada'), { status: 'DECLINED', reviewedBy: 'org', reviewedAt: serverTimestamp() });
    const [mail] = await waitFor(async () => { const m = await mailFor('DECLINED'); return m.length ? m : null; });
    assert.match(mail.message.text, /aren't able to offer you a spot/);
  });

  test('clients cannot write to the mail queue', async () => {
    const org = env.authenticatedContext('org', { email: 'org@example.com' }).firestore();
    await assert.rejects(setDoc(doc(org, 'mail/spam'), { to: 'victim@example.com', message: { subject: 'hi', text: 'x' } }));
    await asAdmin((admin) => deleteDoc(doc(admin, 'mail/spam')));
  });
});
