const form = document.querySelector('#auth-form');
const loginTab = document.querySelector('#login-tab');
const registerTab = document.querySelector('#register-tab');
const notice = document.querySelector('#notice');
const modeFields = {
  name: document.querySelector('#name-field'),
  institution: document.querySelector('#institution-field'),
  role: document.querySelector('#role-field'),
  confirm: document.querySelector('#confirm-field'),
  remember: document.querySelector('#remember-row'),
};
let mode = 'login';

function setMode(next) {
  mode = next;
  const registering = mode === 'register';
  loginTab.classList.toggle('active', !registering);
  registerTab.classList.toggle('active', registering);
  loginTab.setAttribute('aria-selected', String(!registering));
  registerTab.setAttribute('aria-selected', String(registering));
  modeFields.name.hidden = !registering;
  modeFields.institution.hidden = !registering;
  modeFields.role.hidden = !registering;
  modeFields.confirm.hidden = !registering;
  modeFields.remember.hidden = registering;
  document.querySelector('#heading').textContent = registering ? 'Create your account' : 'Welcome back';
  document.querySelector('#subheading').textContent = registering ? 'Set up your workspace and get your lab in sync.' : 'Sign in to pick up where your lab left off.';
  document.querySelector('#submit-label').textContent = registering ? 'Create your account' : 'Sign in to your workspace';
  document.querySelector('#password').autocomplete = registering ? 'new-password' : 'current-password';
  document.querySelector('#confirm-password').required = registering;
  document.querySelector('#forgot-link').hidden = registering;
  document.querySelector('#legal-copy').hidden = !registering;
  document.querySelector('#switch-copy').innerHTML = registering
    ? 'Already have an account? <button type="button" data-mode="login">Sign in</button>'
    : 'New to the platform? <button type="button" data-mode="register">Create an account</button>';
  clearErrors();
  notice.hidden = true;
}

function clearErrors() {
  document.querySelectorAll('.field-error').forEach(el => { el.textContent = ''; });
  document.querySelectorAll('.input-wrap').forEach(el => el.classList.remove('invalid'));
}
function fieldError(inputId, message) {
  const input = document.getElementById(inputId);
  const error = document.getElementById(`${inputId}-error`);
  if (error) error.textContent = message;
  input.closest('.input-wrap')?.classList.add('invalid');
  input.focus();
}
function showNotice(message, kind = 'error') {
  notice.textContent = message;
  notice.className = `notice ${kind}`;
  notice.hidden = false;
}

loginTab.addEventListener('click', () => setMode('login'));
registerTab.addEventListener('click', () => setMode('register'));
document.addEventListener('click', event => {
  const switcher = event.target.closest('[data-mode]');
  if (switcher) setMode(switcher.dataset.mode);
});
document.querySelector('.show-password').addEventListener('click', event => {
  const input = document.querySelector('#password');
  const showing = input.type === 'text';
  input.type = showing ? 'password' : 'text';
  event.currentTarget.textContent = showing ? 'Show' : 'Hide';
  event.currentTarget.setAttribute('aria-label', showing ? 'Show password' : 'Hide password');
});
document.querySelector('#forgot-link').addEventListener('click', event => {
  event.preventDefault();
  showNotice('Password reset is not set up yet. Please contact your lab administrator.', 'success');
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  clearErrors();
  notice.hidden = true;
  const email = document.querySelector('#email').value.trim();
  const password = document.querySelector('#password').value;
  const name = document.querySelector('#name').value.trim();
  const confirm = document.querySelector('#confirm-password').value;
  if (mode === 'register' && name.length < 2) return fieldError('name', 'Enter your name (at least 2 characters).');
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return fieldError('email', 'Enter a valid email address.');
  if (password.length < 8) return fieldError('password', 'Use at least 8 characters.');
  if (mode === 'register' && confirm !== password) return fieldError('confirm-password', 'Passwords do not match.');

  const button = document.querySelector('#submit-button');
  button.disabled = true;
  document.querySelector('#submit-label').textContent = mode === 'register' ? 'Creating your account…' : 'Signing you in…';
  try {
    const response = await fetch(`/api/auth/${mode === 'register' ? 'register' : 'login'}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(mode === 'register' ? { name, email, password,
        institutionId: document.querySelector('#institution-id').value || null,
        role: document.querySelector('#account-role').value } : { email, password }),
    });
    const result = response.status === 204 ? {} : await response.json();
    if (!response.ok) throw new Error(result.error || 'Something went wrong. Please try again.');
    if (mode === 'register') {
      window.location.assign('/pending.html');
      return;
    }
    form.reset();
    showNotice(`You're signed in as ${result.user.name}. Your lab workspace is ready.`, 'success');
    window.location.assign('/dashboard.html');
  } catch (error) {
    showNotice(error instanceof TypeError ? 'Could not reach the server. Check your connection and try again.' : error.message);
  } finally {
    button.disabled = false;
    document.querySelector('#submit-label').textContent = mode === 'register' ? 'Create your account' : 'Sign in to your workspace';
  }
});

fetch('/api/auth/me').then(async response => {
  if (!response.ok) return;
  window.location.replace('/dashboard.html');
}).catch(() => {});

fetch('/api/institutions').then(response => response.ok ? response.json() : []).then(institutions => {
  const select=document.querySelector('#institution-id');
  for(const institution of institutions){const option=document.createElement('option');option.value=institution.id;option.textContent=institution.name;select.append(option);}
}).catch(() => {});
