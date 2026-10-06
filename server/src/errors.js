class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.status = status;
    this.code = code;
    // 可选结构化上下文（fieldErrors / currentVersion / entityType / entityId 等），
    // 由 sendError 合并进错误 envelope；旧的三参调用不受影响。
    if (details && typeof details === "object") {
      this.details = details;
    }
  }
}

function sendError(res, error, requestId) {
  const status = error.status || 500;
  const code = error.code || "INTERNAL_ERROR";
  const message = error.status ? error.message : "服务器内部错误";
  const payload = {
    code,
    message
  };
  if (error.details && typeof error.details === "object") {
    Object.assign(payload, error.details);
  }
  if (requestId) {
    payload.requestId = requestId;
  }
  res.status(status).json({
    ok: false,
    error: payload
  });
}

module.exports = { ApiError, sendError };
