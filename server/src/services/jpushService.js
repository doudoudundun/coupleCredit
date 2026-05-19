const JPush = require("jpush-sdk");

let client = null;

function initialize() {
  const appKey = process.env.JPUSH_APP_KEY;
  const masterSecret = process.env.JPUSH_MASTER_SECRET;

  if (!appKey || !masterSecret) {
    console.warn("JPush: JPUSH_APP_KEY or JPUSH_MASTER_SECRET not set, JPush disabled");
    return;
  }

  const proxy = process.env.HTTPS_PROXY || null;
  client = JPush.buildClient(appKey, masterSecret, 3, true, 30000, proxy);
  console.log("JPush: Initialized with appKey=" + appKey.substring(0, 8) + "...");
}

async function pushByRegIds(regIds, notification) {
  if (!client || !regIds || regIds.length === 0) return [];

  try {
    await new Promise((resolve, reject) => {
      client.push()
        .setPlatform("android")
        .setAudience(JPush.registration_id(regIds))
        .setNotification(
          JPush.android(notification.body, notification.title)
        )
        .send((err, res) => {
          if (err) {
            console.error("JPush: Send error:", err.message || err);
            reject(err);
          } else {
            console.log("JPush: Sent msg_id=" + res.msg_id + " to " + regIds.length + " devices");
            resolve(res);
          }
        });
    });
  } catch (err) {
    console.error("JPush: push failed:", err.message || err);
  }

  return [];
}

module.exports = { initialize, pushByRegIds };
