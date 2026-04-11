# Private Network MySQL Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Android app from the old public MySQL server to a MySQL instance running on the current Mac and make the app reachable from trusted mobile devices over a private controlled network.

**Architecture:** Keep the app's current JDBC-based access pattern, but point it at a Mac-hosted MySQL database that is reachable only through a private device network such as Tailscale. Create the database, schema, and least-privilege app user on the Mac first, load connection values from local machine configuration, then verify connectivity locally and across the private network.

**Tech Stack:** Android (Java), Gradle Kotlin DSL, MySQL 8.x or compatible local server, macOS, Tailscale private networking, JDBC via `mysql:mysql-connector-java:5.1.47`

---

## File structure and responsibilities

- Create: `db/mysql/init_private_network.sql` — idempotent SQL bootstrap for the Mac-hosted database, tables, and least-privilege app user.
- Create: `docs/superpowers/runbooks/private-network-mysql-setup.md` — operator runbook with exact MySQL install, SQL bootstrap, Tailscale setup, local config, and verification commands.
- Modify: `app/build.gradle.kts` — load private DB connection values from `local.properties` into generated `BuildConfig` fields.
- Modify: `app/src/main/java/com/example/couplecredit/config/DatabaseConfig.java` — replace hardcoded legacy public DB values with `BuildConfig`-backed private-network values.
- Modify: `app/src/main/java/com/example/couplecredit/function/MySQLDatabaseHelper.java` — add a minimal connection smoke-test method for manual verification.
- Create: `app/src/test/java/com/example/couplecredit/DatabaseConfigSmokeTest.java` — verify the app points at the private database configuration path.
- Modify: `local.properties` — store machine-local private DB host and credentials without committing them.

---

### Task 1: Define and bootstrap the local MySQL schema

**Files:**
- Create: `db/mysql/init_private_network.sql`
- Test: `db/mysql/init_private_network.sql`

- [ ] **Step 1: Write the failing verification command**

```bash
mysql -u root -p -e "USE couple_credit_private; SHOW TABLES;"
```

Expected: FAIL with `ERROR 1049 (42000): Unknown database 'couple_credit_private'`.

- [ ] **Step 2: Create the SQL bootstrap file**

```sql
CREATE DATABASE IF NOT EXISTS couple_credit_private CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'couple_app'@'%' IDENTIFIED BY 'ChangeThisPrivateDbPassword_2026!';
ALTER USER 'couple_app'@'%' IDENTIFIED BY 'ChangeThisPrivateDbPassword_2026!';

GRANT SELECT, INSERT, UPDATE, DELETE ON couple_credit_private.* TO 'couple_app'@'%';
FLUSH PRIVILEGES;

USE couple_credit_private;

CREATE TABLE IF NOT EXISTS users (
    id INT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    avatar VARCHAR(255) NULL,
    status ENUM('active','inactive','banned') DEFAULT 'active',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    invite_code VARCHAR(10) NULL,
    relationship_id INT UNSIGNED NULL,
    couple_status ENUM('single','pending','coupled') DEFAULT 'single',
    nickname VARCHAR(50) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_status (status),
    KEY idx_users_relationship_id (relationship_id)
);

CREATE TABLE IF NOT EXISTS bills (
    bill_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    relationship_id INT UNSIGNED NULL,
    owner TINYINT(1) NOT NULL,
    user_id INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    date DATE NOT NULL,
    time TIME NOT NULL,
    income_type TINYINT(1) NOT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_help TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (bill_id),
    KEY idx_bills_relationship_id (relationship_id),
    KEY idx_bills_user_id (user_id)
);

CREATE TABLE IF NOT EXISTS couple_relationships (
    relationship_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id_1 INT NOT NULL,
    user_id_2 INT NOT NULL,
    status ENUM('active','dissolved') NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (relationship_id),
    KEY idx_relationships_user_1 (user_id_1),
    KEY idx_relationships_user_2 (user_id_2)
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    relationship_id INT UNSIGNED NOT NULL,
    user_id INT NOT NULL,
    content TEXT NOT NULL,
    message_type ENUM('text','image','file','system') DEFAULT 'text',
    display_time VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    avatar_url VARCHAR(500) NULL,
    is_liked TINYINT(1) DEFAULT 0,
    is_deleted TINYINT(1) DEFAULT 0,
    bill_id INT UNSIGNED NULL,
    is_bill_candidate TINYINT(1) DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_chat_relationship_id (relationship_id),
    KEY idx_chat_user_id (user_id),
    KEY idx_chat_bill_id (bill_id)
);
```

- [ ] **Step 3: Run the SQL bootstrap to make the schema exist**

Run:
```bash
mysql -u root -p < db/mysql/init_private_network.sql
```

Expected: completes without SQL errors.

- [ ] **Step 4: Run verification to confirm the schema now exists**

Run:
```bash
mysql -u root -p -e "USE couple_credit_private; SHOW TABLES; DESCRIBE users; DESCRIBE bills; DESCRIBE couple_relationships; DESCRIBE chat_messages;"
```

Expected: PASS and shows the four required tables and the expected columns.

- [ ] **Step 5: Commit**

```bash
git add db/mysql/init_private_network.sql
git commit -m "feat: add local mysql bootstrap schema"
```

### Task 2: Write the operator runbook for Mac, Tailscale, and local config

**Files:**
- Create: `docs/superpowers/runbooks/private-network-mysql-setup.md`
- Modify: `local.properties`
- Test: `docs/superpowers/runbooks/private-network-mysql-setup.md`

- [ ] **Step 1: Write the failing verification commands**

```bash
mysqladmin ping
which tailscale
```

Expected: at least one command fails or shows setup is incomplete before the runbook is followed.

- [ ] **Step 2: Create the runbook with exact setup commands**

```md
# Private Network MySQL Setup Runbook

## 1. Install and start MySQL on the Mac

~~~bash
brew install mysql
brew services start mysql
mysqladmin ping
~~~

Expected: `mysqld is alive`

## 2. Apply the app schema and dedicated user

~~~bash
mysql -u root -p < db/mysql/init_private_network.sql
mysql -u root -p -e "SHOW DATABASES LIKE 'couple_credit_private';"
~~~

Expected: the database `couple_credit_private` is listed.

## 3. Install and join Tailscale on the Mac

~~~bash
brew install --cask tailscale
sudo tailscale up
tailscale ip -4
~~~

Expected: a stable Tailscale IPv4 address is returned.

## 4. Save machine-local app connection values

~~~bash
MAC_TAILSCALE_IP="$(tailscale ip -4 | head -n1)"
python3 - <<'PY'
from pathlib import Path
import os
path = Path("local.properties")
existing = path.read_text().splitlines() if path.exists() else []
filtered = [
    line for line in existing
    if not line.startswith((
        "PRIVATE_DB_HOST=",
        "PRIVATE_DB_PORT=",
        "PRIVATE_DB_NAME=",
        "PRIVATE_DB_USER=",
        "PRIVATE_DB_PASSWORD=",
    ))
]
filtered.extend([
    f"PRIVATE_DB_HOST={os.environ['MAC_TAILSCALE_IP']}",
    "PRIVATE_DB_PORT=3306",
    "PRIVATE_DB_NAME=couple_credit_private",
    "PRIVATE_DB_USER=couple_app",
    "PRIVATE_DB_PASSWORD=ChangeThisPrivateDbPassword_2026!",
])
path.write_text("\n".join(filtered) + "\n")
PY
~~~

Expected: `local.properties` contains the five `PRIVATE_DB_*` keys.

## 5. Allow MySQL to listen for private-network traffic

~~~bash
mysql --help | grep my.cnf
sudo lsof -nP -iTCP:3306 -sTCP:LISTEN
~~~

Expected: MySQL is listening on port 3306 after restart.

## 6. Verify the dedicated app user can log in locally

~~~bash
mysql -u couple_app -p -h 127.0.0.1 -D couple_credit_private -e "SELECT 1;"
~~~

Expected: returns `1`.

## 7. Join Tailscale on the phone
- Install the Tailscale mobile app.
- Sign in to the same tailnet as the Mac.
- Confirm the Mac appears in the device list.

## 8. Verify private-network reachability from another authorized device

~~~bash
MAC_TAILSCALE_IP="$(tailscale ip -4 | head -n1)"
nc -vz "$MAC_TAILSCALE_IP" 3306
mysql -u couple_app -p -h "$MAC_TAILSCALE_IP" -D couple_credit_private -e "SELECT 1;"
~~~

Expected: port 3306 is reachable and `SELECT 1` succeeds.
```

- [ ] **Step 3: Run a documentation-guided local verification**

Run:
```bash
mysqladmin ping && tailscale ip -4
```

Expected: PASS once MySQL and Tailscale are installed and active.

- [ ] **Step 4: Confirm the runbook is internally consistent**

Run:
```bash
grep -n "couple_credit_private\|couple_app\|PRIVATE_DB_\|3306\|tailscale" docs/superpowers/runbooks/private-network-mysql-setup.md
```

Expected: PASS and all identifiers match the SQL bootstrap and app config plan.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/runbooks/private-network-mysql-setup.md
git commit -m "docs: add private network mysql setup runbook"
```

### Task 3: Load private DB values from `local.properties` and repoint app config

**Files:**
- Modify: `app/build.gradle.kts:1-88`
- Modify: `app/src/main/java/com/example/couplecredit/config/DatabaseConfig.java:1-27`
- Modify: `local.properties`
- Test: `app/src/test/java/com/example/couplecredit/DatabaseConfigSmokeTest.java`

- [ ] **Step 1: Write the failing configuration checks**

```bash
grep -n "101.37.68.240\|demodb\|root" app/src/main/java/com/example/couplecredit/config/DatabaseConfig.java
grep -n "PRIVATE_DB_HOST\|PRIVATE_DB_NAME\|PRIVATE_DB_USER\|PRIVATE_DB_PASSWORD" app/build.gradle.kts
```

Expected: first command PASSes with old values; second command FAILs with no matches.

- [ ] **Step 2: Modify `app/build.gradle.kts` to export local private DB values into `BuildConfig`**

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localConfig(name: String, defaultValue: String = ""): String {
    return localProperties.getProperty(name, defaultValue)
}

android {
    namespace = "com.example.couplecredit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.couplecredit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PRIVATE_DB_HOST", "\"${localConfig("PRIVATE_DB_HOST")}\"")
        buildConfigField("String", "PRIVATE_DB_PORT", "\"${localConfig("PRIVATE_DB_PORT", "3306")}\"")
        buildConfigField("String", "PRIVATE_DB_NAME", "\"${localConfig("PRIVATE_DB_NAME", "couple_credit_private")}\"")
        buildConfigField("String", "PRIVATE_DB_USER", "\"${localConfig("PRIVATE_DB_USER", "couple_app")}\"")
        buildConfigField("String", "PRIVATE_DB_PASSWORD", "\"${localConfig("PRIVATE_DB_PASSWORD")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation("androidx.room:room-runtime:2.6.1")
    implementation(libs.androidx.room.common.jvm)
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("mysql:mysql-connector-java:5.1.47")
    implementation("com.zaxxer:HikariCP-java7:2.4.13")

    implementation("androidx.multidex:multidex:2.0.1")
    implementation("com.github.bumptech.glide:glide:4.15.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
```

- [ ] **Step 3: Modify `DatabaseConfig.java` to use `BuildConfig` instead of hardcoded legacy values**

```java
package com.example.couplecredit.config;

import com.example.couplecredit.BuildConfig;

/**
 * 统一的数据库配置类
 * 管理所有数据库连接相关的常量
 */
public class DatabaseConfig {
    public static final String DB_HOST = BuildConfig.PRIVATE_DB_HOST;
    public static final String DB_PORT = BuildConfig.PRIVATE_DB_PORT;
    public static final String DB_NAME = BuildConfig.PRIVATE_DB_NAME;
    public static final String DB_USER = BuildConfig.PRIVATE_DB_USER;
    public static final String DB_PASSWORD = BuildConfig.PRIVATE_DB_PASSWORD;
    public static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME +
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" +
            "&connectTimeout=10000&socketTimeout=30000&autoReconnect=true" +
            "&maxReconnects=3&initialTimeout=2&testOnBorrow=true" +
            "&validationQuery=SELECT 1&testWhileIdle=true";

    public static final String PREF_AVATAR_URI = "avatar_uri_";

    private DatabaseConfig() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
```

- [ ] **Step 4: Add the local machine values to `local.properties`**

Run:
```bash
MAC_TAILSCALE_IP="$(tailscale ip -4 | head -n1)"
python3 - <<'PY'
from pathlib import Path
import os
path = Path("local.properties")
existing = path.read_text().splitlines() if path.exists() else []
filtered = [
    line for line in existing
    if not line.startswith((
        "PRIVATE_DB_HOST=",
        "PRIVATE_DB_PORT=",
        "PRIVATE_DB_NAME=",
        "PRIVATE_DB_USER=",
        "PRIVATE_DB_PASSWORD=",
    ))
]
filtered.extend([
    f"PRIVATE_DB_HOST={os.environ['MAC_TAILSCALE_IP']}",
    "PRIVATE_DB_PORT=3306",
    "PRIVATE_DB_NAME=couple_credit_private",
    "PRIVATE_DB_USER=couple_app",
    "PRIVATE_DB_PASSWORD=ChangeThisPrivateDbPassword_2026!",
])
path.write_text("\n".join(filtered) + "\n")
PY
```

Expected: `local.properties` now contains the private DB keys with the current Mac Tailscale IP.

- [ ] **Step 5: Run the configuration checks to verify the legacy values are gone and the new path is wired in**

Run:
```bash
grep -n "101.37.68.240\|demodb\|root" app/src/main/java/com/example/couplecredit/config/DatabaseConfig.java
grep -n "PRIVATE_DB_HOST\|PRIVATE_DB_NAME\|PRIVATE_DB_USER\|PRIVATE_DB_PASSWORD" app/build.gradle.kts
```

Expected: first command FAILs with no matches; second command PASSes with the new `BuildConfig` wiring.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/example/couplecredit/config/DatabaseConfig.java
git commit -m "chore: load private mysql config from local properties"
```

### Task 4: Add an app-side connectivity smoke test entry point

**Files:**
- Modify: `app/src/main/java/com/example/couplecredit/function/MySQLDatabaseHelper.java:20-120`
- Create: `app/src/test/java/com/example/couplecredit/DatabaseConfigSmokeTest.java`
- Test: `app/src/test/java/com/example/couplecredit/DatabaseConfigSmokeTest.java`

- [ ] **Step 1: Write the failing test file**

```java
package com.example.couplecredit;

import com.example.couplecredit.config.DatabaseConfig;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatabaseConfigSmokeTest {
    @Test
    public void dbConfigPointsAtPrivateDatabase() {
        assertEquals("couple_credit_private", DatabaseConfig.DB_NAME);
        assertEquals("couple_app", DatabaseConfig.DB_USER);
        assertFalse(DatabaseConfig.DB_URL.contains("101.37.68.240"));
        assertTrue(DatabaseConfig.DB_URL.contains(DatabaseConfig.DB_NAME));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails before implementation**

Run:
```bash
./gradlew testDebugUnitTest --tests com.example.couplecredit.DatabaseConfigSmokeTest
```

Expected: FAIL because the test file does not yet exist or the config still points at the legacy values.

- [ ] **Step 3: Add the test file and add a helper method for manual DB smoke checks**

```java
public static void testDatabaseConnection(DatabaseCallback callback) {
    new AsyncTask<Void, Void, Boolean>() {
        private String errorMessage = "";

        @Override
        protected Boolean doInBackground(Void... voids) {
            Connection connection = null;
            PreparedStatement statement = null;
            java.sql.ResultSet resultSet = null;

            try {
                connection = getConnection();
                statement = connection.prepareStatement("SELECT 1");
                resultSet = statement.executeQuery();
                return resultSet.next() && resultSet.getInt(1) == 1;
            } catch (Exception e) {
                errorMessage = e.getMessage();
                Log.e(TAG, "数据库连接测试失败: " + e.getMessage(), e);
                return false;
            } finally {
                closeResources(connection, statement, resultSet);
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (callback != null) {
                if (success) {
                    callback.onSuccess("数据库连接测试成功");
                } else {
                    callback.onError("数据库连接测试失败: " + errorMessage);
                }
            }
        }
    }.execute();
}
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run:
```bash
./gradlew testDebugUnitTest --tests com.example.couplecredit.DatabaseConfigSmokeTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/example/couplecredit/DatabaseConfigSmokeTest.java app/src/main/java/com/example/couplecredit/function/MySQLDatabaseHelper.java
git commit -m "test: add database connectivity smoke coverage"
```

### Task 5: Perform end-to-end verification on the Mac and private network

**Files:**
- Modify: `docs/superpowers/runbooks/private-network-mysql-setup.md:1-999`
- Test: `docs/superpowers/runbooks/private-network-mysql-setup.md`

- [ ] **Step 1: Write the failing end-to-end checks**

```bash
mysql -u couple_app -p -h 127.0.0.1 -D couple_credit_private -e "SELECT 1;"
MAC_TAILSCALE_IP="$(tailscale ip -4 | head -n1)"
nc -vz "$MAC_TAILSCALE_IP" 3306
```

Expected: at least one command fails until the schema, user, listener, and private network are all configured.

- [ ] **Step 2: Run the local DB verification**

Run:
```bash
mysql -u couple_app -p -h 127.0.0.1 -D couple_credit_private -e "SELECT 1;"
```

Expected: PASS and returns `1`.

- [ ] **Step 3: Run the private-network reachability verification**

Run:
```bash
MAC_TAILSCALE_IP="$(tailscale ip -4 | head -n1)"
nc -vz "$MAC_TAILSCALE_IP" 3306
mysql -u couple_app -p -h "$MAC_TAILSCALE_IP" -D couple_credit_private -e "SELECT 1;"
```

Expected: PASS from another authorized device on the same tailnet.

- [ ] **Step 4: Run the Android unit tests and build verification**

Run:
```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Expected: PASS for unit tests and a successful debug APK build.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/runbooks/private-network-mysql-setup.md
git commit -m "chore: verify private network mysql migration"
```

## Self-review
- Spec coverage: the plan covers local MySQL provisioning, schema creation, Tailscale setup, local private DB configuration, Android config changes, smoke testing, and end-to-end verification.
- Placeholder scan: removed plan placeholders by deriving the Mac Tailscale IP through commands and loading it into `local.properties`.
- Type consistency: database names, user names, file paths, and verification commands are consistent across tasks.
