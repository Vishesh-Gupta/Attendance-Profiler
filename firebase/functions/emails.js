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

/** Keep in sync with the 14-day team lock in firestore.rules and Event.TEAM_LOCK_DAYS in the app. */
const TEAM_LOCK_DAYS = 14;

const deadlineNote = (e) =>
  `Important: teams must be formed by ${formatDay(e.startEpochDay - TEAM_LOCK_DAYS)}. ` +
  "From then on, teams are locked and you can't withdraw from the event.";

const TEMPLATES = {
  APPROVED: {
    subject: (e) => `You're approved for ${e.name}: please confirm your spot`,
    body: (e) => [
      `Great news: you've been approved to attend ${e.name}.`,
      'Please confirm that you\'re coming using the button below. Your spot isn\'t final until you do, ' +
        'and you can\'t form a team or check in before confirming.',
      deadlineNote(e),
    ],
    needsConfirmation: true,
  },
  CONFIRMED: {
    subject: (e) => `You're confirmed for ${e.name}`,
    body: (e) => [
      `Thanks for confirming! Your spot at ${e.name} is locked in.`,
      `What's next:\n• Open Attendance Profiler and form your team (up to 4 people) from the event page.\n` +
        '• At the event, show the QR pass on your Profile tab at the check-in desk.',
      deadlineNote(e),
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

const paragraphHtml = (p) => `<p>${escapeHtml(p).replace(/\n/g, '<br>')}</p>`;

const buttonHtml = (url, label) =>
  `<p><a href="${escapeHtml(url)}" style="display:inline-block;padding:12px 24px;background:#3F51B5;color:#ffffff;` +
  `text-decoration:none;border-radius:6px;font-weight:bold">${escapeHtml(label)}</a></p>`;

/**
 * Builds the message for the Trigger Email extension, or null for statuses that don't send email.
 * `event` is the event document's data; `user` is the applicant's users/{uid} document data.
 * Approval emails need `confirmUrl`, the link behind their "Confirm my spot" button.
 */
function decisionEmail(status, event, user, confirmUrl) {
  const template = TEMPLATES[status];
  if (!template || !user?.email) return null;
  if (template.needsConfirmation && !confirmUrl) throw new Error('Approval emails need a confirmation link');
  const where = [formatDates(event), event.location].filter(Boolean).join(' · ');
  const greeting = `Hi ${user.name || 'there'},`;
  const footer = `${event.name}\n${where}`;
  const body = template.body(event);

  const text = [greeting, ...body];
  const html = [greeting, ...body].map(paragraphHtml);
  if (template.needsConfirmation) {
    text.push(`Confirm your spot: ${confirmUrl}`);
    html.push(buttonHtml(confirmUrl, 'Confirm my spot'));
    html.push(paragraphHtml(`If the button doesn't work, open this link: ${confirmUrl}`));
  }
  text.push(footer);
  html.push(paragraphHtml(footer));
  return { to: user.email, message: { subject: template.subject(event), text: text.join('\n\n'), html: html.join('\n') } };
}

/** The small page behind the email button. Everything interpolated is escaped. */
function confirmPage({ title, message, eventName, formAction }) {
  const form = formAction
    ? `<form method="POST" action="${escapeHtml(formAction)}"><button type="submit">Confirm my spot</button></form>`
    : '';
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
  body { font-family: system-ui, -apple-system, sans-serif; background: #f5f5f7; color: #1c1b1f; margin: 0; padding: 16px; }
  main { max-width: 420px; margin: 48px auto; background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 1px 4px rgba(0,0,0,.08); }
  h1 { font-size: 1.4rem; margin-top: 0; }
  button { width: 100%; padding: 14px; font-size: 1rem; font-weight: 600; color: #fff; background: #3F51B5; border: 0; border-radius: 8px; cursor: pointer; }
  .event { color: #5f5c66; font-size: .9rem; }
</style></head>
<body><main>
  ${eventName ? `<p class="event">${escapeHtml(eventName)}</p>` : ''}
  <h1>${escapeHtml(title)}</h1>
  <p>${escapeHtml(message)}</p>
  ${form}
</main></body></html>`;
}

module.exports = { decisionEmail, confirmPage, NOTIFY_STATUSES, TEAM_LOCK_DAYS, escapeHtml, formatDates };
