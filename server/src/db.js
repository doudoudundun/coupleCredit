const mysql = require("mysql2/promise");

function createPool(config) {
  const pool = mysql.createPool({
    host: config.dbHost,
    port: config.dbPort,
    database: config.dbName,
    user: config.dbUser,
    password: config.dbPassword,
    waitForConnections: true,
    connectionLimit: 30,
    queueLimit: 0,
    enableKeepAlive: true,
    keepAliveInitialDelay: 10000,
    idleTimeout: 300000,
    maxIdle: 15,
    charset: "utf8mb4",
    timezone: "+08:00"
  });

  warmPool(pool, 5);
  return pool;
}

async function warmPool(pool, count) {
  const tasks = [];
  for (let i = 0; i < count; i++) {
    tasks.push(
      pool.getConnection().then(async (conn) => {
        await conn.execute("SELECT 1");
        conn.release();
      }).catch(() => {})
    );
  }
  await Promise.all(tasks);
}

module.exports = { createPool };
