let instruments = [];

function renderInstruments() {
  const search = document.querySelector('#instrument-search').value.trim().toLowerCase();
  const category = document.querySelector('#category-filter').value;
  const status = document.querySelector('#status-filter').value;
  const filtered = instruments.filter(item => {
    const matchesSearch = `${item.name} ${item.category} ${item.location} ${item.description}`.toLowerCase().includes(search);
    return matchesSearch && (category === 'all' || item.category === category) && (status === 'all' || item.status === status);
  });
  const grid = document.querySelector('#instrument-grid');
  grid.replaceChildren();
  document.querySelector('#catalog-message').textContent = filtered.length ? `${filtered.length} instrument${filtered.length === 1 ? '' : 's'} shown` : 'No instruments match those filters.';
  for (const item of filtered) {
    const card = document.createElement('article');
    card.className = 'instrument-card';
    const top = document.createElement('div');
    top.className = 'instrument-card-top';
    const icon = document.createElement('span');
    icon.className = 'instrument-icon';
    icon.textContent = '⚗';
    const categoryLabel = document.createElement('span');
    categoryLabel.className = 'instrument-category';
    categoryLabel.textContent = item.category;
    const badge = document.createElement('span');
    badge.className = `instrument-status ${item.status.toLowerCase().replaceAll(' ', '-')}`;
    badge.textContent = item.status;
    top.append(icon, categoryLabel, badge);
    const name = document.createElement('h2');
    name.textContent = item.name;
    const description = document.createElement('p');
    description.className = 'instrument-description';
    description.textContent = item.description;
    const location = document.createElement('div');
    location.className = 'instrument-location';
    location.innerHTML = '<span aria-hidden="true">⌖</span>';
    const locationText = document.createElement('span');
    locationText.textContent = item.location;
    location.append(locationText);
    card.append(top, name, description, location);
    if (item.status.toLowerCase() === 'available') {
      const book = document.createElement('a');
      book.className = 'book-equipment-link';
      book.href = `/bookings.html?equipmentId=${encodeURIComponent(item.id)}`;
      book.textContent = 'Book this equipment';
      card.append(book);
    }
    grid.append(card);
  }
}

async function loadInstruments() {
  const cacheKey = 'labresource.instrument-catalog.v1';
  let hasCachedCatalog = false;
  try {
    const cached = JSON.parse(sessionStorage.getItem(cacheKey) || 'null');
    if (Array.isArray(cached) && cached.length) {
      instruments = cached;
      hasCachedCatalog = true;
      renderCatalog();
      document.querySelector('#catalog-message').textContent = 'Showing the last loaded catalog while checking for updates…';
    }
  } catch { /* Ignore an unavailable or invalid browser cache. */ }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch('/api/instruments', { signal: controller.signal });
    if (response.status === 401) { window.location.replace('/'); return; }
    if (!response.ok) throw new Error(`Catalog request failed (${response.status}).`);
    instruments = await response.json();
    try { sessionStorage.setItem(cacheKey, JSON.stringify(instruments)); } catch { /* Cache is optional. */ }
    renderCatalog();
  } catch (error) {
    if (!hasCachedCatalog) throw new Error(error.name === 'AbortError'
      ? 'The catalog request timed out. Check that PostgreSQL is running, then reload.'
      : 'Could not load the instrument catalog. Check that PostgreSQL is running, then reload.');
    document.querySelector('#catalog-message').textContent = 'Showing the last loaded catalog. The latest catalog could not be reached.';
  } finally {
    clearTimeout(timeout);
  }
}

function renderCatalog() {
  document.querySelector('#instrument-count').textContent = instruments.length;
  const select = document.querySelector('#category-filter');
  select.replaceChildren(new Option('All categories', 'all'));
  for (const category of [...new Set(instruments.map(item => item.category))].sort()) {
    select.append(new Option(category, category));
  }
  renderInstruments();
}

document.querySelector('#instrument-search').addEventListener('input', renderInstruments);
document.querySelector('#category-filter').addEventListener('change', renderInstruments);
document.querySelector('#status-filter').addEventListener('change', renderInstruments);

async function initializeDashboard() {
  try {
    const response = await fetch('/api/auth/me');
    if (response.status === 401) { window.location.replace('/'); return; }
    if (!response.ok) throw new Error('Could not verify your sign-in. Reload the page to try again.');
    const data = await response.json();
    document.querySelector('#user-name').textContent = data.user.name.split(' ')[0];
    document.querySelector('#nav-user').textContent = data.user.name;
    document.querySelector('#user-email').textContent = data.user.email;
    await loadInstruments();
  } catch (error) {
    document.querySelector('#catalog-message').textContent = error.message || 'Could not load dashboard data.';
  }
}

initializeDashboard();

document.querySelector('#logout').addEventListener('click', async () => {
  await fetch('/api/auth/logout', { method: 'POST' });
  window.location.replace('/');
});
