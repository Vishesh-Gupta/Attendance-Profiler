// Runs against the Functions + Firestore emulators (see `npm test`).
import { after, before, beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, doc, getDocs, getDoc, serverTimestamp, setDoc, updateDoc } from 'firebase/firestore';

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

const CONFIRM_URL = 'http://127.0.0.1:5001/demo-attendance/us-central1/confirmAttendance';

const contexts = () => ({
  ada: env.authenticatedContext('ada', { email: 'ada@example.com' }).firestore(),
  org: env.authenticatedContext('org', { email: 'org@example.com' }).firestore(),
});

const registration = () => asAdmin(async (admin) => (await getDoc(doc(admin, 'events/htn/registrations/ada'))).data());

async function applyAndDecide(status) {
  const { ada, org } = contexts();
  await setDoc(doc(ada, 'events/htn/registrations/ada'), { userId: 'ada', registeredAt: serverTimestamp(), status: 'PENDING' });
  await updateDoc(doc(org, 'events/htn/registrations/ada'), { status, reviewedBy: 'org', reviewedAt: serverTimestamp() });
}

async function approvalLink(count = 1) {
  const mails = await waitFor(async () => { const m = await mailFor('APPROVED'); return m.length >= count ? m : null; });
  const texts = mails.map((m) => m.message.text);
  const links = texts.map((t) => t.match(/Confirm your spot: (\S+)/)?.[1]).filter(Boolean);
  assert.equal(links.length, mails.length, 'every approval email has a confirmation link');
  return links;
}

describe('application emails', () => {
  test('approving queues one email with a confirmation link and records it', async () => {
    await applyAndDecide('APPROVED');
    const [link] = await approvalLink();
    assert.ok(link.startsWith(`${CONFIRM_URL}?token=`));
    const reg = await waitFor(async () => { const r = await registration(); return r.notifiedStatus ? r : null; });
    assert.equal(reg.notifiedStatus, 'APPROVED');
    await new Promise((r) => setTimeout(r, 1500)); // the function's own update must not send a second email
    assert.equal((await mailFor('APPROVED')).length, 1);
  });

  test('a new application (pending) sends nothing; a decline sends the decision', async () => {
    const { ada, org } = contexts();
    await setDoc(doc(ada, 'events/htn/registrations/ada'), { userId: 'ada', registeredAt: serverTimestamp(), status: 'PENDING' });
    await new Promise((r) => setTimeout(r, 1500));
    assert.equal((await mailFor('PENDING')).length, 0);
    await updateDoc(doc(org, 'events/htn/registrations/ada'), { status: 'DECLINED', reviewedBy: 'org', reviewedAt: serverTimestamp() });
    const [mail] = await waitFor(async () => { const m = await mailFor('DECLINED'); return m.length ? m : null; });
    assert.match(mail.message.text, /aren't able to offer you a spot/);
  });

  test('clients cannot write to the mail queue or read confirmation tokens', async () => {
    const { org } = contexts();
    await assert.rejects(setDoc(doc(org, 'mail/spam'), { to: 'victim@example.com', message: { subject: 'hi', text: 'x' } }));
    await assert.rejects(getDocs(collection(org, 'confirmTokens')));
  });
});

describe('confirming a spot from the email', () => {
  test('opening the link only shows a page; pressing Confirm confirms and sends a receipt', async () => {
    await applyAndDecide('APPROVED');
    const [link] = await approvalLink();

    const page = await fetch(link);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /<form method="POST"/);
    assert.equal((await registration()).status, 'APPROVED', 'a GET (e.g. an email link scanner) must not confirm');

    const confirmed = await fetch(link, { method: 'POST' });
    assert.equal(confirmed.status, 200);
    assert.match(await confirmed.text(), /You&#39;re confirmed!/);
    const reg = await registration();
    assert.equal(reg.status, 'CONFIRMED');
    assert.equal(reg.confirmedVia, 'email');

    const [receipt] = await waitFor(async () => { const m = await mailFor('CONFIRMED'); return m.length ? m : null; });
    assert.match(receipt.message.text, /form your team/);

    const again = await fetch(link, { method: 'POST' });
    assert.match(await again.text(), /already confirmed/);
  });

  test('a link stops working once the approval is undone', async () => {
    await applyAndDecide('APPROVED');
    const [link] = await approvalLink();
    const { org } = contexts();
    await updateDoc(doc(org, 'events/htn/registrations/ada'), { status: 'PENDING', reviewedBy: 'org', reviewedAt: serverTimestamp() });
    const res = await fetch(link, { method: 'POST' });
    assert.equal(res.status, 410);
    assert.equal((await registration()).status, 'PENDING');
  });

  test('made-up tokens are rejected', async () => {
    for (const token of ['nope', 'x'.repeat(43), '../../users/org']) {
      const res = await fetch(`${CONFIRM_URL}?token=${encodeURIComponent(token)}`, { method: 'POST' });
      assert.equal(res.status, 404);
    }
  });

  test('asking for a resend emails a fresh link; both links work until confirmed', async () => {
    await applyAndDecide('APPROVED');
    await approvalLink();
    const { ada } = contexts();
    await updateDoc(doc(ada, 'events/htn/registrations/ada'), { resendRequestedAt: serverTimestamp() });
    const links = await approvalLink(2);
    assert.notEqual(links[0], links[1]);
    const res = await fetch(links[0], { method: 'POST' });
    assert.match(await res.text(), /You&#39;re confirmed!/);
  });
});
