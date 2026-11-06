window.formatLabTime = function (value, options = { dateStyle: 'medium', timeStyle: 'short' }) {
  if (!value) return '—';
  return new Intl.DateTimeFormat(undefined, options).format(new Date(value));
};

window.makeStatusBadge = function (status, baseClass = 'status-badge') {
  const badge = document.createElement('span');
  const key = String(status || 'UNKNOWN').toLowerCase().replaceAll(' ', '_');
  badge.className = `${baseClass} ${key}`;
  badge.textContent = String(status || 'Unknown').replaceAll('_', ' ');
  return badge;
};
