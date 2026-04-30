const mysql = require("mysql2/promise");

function createPool(config) {
  const pool = mysql.createPool({
    host: config.dbHost,
    port: config.dbPort,
    database: config.dbName,
    user: config.dbUser,
    password: config.dbPassword,
    waitForConnections: true,
    connectionLimit: 20,
    queueLimit: 0,
    enableKeepAlive: true,
    keepAliveInitialDelay: 30000,
    idleTimeout: 300000,
    maxIdle: 10,
    charset: "utf8mb4",
    timezone: "+08:00"
  });

  warmPool(pool, 3);
  return pool;
}

async function warmPool(pool, count) {
  for (let i = 0; i < count; i++) {
    try {
      const conn = await pool.getConnection();
      await conn.execute("SELECT 1");
      conn.release();
    } catch (_) {}
  }
}

module.exports = { createPool };
