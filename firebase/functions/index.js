const crypto = require('node:crypto');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const { onRequest } = require('firebase-functions/v2/https');
const logger = require('firebase-functions/logger');
const { decisionEmail, confirmPage } = require('./emails');

initializeApp();
const db = getFirestore();

const REGION = 'us-central1';

/** Where the "Confirm my spot" button points. CONFIRM_BASE_URL overrides it (e.g. a custom domain). */
function confirmBaseUrl() {
  if (process.env.CONFIRM_BASE_URL) return process.env.CONFIRM_BASE_URL;
  const project = process.env.GCLOUD_PROJECT;
  if (process.env.FUNCTIONS_EMULATOR === 'true') {
    return `http://127.0.0.1:5001/${project}/${REGION}/confirmAttendance`;
  }
  return `https://${REGION}-${project}.cloudfunctions.net/confirmAttendance`;
}

const millis = (timestamp) => timestamp?.toMillis?.() ?? 0;

/**
 * Emails applicants about their application:
 * - APPROVED: asks them to confirm their spot with a single-use link (see confirmAttendance).
 *   Also re-sent when the applicant asks (resendRequestedAt changes) while still unconfirmed.
 * - CONFIRMED: a receipt with next steps.
 * - WAITLISTED / DECLINED: the decision.
 * Messages are queued in `mail`, which the Trigger Email extension sends. Clients can't write there.
 */
exports.onRegistrationDecision = onDocumentWritten(
  { document: 'events/{eventId}/registrations/{uid}', region: REGION },
  async (change) => {
    const before = change.data.before.exists ? change.data.before.data() : null;
    const after = change.data.after.exists ? change.data.after.data() : null;
    if (!after) return;
    const statusChanged = before?.status !== after.status;
    const resendRequested = !statusChanged && after.status === 'APPROVED' &&
      millis(after.resendRequestedAt) > millis(before?.resendRequestedAt);
    if (!statusChanged && !resendRequested) return;

    const { eventId, uid } = change.params;
    const [userSnap, eventSnap] = await Promise.all([db.doc(`users/${uid}`).get(), db.doc(`events/${eventId}`).get()]);
    if (!userSnap.exists || !eventSnap.exists) return;

    // Functions can run more than once per change; a deterministic id makes each email send once.
    const sendKey = resendRequested
      ? `resend_${millis(after.resendRequestedAt)}`
      : `${after.status}_${millis(after.reviewedAt) || millis(after.confirmedAt)}`;
    const mailRef = db.collection('mail').doc(`${eventId}_${uid}_${sendKey}`);
    if ((await mailRef.get()).exists) return;

    let confirmUrl;
    if (after.status === 'APPROVED') {
      // Single-use link tied to this approval. Stored server-side only: clients can't read confirmTokens.
      const token = crypto.randomBytes(32).toString('base64url');
      await db.doc(`confirmTokens/${token}`).set({
        eventId, uid, approvedAt: after.reviewedAt ?? null, createdAt: FieldValue.serverTimestamp(),
      });
      confirmUrl = `${confirmBaseUrl()}?token=${token}`;
    }

    const mail = decisionEmail(after.status, eventSnap.data(), userSnap.data(), confirmUrl);
    if (!mail) return;
    try {
      await mailRef.create({ ...mail, eventId, userId: uid, status: after.status, createdAt: FieldValue.serverTimestamp() });
    } catch (e) {
      if (e.code === 6 /* ALREADY_EXISTS */) return;
      throw e;
    }
    try {
      await change.data.after.ref.update({ notifiedStatus: after.status, notifiedAt: FieldValue.serverTimestamp() });
    } catch (e) {
      if (e.code !== 5 /* NOT_FOUND: the application was withdrawn meanwhile */) throw e;
    }
    logger.info('Queued application email', { eventId, uid, status: after.status, resend: resendRequested });
  },
);

/**
 * The "Confirm my spot" link. GET shows a page with a Confirm button and changes nothing, because
 * email security scanners open links automatically. POST confirms. The link only works while the
 * approval it was sent for still stands.
 */
exports.confirmAttendance = onRequest({ region: REGION }, async (req, res) => {
  res.set('Cache-Control', 'no-store');
  const send = (code, page) => res.status(code).type('html').send(confirmPage(page));
  if (req.method !== 'GET' && req.method !== 'POST') return send(405, { title: 'Not allowed', message: 'Use the link from your email.' });

  const token = String(req.query.token ?? '');
  const tokenSnap = /^[A-Za-z0-9_-]{20,100}$/.test(token) ? await db.doc(`confirmTokens/${token}`).get() : null;
  if (!tokenSnap?.exists) {
    return send(404, { title: 'Link not recognized', message: 'This confirmation link is invalid. Use the most recent email we sent you.' });
  }
  const { eventId, uid, approvedAt } = tokenSnap.data();
  const registrationRef = db.doc(`events/${eventId}/registrations/${uid}`);
  const eventSnap = await db.doc(`events/${eventId}`).get();
  const eventName = eventSnap.exists ? eventSnap.data().name : undefined;

  const outcome = await db.runTransaction(async (tx) => {
    const reg = await tx.get(registrationRef);
    if (!reg.exists) return 'withdrawn';
    const data = reg.data();
    if (data.status === 'CONFIRMED') return 'already';
    if (data.status !== 'APPROVED' || millis(data.reviewedAt) !== millis(approvedAt)) return 'stale';
    if (req.method === 'GET') return 'ask';
    tx.update(registrationRef, { status: 'CONFIRMED', confirmedAt: FieldValue.serverTimestamp(), confirmedVia: 'email' });
    return 'confirmed';
  });

  switch (outcome) {
    case 'ask':
      return send(200, {
        eventName,
        title: 'Confirm your spot',
        message: "Tap the button to confirm you're coming. You can then form your team in the app.",
        formAction: `?token=${token}`,
      });
    case 'confirmed':
      logger.info('Attendance confirmed', { eventId, uid });
      return send(200, { eventName, title: "You're confirmed!", message: 'See you there. Open Attendance Profiler to form your team.' });
    case 'already':
      return send(200, { eventName, title: "You're already confirmed", message: 'Nothing else to do. Open Attendance Profiler to form your team.' });
    case 'withdrawn':
      return send(410, { eventName, title: 'Application withdrawn', message: 'This application was withdrawn, so there is nothing to confirm.' });
    default:
      return send(410, {
        eventName,
        title: 'This link has expired',
        message: 'Your application has changed since this email was sent. Check the app for your current status.',
      });
  }
});
