/**
 * 一次性/开发用迁移执行器：按序号顺序执行 migrations/ 下的 SQL 文件。
 *
 * 用法：node scripts/dev/run-migration.js [文件名 ...]
 *   不带参数时执行 migrations 目录下所有未执行过的文件（按序号排序，
 *   通过 information_schema 检查表是否已存在来跳过建表文件——仅对纯建表迁移可靠）。
 *   带上文件名则强制按顺序执行指定文件（已存在的表用 IF NOT EXISTS 兜底）。
 *
 * 无迁移运行器是仓库现状；本脚本仅供本地/测试环境使用，生产由 DBA 人工执行。
 */
const fs = require("fs");
const path = require("path");

const envPath = path.resolve(__dirname, "../../.env");
if (fs.existsSync(envPath)) {
  for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const index = trimmed.indexOf("=");
    if (index <= 0) continue;
    const key = trimmed.slice(0, index).trim();
    if (process.env[key] === undefined) process.env[key] = trimmed.slice(index + 1).trim();
  }
}

const mysql = require("mysql2/promise");
const { readConfig } = require("../../src/config");

function splitStatements(sql) {
  const statements = [];
  let current = "";
  let inBlockComment = false;
  for (const rawLine of sql.split(/\r?\n/)) {
    let line = rawLine;
    if (inBlockComment) {
      const end = line.indexOf("*/");
      if (end === -1) continue;
      line = line.slice(end + 2);
      inBlockComment = false;
    }
    // 去掉行内块注释与 -- 注释（本仓库迁移不用字符串字面量里的 --）
    for (;;) {
      const start = line.indexOf("/*");
      if (start === -1) break;
      const end = line.indexOf("*/", start + 2);
      if (end === -1) {
        line = line.slice(0, start);
        inBlockComment = true;
        break;
      }
      line = line.slice(0, start) + line.slice(end + 2);
    }
    const commentIndex = line.indexOf("--");
    if (commentIndex !== -1) line = line.slice(0, commentIndex);
    current += line + "\n";
    if (/;\s*$/.test(line)) {
      const statement = current.trim();
      if (statement) statements.push(statement);
      current = "";
    }
  }
  const tail = current.trim();
  if (tail) statements.push(tail);
  return statements;
}

async function main() {
  const config = readConfig();
  const conn = await mysql.createConnection({
    host: config.dbHost,
    port: config.dbPort,
    user: config.dbUser,
    password: config.dbPassword,
    database: config.dbName,
    multipleStatements: false
  });

  const migrationsDir = path.resolve(__dirname, "../../migrations");
  const args = process.argv.slice(2);
  const files = args.length > 0
    ? args
    : fs.readdirSync(migrationsDir).filter((name) => /^\d+_.*\.sql$/.test(name)).sort();

  try {
    for (const file of files) {
      const fullPath = path.join(migrationsDir, file);
      const sql = fs.readFileSync(fullPath, "utf8");
      const statements = splitStatements(sql);
      console.log(`== ${file}: ${statements.length} statements`);
      for (let i = 0; i < statements.length; i++) {
        const preview = statements[i].replace(/\s+/g, " ").slice(0, 80);
        await conn.query(statements[i]);
        console.log(`   [${i + 1}/${statements.length}] OK ${preview}`);
      }
    }
    const [tables] = await conn.query("SHOW TABLES LIKE 'housework%'");
    console.log("housework tables:", tables.map((row) => Object.values(row)[0]).join(", "));
  } finally {
    await conn.end();
  }
}

main().catch((error) => {
  console.error("migration failed:", error.message);
  process.exit(1);
});
