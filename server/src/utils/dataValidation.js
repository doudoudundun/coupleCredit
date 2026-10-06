const { ApiError } = require("../errors");

function parseIncomeType(value) {
  if (!Number.isInteger(value) || (value !== 0 && value !== 1)) {
    throw new ApiError(400, "INVALID_REQUEST", "收支类型必须是 0（支出）或 1（收入）");
  }
  return value;
}

module.exports = { parseIncomeType };
