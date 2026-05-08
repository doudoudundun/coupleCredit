# Daily High-Priority Todo Reminder

## Overview

Every day at 12:00 (UTC+8), the server queries all open high-priority todos and sends a push notification to each affected user and their partner. FCM is the primary delivery channel; app polling serves as a fallback.

## Database

### `fcm_tokens`

Stores device push tokens for each user.

| Column | Type | Notes |
|--------|------|-------|
| token_id | INT UNSIGNED AUTO_INCREMENT PK | |
| user_id | INT UNSIGNED NOT NULL | FK to users |
| token | VARCHAR(255) NOT NULL UNIQUE | FCM device token |
| device_id | VARCHAR(100) NULL | Optional device identifier |
| created_at | DATETIME | DEFAULT CURRENT_TIMESTAMP |
| updated_at | DATETIME | ON UPDATE CURRENT_TIMESTAMP |

Index: `idx_fcm_user (user_id)`

### `notifications`

Persistent notification records for polling fallback and history.

| Column | Type | Notes |
|--------|------|-------|
| notification_id | INT UNSIGNED AUTO_INCREMENT PK | |
| user_id | INT UNSIGNED NOT NULL | FK to users |
| type | ENUM('todo_reminder', 'system') | DEFAULT 'system' |
| title | VARCHAR(120) NOT NULL | |
| body | VARCHAR(500) NOT NULL | |
| related_id | INT UNSIGNED NULL | Associated todo_id |
| is_read | TINYINT(1) | DEFAULT 0 |
| created_at | DATETIME | DEFAULT CURRENT_TIMESTAMP |

Index: `idx_notif_user_read (user_id, is_read, created_at)`

## Server

### New dependencies

- `node-cron` — cron scheduling
- `firebase-admin` — FCM push

### New files

**`server/src/services/fcmService.js`**

- `initializeApp()` — init firebase-admin with service account key
- `sendMulticast(tokens, notification)` — send FCM to multiple tokens
- Handles token cleanup on invalid/expired tokens

**`server/src/services/notificationService.js`**

- `sendTodoReminder()` — called by cron at 12:00 daily
  1. Query all todos where `priority = 'high' AND status = 'open'`
  2. For each todo, determine recipients: creator + partner (via relationship_id)
  3. Insert a row into `notifications` per recipient per todo
  4. Send FCM push per todo per recipient (individual notifications, not summary)
- Notification title: "高优先级待办提醒"
- Notification body: todo title

**`server/src/routes/fcm.js`** — mounted at `/api/fcm`

- `POST /register` — register/update FCM token (body: `{ userId, token, deviceId }`)
- `DELETE /token` — remove token on logout (body: `{ token }`)

**`server/src/routes/notifications.js`** — mounted at `/api/notifications`

- `GET /` — list unread notifications for user (query: `userId`)
- `PUT /:id/read` — mark notification as read

### Cron registration

In `server/src/index.js`:

```js
const cron = require('node-cron');
cron.schedule('0 12 * * *', () => notificationService.sendTodoReminder());
```

### Migration files

- `server/migrations/011_create_fcm_tokens.sql`
- `server/migrations/012_create_notifications.sql`

## Android

### New dependencies

`build.gradle.kts` (app):
```kotlin
implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
implementation("com.google.firebase:firebase-messaging")
```

Project-level `build.gradle.kts`:
```kotlin
classpath("com.google.gms:google-services:4.4.0")
```

Apply plugin: `com.google.gms.google-services`

### New/modified files

**`MyFirebaseMessagingService`** (new)
- `onNewToken()` → POST /api/fcm/register
- `onMessageReceived()` → show NotificationCompat with todo title/body, tap opens TodoFragment

**`NotificationHelper`** (new utility)
- Create `NotificationChannel` "todo_reminders" (IMPORTANCE_HIGH)
- Check/request `POST_NOTIFICATIONS` permission (Android 13+)

**`AndroidManifest.xml`** (modify)
- Add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
- Register `<service android:name=".MyFirebaseMessagingService">` with intent filter `com.google.firebase.MESSAGING_EVENT`

**`google-services.json`** (new, from Firebase Console)

**`MainActivity` or app startup** (modify)
- On launch, ensure current FCM token is posted to /api/fcm/register
- Check notification permission

**`PollingManager`** (modify)
- During polling cycle, call GET /api/notifications
- If unread notifications exist, show local notifications (deduplicate by notification_id)

### Notification behavior

- Each high-priority todo gets its own notification
- Tap action: open app to TodoFragment with high-priority filter
- Auto-dismiss: when user opens the todo, related notifications are marked read

## Firebase setup (manual, pre-implementation)

1. Create Firebase project in Firebase Console
2. Add Android app with package name `com.example.couplecredit`
3. Download `google-services.json` → place in `app/`
4. Generate service account key (Project Settings → Service Accounts) → save as `server/firebase-service-account.json`
5. The service account key file must NOT be committed to git (add to .gitignore)

## Implementation order

1. Firebase project setup + config files
2. Database migrations (fcm_tokens, notifications)
3. Server: fcmService + notificationService + routes + cron
4. Android: FCM dependency + Service + NotificationHelper + manifest
5. Android: PollingManager polling fallback
6. End-to-end test
