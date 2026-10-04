import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { decisionEmail, formatDates } = require('../functions/emails.js');

const htn = { name: 'Hack the North', location: 'Waterloo', startEpochDay: 20707, endEpochDay: 20709 }; // Sep 11–13, 2026
const ada = { name: 'Ada', email: 'ada@example.com' };

describe('decision emails', () => {
  test('approval tells people what to do next', () => {
    const mail = decisionEmail('APPROVED', htn, ada);
    assert.equal(mail.to, 'ada@example.com');
    assert.match(mail.message.subject, /approved/);
    assert.match(mail.message.text, /^Hi Ada,/);
    assert.match(mail.message.text, /form your team/);
    assert.match(mail.message.text, /QR pass/);
    assert.match(mail.message.text, /Waterloo/);
  });

  test('waitlist and decline have their own wording', () => {
    assert.match(decisionEmail('WAITLISTED', htn, ada).message.text, /waitlist/);
    assert.match(decisionEmail('DECLINED', htn, ada).message.text, /aren't able to offer you a spot/);
  });

  test('no email for pending, unknown statuses or users without an email', () => {
    assert.equal(decisionEmail('PENDING', htn, ada), null);
    assert.equal(decisionEmail('WHATEVER', htn, ada), null);
    assert.equal(decisionEmail('APPROVED', htn, { name: 'x' }), null);
  });

  test('names are escaped in HTML', () => {
    const mail = decisionEmail('APPROVED', { ...htn, name: '<b>Hack</b>' }, { name: 'A & B', email: 'a@b.c' });
    assert.ok(!mail.message.html.includes('<b>Hack</b>'));
    assert.ok(mail.message.html.includes('&lt;b&gt;Hack&lt;/b&gt;'));
    assert.ok(mail.message.html.includes('A &amp; B'));
  });

  test('dates', () => {
    assert.equal(formatDates({ startEpochDay: 20707 }), 'Fri, September 11, 2026');
    assert.equal(formatDates(htn), 'Fri, September 11, 2026 – Sun, September 13, 2026');
  });
});
