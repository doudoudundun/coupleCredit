# Trusted-device private network MySQL access design

## Goal
Use the current Mac as the app's MySQL server and let the installed Android app on trusted mobile devices access it over the internet through a private controlled network, without exposing MySQL directly to the public internet.

## Context observed in the current project
- The app currently hardcodes remote MySQL connection settings in `app/src/main/java/com/example/couplecredit/config/DatabaseConfig.java`.
- The app currently connects directly to MySQL over JDBC from Android code in `app/src/main/java/com/example/couplecredit/function/MySQLDatabaseHelper.java`.
- Connection pooling is handled in `app/src/main/java/com/example/couplecredit/database/DatabaseConnectionPool.java`.
- Existing schema clues in `app/src/main/java/com/example/couplecredit/database_create` show at least these core tables: `users`, `bills`, `couple_relationships`, `chat_messages`.

## Recommended approach
Use a private device network such as Tailscale between the Mac and the trusted mobile device. Run MySQL locally on the Mac, bind it so it is reachable over the private network, create a dedicated application database and user, and update the Android app to connect to the Mac's private-network address instead of the old public database host.

## Why this approach
- It meets the user's requirement that trusted devices can use the app remotely.
- It avoids exposing MySQL port 3306 directly to the public internet.
- It does not depend on knowing whether the Mac has a public IP or controllable router configuration.
- It is safer than the current direct-to-public-MySQL model, especially because the app contains database credentials client-side.

## Architecture
### Components
1. **Mac host**
   - Runs MySQL locally.
   - Hosts the application database for the Android app.
   - Joins a private controlled network.
   - Restricts database access to the private network path.

2. **Trusted mobile device**
   - Joins the same private controlled network.
   - Runs the Android app.
   - Reaches MySQL through the Mac's private-network address.

3. **Android app configuration**
   - Replaces the old host in `DatabaseConfig.java`.
   - Uses the new database name, user, and password for the local Mac-hosted database.
   - Keeps the current JDBC access pattern for now.

### Network model
- No public MySQL exposure.
- Traffic path is: Android app → private network → Mac-hosted MySQL.
- Only devices admitted to the private network can reach the service.

## Database scope
The local MySQL instance on the Mac should include a schema compatible with the app's current features. At minimum, create and validate these tables:
- `users`
- `bills`
- `couple_relationships`
- `chat_messages`

The schema should be aligned to the fields already reflected in `database_create`, with particular care for the `users` table because current code directly depends on it for registration, login, deletion, and password updates.

## Data flow
1. User opens the app on a trusted mobile device.
2. The mobile device is already connected to the private network.
3. The app reads MySQL host and credentials from `DatabaseConfig.java`.
4. The app opens a JDBC connection to the Mac's private-network address.
5. Queries run against the Mac-hosted local MySQL database.
6. Responses are returned to the app over the same private network path.

## Failure handling
- **MySQL not running on Mac**: fix local MySQL service before changing app configuration.
- **Private network connected but DB unreachable**: verify MySQL bind/listen settings, user grants, and macOS firewall rules.
- **DB reachable from Mac but not from mobile device**: verify private network addressing and device membership.
- **DB reachable but app fails**: verify `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, and JDBC connection parameters.
- **Schema mismatch**: adjust the local schema to match the fields currently required by the app code.

## Verification gates
Verification should happen in this order:
1. Confirm local MySQL is installed and running on the Mac.
2. Confirm the target database and required tables exist locally.
3. Confirm the dedicated app DB user can log in locally.
4. Confirm the Mac and mobile device are connected through the chosen private network.
5. Confirm the MySQL port is reachable over the Mac's private-network address from an authorized external device.
6. Confirm the Android app can complete at least one real connection-based action using the new DB settings.

## Rollback
- Record the current remote DB settings before changing the app.
- If the private-network configuration fails, temporarily restore the previous values in `DatabaseConfig.java`.
- Keep local database creation separate from app config changes so rollback stays simple.

## Out of scope for this change
- Replacing client-side JDBC with a proper backend API.
- Broader credential-hardening or auth redesign beyond creating a dedicated local DB user.
- Non-database app refactors unrelated to connectivity.
