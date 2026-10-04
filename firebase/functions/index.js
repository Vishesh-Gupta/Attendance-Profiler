const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const logger = require('firebase-functions/logger');
const { decisionEmail } = require('./emails');

initializeApp();
const db = getFirestore();

/**
 * Emails applicants when an organizer approves, waitlists or declines their registration.
 * The message is queued in the `mail` collection, which the Trigger Email extension
 * (firebase/firestore-send-email) delivers over SMTP. Clients can't write to `mail`.
 */
exports.onRegistrationDecision = onDocumentWritten('events/{eventId}/registrations/{uid}', async (change) => {
  const before = change.data.before.exists ? change.data.before.data() : null;
  const after = change.data.after.exists ? change.data.after.data() : null;
  if (!after || before?.status === after.status) return;

  const { eventId, uid } = change.params;
  const [userSnap, eventSnap] = await Promise.all([
    db.doc(`users/${uid}`).get(),
    db.doc(`events/${eventId}`).get(),
  ]);
  if (!userSnap.exists || !eventSnap.exists) return;

  const mail = decisionEmail(after.status, eventSnap.data(), userSnap.data());
  if (!mail) return;

  // Functions can run more than once per change; a deterministic id makes the email send once.
  const decidedAt = after.reviewedAt?.toMillis?.() ?? 0;
  const mailRef = db.collection('mail').doc(`${eventId}_${uid}_${after.status}_${decidedAt}`);
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
  logger.info('Queued decision email', { eventId, uid, status: after.status });
});
