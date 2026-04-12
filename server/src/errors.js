class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function sendError(res, error) {
  const status = error.status || 500;
  const code = error.code || "INTERNAL_ERROR";
  const message = error.status ? error.message : "服务器内部错误";
  res.status(status).json({
    ok: false,
    error: {
      code,
      message
    }
  });
}

module.exports = { ApiError, sendError };
