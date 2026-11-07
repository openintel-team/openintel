const lower = (value) => String(value ?? '').toLowerCase();
const isAdmin = (user) => !!user && !user.disabled && lower(user.role) === 'admin';
const isTrial = (user) => !!user && lower(user.role) === 'trial';
const isCut = (name, lookup) => lookup(name)?.relayCut === true;

function canReceiveIntel(recipient, reporter, subject, lookup) {
  if (!recipient || recipient.disabled || isTrial(recipient)) return false;
  if (isAdmin(recipient)) return true;
  if (reporter && lower(reporter) !== 'discord') {
    const source = lookup(reporter);
    if (!source || source.disabled || source.quarantined) return false;
  }
  return !isCut(reporter, lookup) && !isCut(subject, lookup);
}

// A player's own report is exact; another client's view of them lags by that
// client's interpolation and network path. Keep the self-report unless it is
// more than SELF_REPORT_PREFERENCE_MS older than the newest other view.
const SELF_REPORT_PREFERENCE_MS = 1000;
const isSelfReport = (report) => lower(report.reporter) === lower(report.name);
function better(current, report) {
  if (isSelfReport(current) !== isSelfReport(report)) {
    const self = isSelfReport(current) ? current : report;
    const other = self === current ? report : current;
    return self.t >= other.t - SELF_REPORT_PREFERENCE_MS ? self : other;
  }
  return current.t <= report.t ? report : current;
}

function visiblePositions(reports, recipient, lookup) {
  const latest = new Map();
  for (const report of reports) {
    if (!canReceiveIntel(recipient, report.reporter, report.name, lookup)) continue;
    const key = lower(report.name);
    latest.set(key, latest.has(key) ? better(latest.get(key), report) : report);
  }
  return [...latest.values()];
}

function visibilityKey(recipient, users) {
  if (isTrial(recipient)) return 'trial';
  if (isAdmin(recipient)) return 'admin';
  return 'public:' + users.filter(user => user.relayCut === true).map(user => lower(user.name)).sort().join(',');
}

module.exports = { isAdmin, isTrial, isCut, canReceiveIntel, visiblePositions, visibilityKey };
