# 数据库主键统一与结构重建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将项目中的用户主键引用统一到 `users.id`，修复仓库内 SQL 结构隐患，并提供一份可直接执行的数据库重建脚本。

**Architecture:** 以当前 server 与 Android 实际代码为准，统一 `users` 作为主表、`couple_relationships` 作为关系表、`bills`/`inventory`/`chat_messages` 作为业务表。优先修复仓库内初始化 SQL 与 migration SQL，再补一份重建型 SQL 脚本，最后通过代码搜索和构建验证确认没有残留的 `users.user_id` 引用。

**Tech Stack:** MySQL, Node.js/Express, Android Java, Gradle

---

## File Map

- Modify: `server/migrations/002_create_inventory.sql` — 将 inventory 的用户外键从 `users(user_id)` 改为 `users(id)`
- Modify: `db/mysql/init_private_network.sql` — 统一核心 5 表结构到 `users.id` 版本，并补充外键与约束
- Create: `db/mysql/rebuild_unified_schema.sql` — 面向现有数据库的强制重建脚本
- Modify: `app/src/main/java/com/example/couplecredit/repository/CloudChatRepository.java` — 将单人聊天查询从 `relationship_id = 0` 兼容逻辑收敛为以 `NULL` 为主
- Verify: repo-wide grep for `users(user_id)` / `REFERENCES users(user_id)` / `relationship_id = 0`
- Validate: Android build and SQL reference scan

### Task 1: Fix inventory migration foreign key

**Files:**
- Modify: `server/migrations/002_create_inventory.sql`

- [ ] **Step 1: Read the migration and confirm the broken foreign key**

Expected line to replace:

```sql
FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
```

- [ ] **Step 2: Replace it with the unified `users.id` foreign key**

```sql
FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
```

- [ ] **Step 3: Verify the file contains no remaining `users(user_id)` reference**

Run:

```bash
grep -n "users(user_id)\|REFERENCES users(user_id)" server/migrations/002_create_inventory.sql
```

Expected: no output

### Task 2: Normalize the bootstrap schema

**Files:**
- Modify: `db/mysql/init_private_network.sql`

- [ ] **Step 1: Ensure `users` uses `id` as the primary key and keeps runtime-required fields**

Required shape:

```sql
CREATE TABLE IF NOT EXISTS users (
    id INT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    avatar VARCHAR(255) NULL,
    nickname VARCHAR(50) NULL,
    invite_code VARCHAR(64) NULL,
    status ENUM('active', 'inactive', 'banned') NOT NULL DEFAULT 'active',
    couple_status ENUM('single', 'pending', 'coupled') NOT NULL DEFAULT 'single',
    relationship_id INT UNSIGNED NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
```

- [ ] **Step 2: Ensure `couple_relationships` points to `users.id`**

```sql
CONSTRAINT fk_relationships_user_1
    FOREIGN KEY (user_id_1) REFERENCES users(id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
CONSTRAINT fk_relationships_user_2
    FOREIGN KEY (user_id_2) REFERENCES users(id)
    ON DELETE CASCADE
    ON UPDATE CASCADE
```

- [ ] **Step 3: Ensure `bills`, `inventory`, and `chat_messages` all use `users.id`**

```sql
CONSTRAINT fk_bills_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
CONSTRAINT fk_inventory_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
CONSTRAINT fk_chat_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
    ON UPDATE CASCADE
```

- [ ] **Step 4: Keep `chat_messages.relationship_id` nullable**

```sql
relationship_id INT UNSIGNED NULL,
```

- [ ] **Step 5: Verify there is no `users(user_id)` reference left in the bootstrap SQL**

Run:

```bash
grep -n "users(user_id)\|REFERENCES users(user_id)" db/mysql/init_private_network.sql
```

Expected: no output

### Task 3: Add a destructive rebuild script for existing databases

**Files:**
- Create: `db/mysql/rebuild_unified_schema.sql`

- [ ] **Step 1: Add a clear warning header and disable FK checks**

```sql
-- WARNING: destructive rebuild script
SET FOREIGN_KEY_CHECKS = 0;
```

- [ ] **Step 2: Drop dependent tables in safe order**

```sql
DROP TABLE IF EXISTS chat_messages;
DROP TABLE IF EXISTS inventory;
DROP TABLE IF EXISTS bills;
DROP TABLE IF EXISTS couple_relationships;
DROP TABLE IF EXISTS users;
```

- [ ] **Step 3: Recreate the unified schema**

Include the same `users`, `couple_relationships`, `bills`, `inventory`, and `chat_messages` definitions used by `db/mysql/init_private_network.sql`.

- [ ] **Step 4: Re-enable FK checks**

```sql
SET FOREIGN_KEY_CHECKS = 1;
```

- [ ] **Step 5: Verify the script contains all 5 tables**

Run:

```bash
grep -n "CREATE TABLE IF NOT EXISTS users\|CREATE TABLE IF NOT EXISTS couple_relationships\|CREATE TABLE IF NOT EXISTS bills\|CREATE TABLE IF NOT EXISTS inventory\|CREATE TABLE IF NOT EXISTS chat_messages" db/mysql/rebuild_unified_schema.sql
```

Expected: five matches

### Task 4: Remove `relationship_id = 0` assumptions from cloud chat queries

**Files:**
- Modify: `app/src/main/java/com/example/couplecredit/repository/CloudChatRepository.java`

- [ ] **Step 1: Update single-user chat queries to treat no relationship as `NULL`**

Replace:

```java
WHERE cm.user_id = ? AND (cm.relationship_id IS NULL OR cm.relationship_id = 0) AND cm.is_deleted = 0
```

With:

```java
WHERE cm.user_id = ? AND cm.relationship_id IS NULL AND cm.is_deleted = 0
```

- [ ] **Step 2: Update single-user like/delete queries the same way**

Replace:

```java
WHERE id = ? AND user_id = ? AND (relationship_id IS NULL OR relationship_id = 0) AND is_deleted = 0
```

With:

```java
WHERE id = ? AND user_id = ? AND relationship_id IS NULL AND is_deleted = 0
```

- [ ] **Step 3: Verify no `relationship_id = 0` fallback remains in this file**

Run:

```bash
grep -n "relationship_id = 0" app/src/main/java/com/example/couplecredit/repository/CloudChatRepository.java
```

Expected: no output

### Task 5: Validate the repository state

**Files:**
- Verify only

- [ ] **Step 1: Search the repo for old broken user foreign key references**

Run:

```bash
grep -RIn "users(user_id)\|REFERENCES users(user_id)\|users\.user_id" server db app
```

Expected: no output, or docs-only matches outside runtime files

- [ ] **Step 2: Build the Android app**

Run:

```bash
"/Users/chengzi/Code/GitHub/coupleCredit/gradlew" -p "/Users/chengzi/Code/GitHub/coupleCredit" :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: If server-side SQL files changed only, do a final syntax/reference sanity check**

Run:

```bash
node --check "/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/inventory.js"
```

Expected: no output
