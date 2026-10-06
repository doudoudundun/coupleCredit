const path = require("path");
const { HttpsProxyAgent } = require("https-proxy-agent");

let initialized = false;
let admin;

function getAdmin() {
  if (!admin) admin = require("firebase-admin");
  return admin;
}

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
  const firebaseAdmin = getAdmin();
  const appOptions = { credential: firebaseAdmin.credential.cert(serviceAccount) };
  const proxy = process.env.HTTPS_PROXY || process.env.HTTP_PROXY;
  if (proxy) {
    appOptions.httpAgent = new HttpsProxyAgent(proxy);
    console.log("FCM: Using proxy " + proxy);
  }
  firebaseAdmin.initializeApp(appOptions);
  initialized = true;
  console.log("FCM: Initialized with firebase-admin, project=" + serviceAccount.project_id);
}

function chunkTokens(tokens, size = 500) {
  const batches = [];
  for (let index = 0; index < tokens.length; index += size) {
    batches.push(tokens.slice(index, index + size));
  }
  return batches;
}

function isInvalidTokenError(error) {
  const code = error && error.code ? error.code : "";
  return code.includes("registration-token-not-registered")
    || code.includes("UNREGISTERED")
    || String(error && error.message || "").includes("UNREGISTERED");
}

async function sendMulticast(tokens, notification) {
  if (!initialized || !tokens || tokens.length === 0) return [];
  const invalidTokens = [];
  let successCount = 0;
  const messaging = getAdmin().messaging();

  for (const batch of chunkTokens(tokens)) {
    const multicastMessage = {
      tokens: batch,
      notification: { title: notification.title, body: notification.body },
      android: { priority: "high" }
    };

    if (typeof messaging.sendEachForMulticast === "function") {
      const response = await messaging.sendEachForMulticast(multicastMessage);
      response.responses.forEach((sendResponse, index) => {
        if (sendResponse.success) {
          successCount++;
          return;
        }
        const token = batch[index];
        if (isInvalidTokenError(sendResponse.error)) {
          invalidTokens.push(token);
          console.warn("FCM: Token unregistered:", token.substring(0, 20) + "...");
        } else {
          console.error("FCM: Send failed token=" + token.substring(0, 20) + "... error:", sendResponse.error?.message);
        }
      });
      continue;
    }

    // 兼容旧版 firebase-admin：没有批量 API 时保留单 token 兜底。
    for (const token of batch) {
      try {
        await messaging.send({
          token,
          notification: { title: notification.title, body: notification.body },
          android: { priority: "high" }
        });
        successCount++;
      } catch (err) {
        if (isInvalidTokenError(err)) {
          invalidTokens.push(token);
          console.warn("FCM: Token unregistered:", token.substring(0, 20) + "...");
        } else {
          console.error("FCM: Send failed token=" + token.substring(0, 20) + "... error:", err.message);
        }
      }
    }
  }

  console.log("FCM: Sent", successCount + "/" + tokens.length, "successfully");
  return invalidTokens;
}

module.exports = { initializeApp, sendMulticast, chunkTokens };
