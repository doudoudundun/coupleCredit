const required = ["DB_PASSWORD", "INVITE_CODE"];

function readConfig() {
  for (const key of required) {
    if (!process.env[key]) {
      throw new Error(`Missing required environment variable: ${key}`);
    }
  }

  return {
    host: process.env.HOST || "0.0.0.0",
    port: Number(process.env.PORT || 8080),
    dbHost: process.env.DB_HOST || "127.0.0.1",
    dbPort: Number(process.env.DB_PORT || 3306),
    dbName: process.env.DB_NAME || "couple_credit_private",
    dbUser: process.env.DB_USER || "couple_app",
    dbPassword: process.env.DB_PASSWORD,
    inviteCode: process.env.INVITE_CODE,
    bcryptRounds: Number(process.env.BCRYPT_ROUNDS || 10)
  };
}

module.exports = { readConfig };
