const path = require("path");
const admin = require("firebase-admin");

let initialized = false;

function initializeApp() {
  if (initialized) return;
  const serviceAccountPath = path.resolve(__dirname, "../../firebase-service-account.json");
  try {
    require.resolve(serviceAccountPath);
  } catch {
    console.warn("FCM: firebase-service-account.json not found, push notifications disabled");
    return;
  }
  const serviceAccount = require(serviceAccountPath);
  admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
  initialized = true;
  console.log("FCM: Initialized with firebase-admin, project=" + serviceAccount.project_id);
}

async function sendMulticast(tokens, notification) {
  if (!initialized || !tokens || tokens.length === 0) return [];
  const invalidTokens = [];
  let successCount = 0;

  for (const token of tokens) {
    try {
      await admin.messaging().send({
        token,
        notification: { title: notification.title, body: notification.body },
        android: { priority: "high" }
      });
      successCount++;
    } catch (err) {
      const code = err.code || "";
      if (code.includes("registration-token-not-registered") || code.includes("UNREGISTERED") || String(err.message || "").includes("UNREGISTERED")) {
        invalidTokens.push(token);
        console.warn("FCM: Token unregistered:", token.substring(0, 20) + "...");
      } else {
        console.error("FCM: Send failed token=" + token.substring(0, 20) + "... error:", err.message);
      }
    }
  }

  console.log("FCM: Sent", successCount + "/" + tokens.length, "successfully");
  return invalidTokens;
}

module.exports = { initializeApp, sendMulticast };
