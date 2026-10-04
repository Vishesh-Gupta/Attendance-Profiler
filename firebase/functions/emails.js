// Decision emails for event applications. Pure functions so they can be unit tested.

const ONE_DAY_MS = 24 * 60 * 60 * 1000;

const escapeHtml = (value) =>
  String(value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

const formatDay = (epochDay) =>
  new Date(epochDay * ONE_DAY_MS).toLocaleDateString('en-US', {
    timeZone: 'UTC', weekday: 'short', month: 'long', day: 'numeric', year: 'numeric',
  });

const formatDates = (event) => {
  const end = event.endEpochDay ?? event.startEpochDay;
  return end === event.startEpochDay
    ? formatDay(event.startEpochDay)
    : `${formatDay(event.startEpochDay)} – ${formatDay(end)}`;
};

const TEMPLATES = {
  APPROVED: {
    subject: (e) => `You're in! Your application to ${e.name} was approved`,
    body: (e) => [
      `Great news: you've been approved to attend ${e.name}.`,
      `What's next:\n• Open Attendance Profiler and form your team (up to 4 people) from the event page.\n` +
        '• At the event, show the QR pass on your Profile tab at the check-in desk.',
    ],
  },
  WAITLISTED: {
    subject: (e) => `You're on the waitlist for ${e.name}`,
    body: (e) => [
      `Thanks for applying to ${e.name}. We can't confirm your spot yet, so you're on the waitlist.`,
      "We'll email you again if a spot opens up. You can check your status in Attendance Profiler at any time.",
    ],
  },
  DECLINED: {
    subject: (e) => `Your application to ${e.name}`,
    body: (e) => [
      `Thanks for applying to ${e.name}. Unfortunately we aren't able to offer you a spot this time.`,
      'We hope to see you at a future event.',
    ],
  },
};

/** Statuses that trigger an email. PENDING (e.g. an organizer undoing a decision) doesn't. */
const NOTIFY_STATUSES = Object.keys(TEMPLATES);

/**
 * Builds the message for the Trigger Email extension, or null for statuses that don't send email.
 * `event` is the event document's data; `user` is the applicant's users/{uid} document data.
 */
function decisionEmail(status, event, user) {
  const template = TEMPLATES[status];
  if (!template || !user?.email) return null;
  const where = [formatDates(event), event.location].filter(Boolean).join(' · ');
  const paragraphs = [`Hi ${user.name || 'there'},`, ...template.body(event), `${event.name}\n${where}`];
  return {
    to: user.email,
    message: {
      subject: template.subject(event),
      text: paragraphs.join('\n\n'),
      html: paragraphs.map((p) => `<p>${escapeHtml(p).replace(/\n/g, '<br>')}</p>`).join('\n'),
    },
  };
}

module.exports = { decisionEmail, NOTIFY_STATUSES, escapeHtml, formatDates };
