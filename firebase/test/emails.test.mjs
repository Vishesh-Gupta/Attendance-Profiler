import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { decisionEmail, confirmPage, formatDates } = require('../functions/emails.js');

const htn = { name: 'Hack the North', location: 'Waterloo', startEpochDay: 20707, endEpochDay: 20709 }; // Sep 11–13, 2026
const ada = { name: 'Ada', email: 'ada@example.com' };

describe('decision emails', () => {
  const link = 'https://example.com/confirmAttendance?token=abc_123';

  test('approval asks people to confirm with a button', () => {
    const mail = decisionEmail('APPROVED', htn, ada, link);
    assert.equal(mail.to, 'ada@example.com');
    assert.match(mail.message.subject, /confirm your spot/);
    assert.match(mail.message.text, /^Hi Ada,/);
    assert.ok(mail.message.text.includes(`Confirm your spot: ${link}`));
    assert.match(mail.message.html, /<a href="https:\/\/example\.com\/confirmAttendance\?token=abc_123"[^>]*>Confirm my spot<\/a>/);
    assert.match(mail.message.text, /Waterloo/);
  });

  test('approval and confirmation emails state the team deadline (14 days before the start)', () => {
    for (const mail of [decisionEmail('APPROVED', htn, ada, link), decisionEmail('CONFIRMED', htn, ada)]) {
      assert.match(mail.message.text, /teams must be formed by Fri, August 28, 2026/);
      assert.match(mail.message.text, /can't withdraw/);
    }
    assert.doesNotMatch(decisionEmail('DECLINED', htn, ada).message.text, /teams must be formed/);
  });

  test('approval emails cannot go out without a confirmation link', () => {
    assert.throws(() => decisionEmail('APPROVED', htn, ada));
  });

  test('confirmation receipt tells people what to do next', () => {
    const mail = decisionEmail('CONFIRMED', htn, ada);
    assert.match(mail.message.subject, /confirmed/);
    assert.match(mail.message.text, /form your team/);
    assert.match(mail.message.text, /QR pass/);
  });

  test('confirm page escapes content and only shows a form when asked', () => {
    const page = confirmPage({ title: '<x>', message: 'm', eventName: 'E&E', formAction: '?token=a"b' });
    assert.ok(page.includes('&lt;x&gt;') && page.includes('E&amp;E') && page.includes('?token=a&quot;b'));
    assert.match(page, /<form method="POST"/);
    assert.ok(!confirmPage({ title: 't', message: 'm' }).includes('<form'));
  });

  test('waitlist and decline have their own wording', () => {
    assert.match(decisionEmail('WAITLISTED', htn, ada).message.text, /waitlist/);
    assert.match(decisionEmail('DECLINED', htn, ada).message.text, /aren't able to offer you a spot/);
  });

  test('no email for pending, unknown statuses or users without an email', () => {
    assert.equal(decisionEmail('PENDING', htn, ada), null);
    assert.equal(decisionEmail('WHATEVER', htn, ada), null);
    assert.equal(decisionEmail('APPROVED', htn, { name: 'x' }, link), null);
  });

  test('names are escaped in HTML', () => {
    const mail = decisionEmail('DECLINED', { ...htn, name: '<b>Hack</b>' }, { name: 'A & B', email: 'a@b.c' });
    assert.ok(!mail.message.html.includes('<b>Hack</b>'));
    assert.ok(mail.message.html.includes('&lt;b&gt;Hack&lt;/b&gt;'));
    assert.ok(mail.message.html.includes('A &amp; B'));
  });

  test('dates', () => {
    assert.equal(formatDates({ startEpochDay: 20707 }), 'Fri, September 11, 2026');
    assert.equal(formatDates(htn), 'Fri, September 11, 2026 – Sun, September 13, 2026');
  });
});
