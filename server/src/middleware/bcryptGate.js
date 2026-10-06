/**
 * bcrypt 并发闸门（CPU 保护）。
 *
 * 背景
 *  登录/注册/改密的 bcrypt 是 CPU 密集型操作：BCRYPT_ROUNDS=10 时单次约 60~80ms。
 *  bcrypt 的 async 版本确实不阻塞事件循环，但它跑在 libuv 线程池里 —— 在 2 vCPU 的
 *  机器上，只要 2~4 个线程池线程同时在算，两个核就被吃满，主线程（事件循环）随之饥饿。
 *
 *  实测（2026-09-23 压测，2 vCPU / 2GB）：
 *    仅 20 并发登录洪水，就把正常业务 /api/bills 从 822 rps 打到 36 rps（-95.6%），
 *    P99 从 50ms 涨到 728ms。即「约 20 个并发登录请求 ≈ 打瘫全站」。
 *
 * 做法
 *  给「同时在跑的 bcrypt 相关请求」设上限，超出的请求排队或快速失败（429），
 *  而不是一起抢 CPU。这样登录洪水只影响登录本身，记账等正常业务不受牵连。
 *  默认上限 1：保证 bcrypt 最多占 1 个核，事件循环永远有 1 个核可用。
 *
 * ⚠️ 2026-09-23 修复（v2）：名额泄漏
 *  v1 把 res.on("finish"/"close") 注册在 acquire() resolve 「之后」。
 *  若客户端在「排队等待名额」期间断开，响应早已关闭 → 监听器注册在关闭之后、
 *  永不触发 → 名额永不归还。而 MAX_CONCURRENT=1 时只要泄漏 1 个，
 *  闸门就彻底卡死：后续所有登录都只能排队到超时后 429，登录功能永久不可用。
 *  复现证据：P1 占名额 → P2 入队 → P2 断开 → 日志出现
 *  `REL-TRANSFER id=1 running=1` 之后再无 `REL-RETURN`。
 *
 *  v2 改为「先注册监听 → 拿到名额后再校验请求是否已结束」的状态机，
 *  并加一层看门狗兜底（持有时间超过 HOLD_LIMIT_MS 视为泄漏，强制回收），
 *  确保任何未知异常都不会让登录功能永久瘫痪。
 *
 * 可用环境变量调整（改 .env 后 pm2 restart --update-env）：
 *  BCRYPT_MAX_CONCURRENT  同时在跑的请求数上限，默认 1（2 vCPU 建议 1~2）
 *  BCRYPT_MAX_QUEUE       允许排队的请求数，默认 16
 *  BCRYPT_MAX_WAIT_MS     排队最长等待毫秒数，默认 4000
 */

function toNonNegativeInt(raw, fallback) {
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) && n >= 0 ? n : fallback;
}

const MAX_CONCURRENT = Math.max(1, toNonNegativeInt(process.env.BCRYPT_MAX_CONCURRENT, 1));
const MAX_QUEUE = toNonNegativeInt(process.env.BCRYPT_MAX_QUEUE, 16);
const MAX_WAIT_MS = toNonNegativeInt(process.env.BCRYPT_MAX_WAIT_MS, 4000);

// 单次 bcrypt 约 60~80ms；持有超过这个时间必然是泄漏（而非真的在算）。
const HOLD_LIMIT_MS = toNonNegativeInt(process.env.BCRYPT_HOLD_LIMIT_MS, 30000);

let running = 0;
let lastChangeAt = Date.now();
const waiters = [];

/**
 * 申请一个执行名额。
 * @returns {Promise<boolean>} true=拿到名额；false=队列已满或等待超时
 */
function acquire() {
  lastChangeAt = Date.now();
  if (running < MAX_CONCURRENT) {
    running += 1;
    return Promise.resolve(true);
  }
  if (waiters.length >= MAX_QUEUE) {
    return Promise.resolve(false);
  }
  return new Promise((resolve) => {
    const entry = { resolve, timer: null };
    entry.timer = setTimeout(() => {
      const idx = waiters.indexOf(entry);
      if (idx >= 0) waiters.splice(idx, 1);
      lastChangeAt = Date.now();
      resolve(false);
    }, MAX_WAIT_MS);
    // 定时器不阻止进程退出
    if (typeof entry.timer.unref === "function") entry.timer.unref();
    waiters.push(entry);
  });
}

/** 释放名额：优先转交给队首等待者（running 不变），否则归还。 */
function release() {
  lastChangeAt = Date.now();
  const next = waiters.shift();
  if (next) {
    clearTimeout(next.timer);
    next.resolve(true);
  } else {
    running = Math.max(0, running - 1);
  }
}

/**
 * 看门狗兜底：正常情况下名额持有时间 = 一次 bcrypt ≈ 60~80ms。
 * 若 running > 0 且长时间毫无变化，说明发生了未预期的泄漏，
 * 强制回收名额（宁可短暂超发，也不能让登录功能永久 429）。
 */
const watchdog = setInterval(() => {
  if (running > 0 && Date.now() - lastChangeAt > HOLD_LIMIT_MS) {
    console.error(
      `[bcryptGate] 检测到名额泄漏（running=${running}, 静默 ${Date.now() - lastChangeAt}ms），已强制回收`
    );
    running = 0;
    lastChangeAt = Date.now();
  }
}, Math.max(5000, Math.floor(HOLD_LIMIT_MS / 3)));
if (typeof watchdog.unref === "function") watchdog.unref();

/**
 * Express 中间件：把 bcrypt 相关端点纳入并发闸门。
 * 用法：router.post("/login", authLimiter, bcryptGate, handler)
 * 注意挂在限流器之后 —— 被限流直接拒掉的请求不应占用名额。
 */
function bcryptGate(_req, res, next) {
  let holding = false; // 是否持有名额
  let settled = false; // 名额是否已归还
  let finished = false; // 请求是否已结束（finish / close）

  const settle = () => {
    if (settled || !holding) return;
    settled = true;
    release();
  };

  // 关键：监听器必须在 acquire() 「之前」注册。
  // 否则客户端在排队期间断开时，响应已关闭，事件永远不会再触发。
  res.on("finish", () => {
    finished = true;
    settle();
  });
  res.on("close", () => {
    finished = true;
    settle();
  });

  acquire()
    .then((granted) => {
      if (!granted) {
        res.set("Retry-After", "1");
        return res.status(429).json({
          ok: false,
          error: { code: "RATE_LIMITED", message: "登录请求过多，请稍后重试" },
        });
      }
      holding = true;
      // 等待名额期间客户端可能已经断开：立即归还，不要进入业务逻辑。
      if (finished || res.writableEnded || res.destroyed) {
        settle();
        return undefined;
      }
      return next();
    })
    .catch((err) => {
      settle();
      next(err);
    });
}

module.exports = {
  bcryptGate,
  stats: () => ({ running, queued: waiters.length, lastChangeAt }),
};
