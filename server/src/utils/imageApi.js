const { ApiError } = require("../errors");

function resolveImageApiUrl(baseUrl) {
  const trimmed = String(baseUrl || "").replace(/\/$/, "");
  if (trimmed.endsWith("/images/generations")) return trimmed;
  if (trimmed.endsWith("/v1")) return `${trimmed}/images/generations`;
  return `${trimmed}/v1/images/generations`;
}

function callImageApi(baseUrl, apiKey, payload, timeout = 60000) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeout);

  return fetch(resolveImageApiUrl(baseUrl), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${apiKey}`
    },
    body: JSON.stringify(payload),
    signal: controller.signal
  }).then(async (res) => {
    const data = await res.text();
    if (!res.ok) {
      throw new Error(`AI Image API HTTP ${res.status}: ${data.substring(0, 200)}`);
    }
    try {
      return JSON.parse(data);
    } catch (e) {
      throw new Error(`AI Image API response parse error: ${data.substring(0, 200)}`);
    }
  }).catch((error) => {
    if (error && error.name === "AbortError") {
      throw new Error("AI Image API timeout");
    }
    throw error;
  }).finally(() => {
    clearTimeout(timeoutId);
  });
}

module.exports = { resolveImageApiUrl, callImageApi };
