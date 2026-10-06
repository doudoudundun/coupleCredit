#!/usr/bin/env node
/**
 * 微信消息推送回调 —— 行为级验证（三种加解密模式 × 各种签名组合）
 *
 * 为什么需要它：这个回调的故障方式是**静默的**
 *   · 后台模式选错 → 报文里没有 Encrypt → 审核结果被丢弃 → 头像永远停在「审核中」
 *   · 明文分支漏验签 → 任何人都能 POST 一份 suggest=pass 把违规图片洗成已通过
 *   两者都不会报错、不会写 ERROR 日志，只能靠实测断言。
 *
 * 所以这里起真实 HTTP 服务、挂**真实路由**、复刻生产的 body parser 栈，
 * 用真实签名与真实密文打请求，断言「响应码 + 业务函数是否被调用」。
 *
 * 用法：node scripts/verify-wechat-callback.js
 * 退出码 0 = 全部通过
 *
 * 说明：密钥是**合成值**（43 位纯字母数字，base64 补 `=` 后解出 32 字节），
 *       故意不复用生产密钥，避免把凭据写进仓库。
 */

const express = require("express");
const { computeMsgSignature, computePlainSignature } = require("../src/utils/wechatCrypto");
const { createWechatCallbackRouter } = require("../src/routes/wechatCallback");
const { encryptLikeWechat, buildEncryptedCallback } = require("./lib/wechat-push-selftest");

const TOKEN = "callback-verify-token-0123456789";
const AES_KEY = ("SelfTestKey" + "0123456789".repeat(4)).slice(0, 43); // 43 位纯字母数字
const APPID = "wx0d48cf80f76174c4";

// 与生产日志里微信真实发来的 echostr 形态一致（19 位纯数字）
const PLAIN_ECHOSTR = "4899123940543414013";

let passed = 0;
let failed = 0;
const lines = [];

function ok(label, cond, detail) {
  if (cond) passed += 1;
  else failed += 1;
  lines.push(`${cond ? "✅" : "❌"} ${label}${detail ? `  → ${detail}` : ""}`);
}

function newTimestamp() {
  return String(Math.floor(Date.now() / 1000));
}

/** 构造一条内容安全结果报文。故意同时带 <detail>(risky) 与 <result>(pass)，用于验证取值层级。 */
const RESULT_XML =
  "<xml>" +
  "<ToUserName><![CDATA[gh_test]]></ToUserName>" +
  "<Event><![CDATA[wxa_media_check]]></Event>" +
  "<trace_id><![CDATA[trace-xml-001]]></trace_id>" +
  "<errcode>0</errcode>" +
  "<detail><strategy>content_model</strategy><suggest>risky</suggest><label>20002</label></detail>" +
  "<result><suggest>pass</suggest><label>100</label></result>" +
  "</xml>";

const RESULT_JSON = JSON.stringify({
  ToUserName: "gh_test",
  Event: "wxa_media_check",
  trace_id: "trace-json-001",
  errcode: 0,
  detail: [{ strategy: "content_model", suggest: "risky", label: 20002 }],
  result: { suggest: "pass", label: 100 }
});

const OTHER_EVENT_XML = "<xml><Event><![CDATA[other_event]]></Event></xml>";

async function main() {
  // 复刻生产 index.js 的中间件栈：全局 express.json() 在路由之前
  const calls = [];
  const stubSecurity = {
    applyMediaCheckResult: async (args) => {
      calls.push(args);
      return { handled: true, reason: "STUB", userId: 1 };
    }
  };

  const app = express();
  app.use(express.json());
  app.use(
    "/api/wechat",
    createWechatCallbackRouter({
      config: { wechatMsgToken: TOKEN, wechatEncodingAESKey: AES_KEY },
      contentSecurity: stubSecurity,
      pool: null
    })
  );

  const server = await new Promise((resolve) => {
    const s = app.listen(0, "127.0.0.1", () => resolve(s));
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  const CALLBACK = `${base}/api/wechat/callback`;

  try {
    // ───────────────────────── GET：URL 校验（后台点保存时触发）─────────────────────────
    {
      const ts = newTimestamp();
      const nonce = "nonce-get-1";
      const plain = "hello-wechat-verify";
      const enc = encryptLikeWechat(plain, APPID, AES_KEY);
      const sig = computeMsgSignature({ token: TOKEN, timestamp: ts, nonce, encrypt: enc });
      const r = await fetch(
        `${CALLBACK}?msg_signature=${sig}&timestamp=${ts}&nonce=${nonce}&echostr=${encodeURIComponent(enc)}`
      );
      const body = await r.text();
      ok(
        "GET 安全模式（msg_signature + 密文 echostr）→ 200 且返回明文",
        r.status === 200 && body === plain,
        `status=${r.status} body=${JSON.stringify(body)}`
      );
    }

    {
      const ts = newTimestamp();
      const nonce = "nonce-get-2";
      const sig = computePlainSignature({ token: TOKEN, timestamp: ts, nonce });
      const r = await fetch(
        `${CALLBACK}?signature=${sig}&timestamp=${ts}&nonce=${nonce}&echostr=${PLAIN_ECHOSTR}`
      );
      const body = await r.text();
      ok(
        "GET 明文模式（signature + 明文 echostr）→ 200 且原样回显",
        r.status === 200 && body === PLAIN_ECHOSTR,
        `status=${r.status} body=${JSON.stringify(body)}`
      );
    }

    {
      // 实测存在的第三种形态：签名参数用 signature，但 echostr 是密文
      const ts = newTimestamp();
      const nonce = "nonce-get-3";
      const plain = "secure-echostr-verify";
      const enc = encryptLikeWechat(plain, APPID, AES_KEY);
      const sig = computePlainSignature({ token: TOKEN, timestamp: ts, nonce });
      const r = await fetch(
        `${CALLBACK}?signature=${sig}&timestamp=${ts}&nonce=${nonce}&echostr=${encodeURIComponent(enc)}`
      );
      const body = await r.text();
      ok(
        "GET signature + 密文 echostr → 200 且解密后返回明文",
        r.status === 200 && body === plain,
        `status=${r.status} body=${JSON.stringify(body)}`
      );
    }

    {
      const ts = newTimestamp();
      const r = await fetch(
        `${CALLBACK}?signature=deadbeef&timestamp=${ts}&nonce=n&echostr=${PLAIN_ECHOSTR}`
      );
      ok("GET 签名不合法 → 401", r.status === 401, `status=${r.status}`);
    }

    {
      const ts = newTimestamp();
      const r = await fetch(`${CALLBACK}?timestamp=${ts}&nonce=n&echostr=${PLAIN_ECHOSTR}`);
      ok("GET 无任何签名参数 → 400", r.status === 400, `status=${r.status}`);
    }

    {
      const ts = newTimestamp();
      const sig = computePlainSignature({ token: TOKEN, timestamp: ts, nonce: "n" });
      const r = await fetch(`${CALLBACK}?signature=${sig}&timestamp=${ts}&nonce=n`);
      ok("GET 缺 echostr → 400", r.status === 400, `status=${r.status}`);
    }

    {
      const ts = newTimestamp();
      const enc = encryptLikeWechat("x", APPID, AES_KEY);
      const r = await fetch(
        `${CALLBACK}?msg_signature=deadbeef&timestamp=${ts}&nonce=n&echostr=${encodeURIComponent(enc)}`
      );
      ok("GET msg_signature 不合法 → 401", r.status === 401, `status=${r.status}`);
    }

    // ───────────────────────── POST：审核结果回调 ─────────────────────────
    // 9) 安全模式 / 兼容模式：加密报文 + 正确的 msg_signature
    {
      const before = calls.length;
      const ts = newTimestamp();
      const nonce = "nonce-post-1";
      const built = buildEncryptedCallback({
        token: TOKEN,
        encodingAESKey: AES_KEY,
        appid: APPID,
        message: RESULT_XML,
        timestamp: ts,
        nonce,
        wrap: "xml"
      });
      const r = await fetch(
        `${CALLBACK}?msg_signature=${built.signature}&timestamp=${ts}&nonce=${nonce}`,
        { method: "POST", headers: { "Content-Type": "text/xml" }, body: built.body }
      );
      const body = await r.text();
      const call = calls[before];
      ok(
        "POST 安全模式（加密 XML）→ 返回 success 且业务被调用",
        r.status === 200 && body === "success" && calls.length === before + 1,
        `status=${r.status} body=${JSON.stringify(body)} 调用数=${calls.length - before}`
      );
      ok(
        "POST 解析正确取到 <result> 层（suggest=pass，而不是 <detail> 的 risky）",
        call && call.suggest === "pass" && call.label === 100 && call.traceId === "trace-xml-001",
        call ? `suggest=${call.suggest} label=${call.label} traceId=${call.traceId}` : "业务未被调用"
      );
    }

    // 10) 加密 JSON（后台「数据格式」选 JSON 时）
    {
      const before = calls.length;
      const ts = newTimestamp();
      const nonce = "nonce-post-2";
      const built = buildEncryptedCallback({
        token: TOKEN,
        encodingAESKey: AES_KEY,
        appid: APPID,
        message: RESULT_JSON,
        timestamp: ts,
        nonce,
        wrap: "json"
      });
      const r = await fetch(
        `${CALLBACK}?msg_signature=${built.signature}&timestamp=${ts}&nonce=${nonce}`,
        { method: "POST", headers: { "Content-Type": "application/json" }, body: built.body }
      );
      const body = await r.text();
      const call = calls[before];
      ok(
        "POST 安全模式（加密 JSON，经全局 express.json 后仍可解析）→ success 且业务被调用",
        r.status === 200 && body === "success" && calls.length === before + 1,
        `status=${r.status} body=${JSON.stringify(body)} 调用数=${calls.length - before}`
      );
      ok(
        "POST JSON 报文解析正确（suggest=pass / label=100）",
        call && call.suggest === "pass" && call.label === 100,
        call ? `suggest=${call.suggest} label=${call.label}` : "业务未被调用"
      );
    }

    // 11) 明文模式：明文 XML + 正确的 signature
    {
      const before = calls.length;
      const ts = newTimestamp();
      const nonce = "nonce-post-3";
      const sig = computePlainSignature({ token: TOKEN, timestamp: ts, nonce });
      const r = await fetch(`${CALLBACK}?signature=${sig}&timestamp=${ts}&nonce=${nonce}`, {
        method: "POST",
        headers: { "Content-Type": "text/xml" },
        body: RESULT_XML
      });
      const body = await r.text();
      const call = calls[before];
      ok(
        "POST 明文模式（明文 XML + 正确 signature）→ success 且业务被调用",
        r.status === 200 && body === "success" && calls.length === before + 1,
        `status=${r.status} body=${JSON.stringify(body)} 调用数=${calls.length - before}`
      );
      ok(
        "POST 明文报文解析正确（suggest=pass / label=100）",
        call && call.suggest === "pass" && call.label === 100 && call.traceId === "trace-xml-001",
        call ? `suggest=${call.suggest} label=${call.label}` : "业务未被调用"
      );
    }

    // 12) 明文 JSON（application/json，会先被全局 json parser 解析）
    {
      const before = calls.length;
      const ts = newTimestamp();
      const nonce = "nonce-post-4";
      const sig = computePlainSignature({ token: TOKEN, timestamp: ts, nonce });
      const r = await fetch(`${CALLBACK}?signature=${sig}&timestamp=${ts}&nonce=${nonce}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: RESULT_JSON
      });
      const body = await r.text();
      ok(
        "POST 明文模式（明文 JSON）→ success 且业务被调用",
        r.status === 200 && body === "success" && calls.length === before + 1,
        `status=${r.status} body=${JSON.stringify(body)} 调用数=${calls.length - before}`
      );
    }

    // 13) ⚠️ 安全关键：明文报文不带签名必须拒绝，且绝不能触达业务
    //     注意状态码语义：凭据**缺失** → 400，凭据**不合法** → 401（见下一条）
    {
      const before = calls.length;
      const ts = newTimestamp();
      const r = await fetch(`${CALLBACK}?timestamp=${ts}&nonce=nonce-post-5`, {
        method: "POST",
        headers: { "Content-Type": "text/xml" },
        body: RESULT_XML
      });
      ok(
        "POST 明文报文无 signature → 400 且业务未被调用（防伪造 pass）",
        r.status === 400 && calls.length === before,
        `status=${r.status} 调用数=${calls.length - before}`
      );
    }

    // 14) ⚠️ 安全关键：明文报文签名错误必须拒绝
    {
      const before = calls.length;
      const ts = newTimestamp();
      const r = await fetch(`${CALLBACK}?signature=deadbeef&timestamp=${ts}&nonce=nonce-post-6`, {
        method: "POST",
        headers: { "Content-Type": "text/xml" },
        body: RESULT_XML
      });
      ok(
        "POST 明文报文 signature 错误 → 401 且业务未被调用（防伪造 pass）",
        r.status === 401 && calls.length === before,
        `status=${r.status} 调用数=${calls.length - before}`
      );
    }

    // 15) ⚠️ 安全关键：加密报文签名错误必须拒绝
    {
      const before = calls.length;
      const ts = newTimestamp();
      const nonce = "nonce-post-7";
      const built = buildEncryptedCallback({
        token: TOKEN,
        encodingAESKey: AES_KEY,
        appid: APPID,
        message: RESULT_XML,
        timestamp: ts,
        nonce,
        wrap: "xml"
      });
      const r = await fetch(`${CALLBACK}?msg_signature=deadbeef&timestamp=${ts}&nonce=${nonce}`, {
        method: "POST",
        headers: { "Content-Type": "text/xml" },
        body: built.body
      });
      ok(
        "POST 加密报文 msg_signature 错误 → 401 且业务未被调用",
        r.status === 401 && calls.length === before,
        `status=${r.status} 调用数=${calls.length - before}`
      );
    }

    // 16) 非内容安全事件：确认接收但不触达业务
    {
      const before = calls.length;
      const ts = newTimestamp();
      const nonce = "nonce-post-8";
      const built = buildEncryptedCallback({
        token: TOKEN,
        encodingAESKey: AES_KEY,
        appid: APPID,
        message: OTHER_EVENT_XML,
        timestamp: ts,
        nonce,
        wrap: "xml"
      });
      const r = await fetch(
        `${CALLBACK}?msg_signature=${built.signature}&timestamp=${ts}&nonce=${nonce}`,
        { method: "POST", headers: { "Content-Type": "text/xml" }, body: built.body }
      );
      const body = await r.text();
      ok(
        "POST 非 wxa_media_check 事件 → 返回 success 且业务未被调用",
        r.status === 200 && body === "success" && calls.length === before,
        `status=${r.status} 调用数=${calls.length - before}`
      );
    }
  } finally {
    server.close();
  }

  console.log("微信消息推送回调 —— 行为级验证\n");
  for (const line of lines) console.log("  " + line);
  console.log(`\n通过 ${passed}   失败 ${failed}`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("验证脚本自身异常:", e);
  process.exit(1);
});
