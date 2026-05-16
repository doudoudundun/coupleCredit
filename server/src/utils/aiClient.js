const { ApiError } = require("../errors");

function buildAiProviderConfig(envPrefix, defaultBaseUrl, defaultModel) {
  return {
    apiKey: process.env[`${envPrefix}_API_KEY`],
    baseUrl: process.env[`${envPrefix}_BASE_URL`] || defaultBaseUrl || null,
    model: process.env[`${envPrefix}_MODEL`] || defaultModel
  };
}

function resolveChatApiUrl(baseUrl) {
  const trimmed = String(baseUrl || "").replace(/\/$/, "");
  if (trimmed.endsWith("/chat/completions")) return trimmed;
  if (trimmed.endsWith("/v1")) return `${trimmed}/chat/completions`;
  return `${trimmed}/v1/chat/completions`;
}

function callAiApi(baseUrl, apiKey, payload, timeout = 120000) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeout);

  return fetch(resolveChatApiUrl(baseUrl), {
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
      const preview = data.substring(0, 300).replace(/\s+/g, " ");
      throw new ApiError(502, "AI_API_ERROR", `AI API HTTP ${res.status}: ${preview}`);
    }
    try {
      return JSON.parse(data);
    } catch (_e) {
      const preview = data.substring(0, 200).replace(/\s+/g, " ");
      throw new ApiError(502, "AI_API_ERROR", `AI API response parse error: ${preview}`);
    }
  }).catch((error) => {
    if (error && error.name === "AbortError") {
      throw new ApiError(504, "AI_API_TIMEOUT", "AI API request timeout");
    }
    throw error instanceof ApiError
      ? error
      : new ApiError(502, "AI_API_ERROR", `AI API request failed: ${error.message}`);
  }).finally(() => {
    clearTimeout(timeoutId);
  });
}

async function callAiWithFallback(payload, primaryProvider, fallbackProvider) {
  const primaryPayload = { ...payload, model: primaryProvider.model };
  try {
    return await callAiApi(primaryProvider.baseUrl, primaryProvider.apiKey, primaryPayload);
  } catch (primaryError) {
    if (!fallbackProvider || !fallbackProvider.apiKey || !fallbackProvider.baseUrl) {
      throw primaryError;
    }
    console.warn(`Primary AI failed (${primaryError.message}), trying fallback`);
    const fallbackPayload = { ...payload, model: fallbackProvider.model };
    return callAiApi(fallbackProvider.baseUrl, fallbackProvider.apiKey, fallbackPayload);
  }
}

function extractAiResponseText(aiResult) {
  const content = aiResult?.choices?.[0]?.message?.content || "";
  if (typeof content === "string") return content;
  if (Array.isArray(content)) return content.map(extractContentPartText).filter(Boolean).join("\n");
  if (content && typeof content === "object") {
    if (typeof content.text === "string") return content.text;
    return JSON.stringify(content);
  }
  return "";
}

function extractContentPartText(part) {
  if (typeof part === "string") return part;
  if (!part || typeof part !== "object") return "";
  if (typeof part.text === "string") return part.text;
  if (Array.isArray(part.content)) return part.content.map(extractContentPartText).filter(Boolean).join("\n");
  return "";
}

module.exports = {
  buildAiProviderConfig,
  resolveChatApiUrl,
  callAiApi,
  callAiWithFallback,
  extractAiResponseText,
  extractContentPartText
};
