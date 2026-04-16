# External API tunnel design

## Goal
Allow the Android app running on a real device outside the home network to use the Mac-hosted local service without exposing MySQL directly to the public internet and without needing router admin access.

## Context observed in the current project
- The Android app already uses the HTTP API for registration, login, and bill creation through `BuildConfig.PRIVATE_API_BASE_URL`.
- The local API already runs on the Mac through launchd at `127.0.0.1:8082`.
- The user's network path is blocked by upstream NAT / router access constraints, so inbound port forwarding is not available.
- `local.properties` currently does not override `PRIVATE_API_BASE_URL`, so Android Studio builds still default to emulator-only addressing.

## Recommended approach
Keep MySQL private on the Mac and keep the existing local HTTP API on `127.0.0.1:8082`. Expose only the HTTP API through an outbound SSH reverse tunnel to a public relay service. Point Android Studio builds at the resulting public HTTPS URL via `local.properties`.

## Why this approach
- It works without router admin or upstream port forwarding.
- It preserves the already-completed migration away from direct Android-to-MySQL writes for the critical flows already moved to the API.
- It avoids publishing MySQL port `3306` and database credentials directly to the public internet.
- It fits the current code structure because the app already talks to `BuildConfig.PRIVATE_API_BASE_URL`.

## Architecture
### Components
1. **Mac host**
   - Runs MySQL locally.
   - Runs the Node API locally on `127.0.0.1:8082`.
   - Opens an outbound SSH tunnel to a public relay.

2. **Public tunnel endpoint**
   - Terminates public HTTPS traffic.
   - Forwards requests through the SSH tunnel to the Mac's local API.

3. **Android app**
   - Uses the public HTTPS base URL from `local.properties`.
   - Continues calling `/api/auth/register`, `/api/auth/login`, and `/api/bills` through `AuthApiClient`.

### Network model
- Traffic path is: Android app -> public HTTPS tunnel URL -> outbound SSH tunnel -> Mac local API -> local MySQL.
- MySQL remains bound only for local/private use and is never exposed publicly.

## Data flow
1. Launchd starts the API on the Mac.
2. The Mac opens the SSH reverse tunnel.
3. The tunnel service returns a public HTTPS URL.
4. Android Studio builds inject that URL into `BuildConfig.PRIVATE_API_BASE_URL` through `local.properties`.
5. The real device calls the public URL.
6. Requests reach the local API and then the local MySQL database.

## Failure handling
- **Local API unhealthy**: fix `http://127.0.0.1:8082` first before debugging the tunnel.
- **Tunnel cannot connect**: verify outbound SSH connectivity and tunnel service availability.
- **Tunnel URL changes**: update `local.properties` and rebuild before running on device.
- **Device can open URL but app fails**: verify `PRIVATE_API_BASE_URL` in the generated build matches the current public URL.
- **HTTP endpoint works but feature fails**: debug API-side validation or database behavior, not network reachability.

## Verification gates
1. Confirm `GET http://127.0.0.1:8082/api/auth/healthz` returns `200` locally.
2. Confirm the tunnel returns a public HTTPS URL.
3. Confirm the public health endpoint returns `200` from outside the Mac.
4. Update `local.properties` with the public base URL.
5. Rebuild and run from Android Studio on the real device.
6. Verify registration, login, and bill creation all succeed through the public URL.

## Rollback
- Remove the `PRIVATE_API_BASE_URL` override from `local.properties` or point it back to a local address.
- Stop the SSH tunnel process.
- Keep the local launchd-managed API unchanged so local verification still works.

## Out of scope for this change
- Exposing MySQL directly to the public internet.
- Migrating every remaining app feature to the HTTP API in this step.
- Permanent production-grade hosting or a custom domain.
