const emailInput = document.querySelector('#admin-email');
const passwordInput = document.querySelector('#admin-password');
const loginPanel = document.querySelector('#admin-login-panel');
const adminContent = document.querySelector('#admin-content');
const loginButton = document.querySelector('#admin-login');
const logoutButton = document.querySelector('#admin-logout');
const loadButton = document.querySelector('#load-requests');
const loginMessage = document.querySelector('#admin-message');
const dashboardMessage = document.querySelector('#dashboard-message');
const requestList = document.querySelector('#request-list');
const bookingRequestList = document.querySelector('#booking-request-list');
const activeBookingList = document.querySelector('#active-booking-list');
const equipmentGrid = document.querySelector('#admin-instrument-grid');

function setMessage(text, error = false) {
  const target = loginPanel.hidden ? dashboardMessage : loginMessage;
  target.textContent = text;
  target.classList.toggle('error', error);
}

function renderRequests(requests) {
  requestList.replaceChildren();
  if (!requests.length) {
    const empty = document.createElement('p');
    empty.className = 'catalog-message';
    empty.textContent = 'No pending account requests.';
    requestList.append(empty);
    return;
  }
  for (const request of requests) {
    const row = document.createElement('article');
    row.className = 'request-card';
    const details = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = request.name;
    const email = document.createElement('span');
    email.textContent = request.email;
    const date = document.createElement('small');
    date.textContent = `Requested ${request.createdAt}`;
    details.append(name, email, date);
    const approve = document.createElement('button');
    approve.type = 'button';
    approve.className = 'approve-button';
    approve.textContent = 'Approve access';
    approve.addEventListener('click', async () => {
      approve.disabled = true;
      try {
        const response = await fetch(`/api/admin/users/${request.id}/approve`, { method: 'POST' });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || 'Could not approve account.');
        await loadDashboard(`Approved ${request.name}.`);
      } catch (error) {
        approve.disabled = false;
        setMessage(error.message, true);
      }
    });
    row.append(details, approve);
    requestList.append(row);
  }
}

function emptyRow(text) {
  const empty = document.createElement('p');
  empty.className = 'catalog-message';
  empty.textContent = text;
  return empty;
}

function localTime(value) { return window.formatLabTime(value); }

function renderBookingApprovals(bookings) {
  bookingRequestList.replaceChildren();
  if (!bookings.length) { bookingRequestList.append(emptyRow('No pending booking approvals.')); return; }
  for (const booking of bookings) {
    const row = document.createElement('article'); row.className = 'request-card';
    const details = document.createElement('div');
    const equipment = document.createElement('strong'); equipment.textContent = booking.equipmentName;
    const student = document.createElement('span'); student.textContent = `Student: ${booking.userName} · ${booking.userEmail}`;
    const time = document.createElement('span'); time.textContent = `${localTime(booking.startTime)} – ${localTime(booking.endTime)}`;
    const purpose = document.createElement('small'); purpose.textContent = `Purpose: ${booking.purpose}`;
    details.append(equipment, student, time, purpose);
    const actions = document.createElement('div'); actions.className = 'admin-booking-actions';
    const approve = document.createElement('button'); approve.type = 'button'; approve.className = 'approve-button'; approve.textContent = 'Approve';
    approve.addEventListener('click', () => decideBooking(booking.id, 'approve', approve));
    const reject = document.createElement('button'); reject.type = 'button'; reject.className = 'reject-button'; reject.textContent = 'Reject';
    reject.addEventListener('click', () => decideBooking(booking.id, 'reject', reject));
    actions.append(approve, reject); row.append(details, actions); bookingRequestList.append(row);
  }
}

function renderCurrentUse(bookings) {
  activeBookingList.replaceChildren();
  if (!bookings.length) { activeBookingList.append(emptyRow('No equipment has an active booking right now.')); return; }
  for (const booking of bookings) {
    const row = document.createElement('article'); row.className = 'request-card';
    const details = document.createElement('div');
    const equipment = document.createElement('strong'); equipment.textContent = `${booking.equipmentName} · In use`;
    const student = document.createElement('span'); student.textContent = `Student: ${booking.userName} · ${booking.userEmail}`;
    const time = document.createElement('span'); time.textContent = `${localTime(booking.startTime)} – ${localTime(booking.endTime)}`;
    const purpose = document.createElement('small'); purpose.textContent = `Purpose: ${booking.purpose}`;
    details.append(equipment, student, time, purpose); row.append(details); activeBookingList.append(row);
  }
}

async function decideBooking(id, decision, button) {
  const reason = decision === 'reject' ? window.prompt('Reason for rejecting this booking:') : null;
  if (decision === 'reject' && !reason?.trim()) return;
  button.disabled = true;
  try {
    const response = await fetch(`/api/bookings/${id}/${decision}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      ...(decision === 'reject' ? { body: JSON.stringify({ reason: reason.trim() }) } : {})
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || `Could not ${decision} booking.`);
    await loadDashboard(decision === 'approve' ? 'Booking approved.' : 'Booking rejected.');
  } catch (error) { button.disabled = false; setMessage(error.message, true); }
}

function renderEquipment(items) {
  equipmentGrid.replaceChildren();
  document.querySelector('#admin-instrument-count').textContent = items.length;
  for (const item of items) {
    const card = document.createElement('article');
    card.className = 'instrument-card';
    const top = document.createElement('div');
    top.className = 'instrument-card-top';
    const icon = document.createElement('span');
    icon.className = 'instrument-icon';
    icon.textContent = '⚗';
    const category = document.createElement('span');
    category.className = 'instrument-category';
    category.textContent = item.category;
    const status = document.createElement('span');
    status.className = `instrument-status ${item.status.toLowerCase().replaceAll(' ', '-')}`;
    status.textContent = item.status;
    top.append(icon, category, status);
    const name = document.createElement('h2');
    name.textContent = item.name;
    const description = document.createElement('p');
    description.className = 'instrument-description';
    description.textContent = item.description;
    const location = document.createElement('div');
    location.className = 'instrument-location';
    const locationLabel = document.createElement('span');
    locationLabel.textContent = item.location;
    location.append(locationLabel);
    card.append(top, name, description, location);
    equipmentGrid.append(card);
  }
}

async function loadDashboard(successMessage) {
  setMessage('Loading equipment and approval requests...');
  try {
    const [requestsResponse, equipmentResponse, bookingsResponse, activeResponse] = await Promise.all([
      fetch('/api/admin/pending'),
      fetch('/api/instruments'),
      fetch('/api/admin/bookings/pending'),
      fetch('/api/admin/bookings/current-use')
    ]);
    const [requests, equipment, bookings, activeBookings] = await Promise.all([
      requestsResponse.json(), equipmentResponse.json(), bookingsResponse.json(), activeResponse.json()
    ]);
    if (!requestsResponse.ok) throw new Error(requests.error || 'Could not load pending requests.');
    if (!equipmentResponse.ok) throw new Error(equipment.error || 'Could not load equipment catalog.');
    if (!bookingsResponse.ok) throw new Error(bookings.error || 'Could not load booking approvals.');
    if (!activeResponse.ok) throw new Error(activeBookings.error || 'Could not load current equipment use.');
    renderRequests(requests);
    renderEquipment(equipment);
    renderBookingApprovals(bookings);
    renderCurrentUse(activeBookings);
    setMessage(successMessage || `${requests.length} pending access request${requests.length === 1 ? '' : 's'} · ${bookings.length} booking approval${bookings.length === 1 ? '' : 's'} · ${equipment.length} equipment items.`);
  } catch (error) { setMessage(error.message, true); }
}

function showAdminDashboard() {
  loginPanel.hidden = true;
  adminContent.hidden = false;
  logoutButton.hidden = false;
  loadDashboard();
}

loginButton.addEventListener('click', async () => {
  setMessage('Signing in...');
  try {
    const response = await fetch('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: emailInput.value, password: passwordInput.value })
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Admin sign-in failed.');
    passwordInput.value = '';
    showAdminDashboard();
  } catch (error) { setMessage(error.message, true); }
});

passwordInput.addEventListener('keydown', event => { if (event.key === 'Enter') loginButton.click(); });
emailInput.addEventListener('keydown', event => { if (event.key === 'Enter') passwordInput.focus(); });
loadButton.addEventListener('click', () => loadDashboard());
logoutButton.addEventListener('click', async () => {
  await fetch('/api/admin/logout', { method: 'POST' });
  requestList.replaceChildren();
  bookingRequestList.replaceChildren();
  activeBookingList.replaceChildren();
  equipmentGrid.replaceChildren();
  adminContent.hidden = true;
  loginPanel.hidden = false;
  logoutButton.hidden = true;
  setMessage('Signed out.');
});

fetch('/api/admin/session').then(response => { if (response.ok) showAdminDashboard(); }).catch(() => {});
