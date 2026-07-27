"use strict";

/**
 * 抠图 worker 线程池（带并发上限的 semaphore）。
 *
 * 用法：
 *   const { removeBackground } = require("./removeBgWorkerPool");
 *   await removeBackground(imagePath, outputPath);
 *
 * 内部用 worker_threads 跑 removeBgWorker.js，最多 MAX_CONCURRENT 个任务并发，
 * 超出的排队等待。避免多个抠图请求同时占满 CPU。
 */

const { Worker } = require("worker_threads");
const path = require("path");

const WORKER_FILE = path.resolve(__dirname, "removeBgWorker.js");
const MAX_CONCURRENT = 2;

let active = 0;
const queue = [];

function schedule() {
  while (active < MAX_CONCURRENT && queue.length > 0) {
    const { imagePath, outputPath, resolve, reject } = queue.shift();
    active++;
    const worker = new Worker(WORKER_FILE, { workerData: { imagePath, outputPath } });
    worker.on("message", (msg) => {
      active--;
      worker.terminate().catch(() => {});
      schedule();
      if (msg && msg.ok) {
        resolve();
      } else {
        reject(new Error((msg && msg.error) || "抠图 worker 执行失败"));
      }
    });
    worker.on("error", (err) => {
      active--;
      worker.terminate().catch(() => {});
      schedule();
      reject(err);
    });
    worker.on("exit", (code) => {
      if (code !== 0 && active > 0) {
        // 非正常退出且未被 message/error 处理
        active--;
        schedule();
      }
    });
  }
}

/**
 * 在 worker 线程中执行抠图。超过 MAX_CONCURRENT 时排队。
 * @returns {Promise<void>}
 */
function removeBackground(imagePath, outputPath) {
  return new Promise((resolve, reject) => {
    queue.push({ imagePath, outputPath, resolve, reject });
    schedule();
  });
}

module.exports = { removeBackground, MAX_CONCURRENT };
