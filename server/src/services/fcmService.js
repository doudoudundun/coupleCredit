const path = require("path");
const https = require("https");
const http = require("http");
const crypto = require("crypto");
const { HttpsProxyAgent } = require("https-proxy-agent");

let initialized = false;
let serviceAccount = null;
let projectId = null;
let accessToken = null;
let tokenExpiry = 0;
const proxyAgent = getProxyAgent();

function getProxyAgent() {
  const proxyUrl = process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY || process.env.http_proxy;
  if (!proxyUrl) return undefined;
  console.log("FCM: Using proxy", proxyUrl);
  return new HttpsProxyAgent(proxyUrl);
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
  serviceAccount = require(serviceAccountPath);
  projectId = serviceAccount.project_id;
  initialized = true;
  console.log("FCM: Initialized" + (proxyAgent ? " (with proxy)" : "") + ", project=" + projectId);
}

function base64url(buf) {
  return buf.toString("base64").replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

async function getAccessToken() {
  if (accessToken && Date.now() < tokenExpiry) return accessToken;

  const now = Math.floor(Date.now() / 1000);
  const header = base64url(Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const payload = base64url(Buffer.from(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  })));

  const sign = crypto.createSign("RSA-SHA256");
  sign.update(header + "." + payload);
  const signature = base64url(sign.sign(serviceAccount.private_key));

  const jwt = header + "." + payload + "." + signature;

  const body = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + encodeURIComponent(jwt);

  const result = await new Promise((resolve, reject) => {
    const opts = {
      hostname: "oauth2.googleapis.com",
      path: "/token",
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "Content-Length": Buffer.byteLength(body) },
    };
    if (proxyAgent) opts.agent = proxyAgent;

    const req = https.request(opts, (res) => {
      let data = "";
      res.on("data", chunk => data += chunk);
      res.on("end", () => resolve({ status: res.statusCode, data }));
    });
    req.on("error", reject);
    req.setTimeout(10000, () => req.destroy(new Error("OAuth timeout")));
    req.write(body);
    req.end();
  });

  if (result.status !== 200) {
    throw new Error("OAuth failed: " + result.status + " " + result.data.substring(0, 200));
  }

  const json = JSON.parse(result.data);
  accessToken = json.access_token;
  tokenExpiry = Date.now() + (json.expires_in - 60) * 1000;
  console.log("FCM: Access token obtained");
  return accessToken;
}

async function sendMulticast(tokens, notification) {
  if (!initialized || !tokens || tokens.length === 0) return [];
  const invalidTokens = [];
  let successCount = 0;

  for (const token of tokens) {
    try {
      const bearer = await getAccessToken();
      const fcmBody = JSON.stringify({
        message: {
          token,
          notification: { title: notification.title, body: notification.body },
          android: { priority: "high" }
        }
      });

      const result = await new Promise((resolve, reject) => {
        const opts = {
          hostname: "fcm.googleapis.com",
          path: "/v1/projects/" + projectId + "/messages:send",
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": "Bearer " + bearer,
          },
        };
        if (proxyAgent) opts.agent = proxyAgent;

        const req = https.request(opts, (res) => {
          let data = "";
          res.on("data", chunk => data += chunk);
          res.on("end", () => resolve({ status: res.statusCode, body: data }));
        });
        req.on("error", reject);
        req.setTimeout(10000, () => req.destroy(new Error("FCM timeout")));
        req.write(fcmBody);
        req.end();
      });

      if (result.status === 200) {
        successCount++;
      } else if (result.status === 404 || result.body.includes("UNREGISTERED")) {
        invalidTokens.push(token);
        console.warn("FCM: Token unregistered:", token.substring(0, 20) + "...");
      } else {
        console.error("FCM: Send failed status=" + result.status + " token=" + token.substring(0, 20) + "... body=" + result.body.substring(0, 500));
      }
    } catch (err) {
      console.error("FCM: Send error:", err.message);
    }
  }

  console.log("FCM: Sent", successCount + "/" + tokens.length, "successfully");
  return invalidTokens;
}

module.exports = { initializeApp, sendMulticast };
