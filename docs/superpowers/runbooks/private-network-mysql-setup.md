# Private Network MySQL Setup Runbook

## 1. Verify the local MySQL installation on the Mac

```bash
/usr/local/mysql/bin/mysql --version
ps -ef | grep "[m]ysqld"
ls -l /tmp/mysql.sock
```

Expected:
- the MySQL client reports an 8.x version
- a `mysqld` process is running
- `/tmp/mysql.sock` exists

If `/usr/local/mysql/bin/mysql` is missing, install MySQL first or update the paths below to match the local installation.

## 2. Apply the app schema and dedicated user

Use an account that has permission to create databases and users. If `root` requires a password, use the local root password.

```bash
/usr/local/mysql/bin/mysql -u root -p < db/mysql/init_private_network.sql
/usr/local/mysql/bin/mysql -u root -p -e "SHOW DATABASES LIKE 'couple_credit_private';"
/usr/local/mysql/bin/mysql -u root -p -e "USE couple_credit_private; SHOW TABLES;"
```

Expected:
- the database `couple_credit_private` is listed
- the tables `users`, `bills`, `couple_relationships`, and `chat_messages` exist

## 3. Verify the dedicated app user can log in locally

```bash
/usr/local/mysql/bin/mysql -u couple_app -p -h 127.0.0.1 -D couple_credit_private -e "SELECT 1;"
```

Expected: returns a single row with `1`

If login fails with `ERROR 1045`, the bootstrap SQL has not been applied yet or the password in the SQL file does not match the password you entered.

## 4. Install and join Tailscale on the Mac

If Homebrew is available and network access works:

```bash
/opt/homebrew/bin/brew install --cask tailscale
sudo tailscale up
```

If Homebrew update requests time out, install Tailscale manually and then run:

```bash
sudo tailscale up
```

To confirm the Mac has a private-network address:

```bash
tailscale ip -4
```

Expected: a stable Tailscale IPv4 address is returned.

## 5. Save machine-local app connection values

Replace `MAC_TAILSCALE_IP` with the IPv4 address returned by `tailscale ip -4`.

```properties
PRIVATE_DB_HOST=MAC_TAILSCALE_IP
PRIVATE_DB_PORT=3306
PRIVATE_DB_NAME=couple_credit_private
PRIVATE_DB_USER=couple_app
PRIVATE_DB_PASSWORD=ChangeThisPrivateDbPassword_2026!
```

Write those keys into `local.properties` next to `sdk.dir=`.

Expected: `local.properties` contains all five `PRIVATE_DB_*` keys.

## 6. Make MySQL listen for private-network traffic

Check the active MySQL config search path:

```bash
/usr/local/mysql/bin/mysql --help --verbose | grep -E "my.cnf|Default options"
```

The default search order on this Mac is:
- `/etc/my.cnf`
- `/etc/mysql/my.cnf`
- `/usr/local/mysql/etc/my.cnf`
- `~/.my.cnf`

Create the config file you want MySQL to use and set a non-loopback bind address. Example:

```ini
[mysqld]
bind-address = 0.0.0.0
port = 3306
socket = /tmp/mysql.sock
```

Then restart MySQL using the local installation's management flow and confirm it is listening:

```bash
sudo lsof -nP -iTCP:3306 -sTCP:LISTEN
```

Expected: MySQL is listening on port `3306`.

## 7. Join Tailscale on the phone

- Install the Tailscale mobile app.
- Sign in to the same tailnet as the Mac.
- Confirm the Mac appears in the device list.

## 8. Verify private-network reachability from another authorized device

On the Mac, get the address:

```bash
tailscale ip -4
```

From another authorized device on the same tailnet:

```bash
nc -vz MAC_TAILSCALE_IP 3306
/usr/local/mysql/bin/mysql -u couple_app -p -h MAC_TAILSCALE_IP -D couple_credit_private -e "SELECT 1;"
```

Expected:
- port `3306` is reachable
- `SELECT 1` succeeds as `couple_app`

## 9. App-side verification

From the Android worktree:

```bash
./gradlew testDebugUnitTest --tests com.example.couplecredit.DatabaseConfigSmokeTest
```

Expected: `BUILD SUCCESSFUL`

## 10. Known blockers observed on this Mac

- `mysqladmin ping` without credentials currently fails with `Access denied for user 'chengzi'@'localhost' (using password: NO)`, so use explicit MySQL credentials for admin checks.
- The bootstrap SQL has now been applied successfully with the local root account (`root`).
- `/usr/local/mysql/bin/mysql -u couple_app -p -h 127.0.0.1 -D couple_credit_private -e "SELECT 1;"` now succeeds with password `ChangeThisPrivateDbPassword_2026!`.
- `tailscale` is not currently in `PATH`.
- `/opt/homebrew/bin/brew` exists, but Homebrew network operations previously timed out while fetching update metadata.
