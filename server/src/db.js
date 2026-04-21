const mysql = require("mysql2/promise");

function createPool(config) {
  return mysql.createPool({
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
    idleTimeout: 60000,
    maxIdle: 10,
    charset: "utf8mb4",
    timezone: "+08:00"
  });
}

module.exports = { createPool };
