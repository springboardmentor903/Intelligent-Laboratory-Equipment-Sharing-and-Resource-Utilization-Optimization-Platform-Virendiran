# Lab Resource Platform authentication

Registration and sign-in frontend with a Java Spring Boot API and PostgreSQL database.

## Requirements

- Java 21 or newer
- Maven 3.9 or newer

## Run locally on Windows

Start the local PostgreSQL database (Docker Desktop must be running):

```powershell
docker compose up -d
```

Set the PostgreSQL connection and the admin login credentials in PowerShell. Use a Gmail address that you own if you want that address to be the admin username; this does not create a Google mailbox.

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/labresource"
$env:DB_USER = "labresource"
$env:DB_PASSWORD = "labresource-local-dev"
$env:ADMIN_EMAIL = "your-admin@gmail.com"
$env:ADMIN_PASSWORD = "choose-a-long-unique-password"
```

Then start the app:

```sh
.\mvnw.cmd spring-boot:run
```

The first run downloads Apache Maven into this project; Maven does not need to be installed globally or added to PATH. Open <http://localhost:8080>. The application creates the PostgreSQL tables automatically. `compose.yaml` uses a local development password; replace it before exposing the database beyond your machine.

On macOS or Linux, use `./mvnw spring-boot:run` after adding the corresponding Unix wrapper, or install Maven and run `mvn spring-boot:run`.

New registrations remain pending until approved. From the main sign-in page, select **Admin login**, or open <http://localhost:8080/admin.html>. After signing in with `ADMIN_EMAIL` and `ADMIN_PASSWORD`, the admin dashboard shows pending access requests and the full equipment catalog from PostgreSQL. Approve requests there; once approved, the user can sign in and will be redirected to <http://localhost:8080/dashboard.html>. Admin access is disabled until both admin environment variables are set. Existing accounts from `lab-resource.sqlite` are copied to PostgreSQL at startup when that file exists; already-approved accounts keep their approval status. The original SQLite file is left in place.

The dashboard reads the instrument catalog from PostgreSQL. The app seeds a starter catalog of common lab equipment on first start; existing entries are preserved. Add or edit catalog rows in the PostgreSQL `instruments` table. The terminal prints events for database startup/migration, new users, sign-ins, approvals, and catalog loading. Passwords and password hashes are never written to the application log.

## API

- `POST /api/auth/register` — `{ "name", "email", "password" }`
- `POST /api/auth/login` — `{ "email", "password" }`
- `GET /api/auth/me` — current session user
- `POST /api/auth/logout` — clears the session cookie
- `POST /api/admin/login` — admin session login using configured credentials
- `GET /api/admin/session` — check the current admin session
- `POST /api/admin/logout` — end the admin session
- `GET /api/admin/pending` — list pending accounts; requires an admin session
- `POST /api/admin/users/{id}/approve` — approve an account; requires an admin session
- `GET /api/instruments` — signed-in users' instrument catalog

Passwords are hashed with PBKDF2-HMAC-SHA256 and a per-user random salt. The server uses an HTTP-only, `SameSite=Lax` session cookie. PostgreSQL stores account names, email addresses, password hashes and salts, approval status, and creation times.
