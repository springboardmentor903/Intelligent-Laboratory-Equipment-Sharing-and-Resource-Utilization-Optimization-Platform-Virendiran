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
    grid.append(card);
  }
}

async function loadInstruments() {
  const response = await fetch('/api/instruments');
  if (response.status === 401) { window.location.replace('/'); return; }
  if (!response.ok) throw new Error('Could not load the instrument catalog. Check the application terminal for database details.');
  instruments = await response.json();
  document.querySelector('#instrument-count').textContent = instruments.length;
  const categories = [...new Set(instruments.map(item => item.category))].sort();
  const select = document.querySelector('#category-filter');
  for (const category of categories) {
    const option = document.createElement('option');
    option.value = category;
    option.textContent = category;
    select.append(option);
  }
  renderInstruments();
}

document.querySelector('#instrument-search').addEventListener('input', renderInstruments);
document.querySelector('#category-filter').addEventListener('change', renderInstruments);
document.querySelector('#status-filter').addEventListener('change', renderInstruments);

fetch('/api/auth/me')
  .then(async response => {
    if (!response.ok) { window.location.replace('/'); return null; }
    return response.json();
  })
  .then(data => {
    if (!data) return;
    document.querySelector('#user-name').textContent = data.user.name.split(' ')[0];
    document.querySelector('#nav-user').textContent = data.user.name;
    document.querySelector('#user-email').textContent = data.user.email;
  })
  .then(data => {
    if (data) return loadInstruments();
    return null;
  })
  .catch(error => {
    document.querySelector('#catalog-message').textContent = error.message || 'Could not load dashboard data.';
  });

document.querySelector('#logout').addEventListener('click', async () => {
  await fetch('/api/auth/logout', { method: 'POST' });
  window.location.replace('/');
});
