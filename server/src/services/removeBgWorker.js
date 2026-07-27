"use strict";

/**
 * 抠图 worker（worker_threads）。
 *
 * 主线程在 removeBgWorkerPool 里 import 本文件并通过 new Worker(__filename) 启动，
 * 通过 postMessage 传 { imagePath, outputPath }，worker 跑完算法后 postMessage 结果。
 *
 * 把 k-means / flood-fill / 边缘羽化等纯 CPU 同步计算隔离到独立线程，
 * 避免阻塞 Node 主事件循环导致其它接口排队。
 */

const { parentPort, workerData } = require("worker_threads");
const path = require("path");
const sharp = require("sharp");

async function run({ imagePath, outputPath }) {
  const { data, info } = await sharp(imagePath)
    .resize(600, 600, { fit: "inside", withoutEnlargement: true })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  const w = info.width;
  const h = info.height;
  const ch = info.channels;
  const total = w * h;

  function idx(x, y) { return y * w + x; }
  function rgb(px) { return [data[px], data[px + 1], data[px + 2]]; }
  function dist3(a, b) {
    const dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
    return Math.sqrt(dr * dr + dg * dg + db * db);
  }

  // --- Step 1: Collect border pixels, cluster to find dominant background colors ---
  const borderColors = [];
  const seen = new Set();
  for (let x = 0; x < w; x++) {
    for (const row of [0, h - 1]) {
      const c = rgb(idx(x, row) * ch);
      const key = (c[0] >> 4) << 8 | (c[1] >> 4) << 4 | (c[2] >> 4);
      if (!seen.has(key)) { seen.add(key); borderColors.push(c); }
    }
  }
  for (let y = 1; y < h - 1; y++) {
    for (const col of [0, w - 1]) {
      const c = rgb(idx(col, y) * ch);
      const key = (c[0] >> 4) << 8 | (c[1] >> 4) << 4 | (c[2] >> 4);
      if (!seen.has(key)) { seen.add(key); borderColors.push(c); }
    }
  }

  // Simple k-means with k=3 to find dominant background cluster
  const CLUSTER_COUNT = 3;
  let centroids = borderColors.slice(0, CLUSTER_COUNT);
  for (let iter = 0; iter < 10; iter++) {
    const sums = Array.from({ length: CLUSTER_COUNT }, () => [0, 0, 0]);
    const counts = new Array(CLUSTER_COUNT).fill(0);
    for (const c of borderColors) {
      let minD = Infinity, minI = 0;
      for (let k = 0; k < CLUSTER_COUNT; k++) {
        const d = dist3(c, centroids[k]);
        if (d < minD) { minD = d; minI = k; }
      }
      sums[minI][0] += c[0]; sums[minI][1] += c[1]; sums[minI][2] += c[2];
      counts[minI]++;
    }
    for (let k = 0; k < CLUSTER_COUNT; k++) {
      if (counts[k] > 0) {
        centroids[k] = [sums[k][0] / counts[k], sums[k][1] / counts[k], sums[k][2] / counts[k]];
      }
    }
  }

  // Pick the centroid with the most border pixels as background
  const clusterCounts = new Array(CLUSTER_COUNT).fill(0);
  for (const c of borderColors) {
    let minD = Infinity, minI = 0;
    for (let k = 0; k < CLUSTER_COUNT; k++) {
      const d = dist3(c, centroids[k]);
      if (d < minD) { minD = d; minI = k; }
    }
    clusterCounts[minI]++;
  }
  let bgCluster = 0;
  for (let k = 1; k < CLUSTER_COUNT; k++) {
    if (clusterCounts[k] > clusterCounts[bgCluster]) bgCluster = k;
  }
  const bgColor = centroids[bgCluster];

  // --- Step 2: Flood-fill from borders using local color comparison ---
  const bgMask = new Uint8Array(total);
  const visited = new Uint8Array(total);
  const localDist = new Float32Array(total);

  const queue = [];
  for (let x = 0; x < w; x++) {
    queue.push(idx(x, 0));
    queue.push(idx(x, h - 1));
  }
  for (let y = 1; y < h - 1; y++) {
    queue.push(idx(0, y));
    queue.push(idx(w - 1, y));
  }

  const BG_THRESHOLD = 80;
  const EDGE_THRESHOLD = 50;

  let head = 0;
  while (head < queue.length) {
    const pi = queue[head++];
    if (pi < 0 || pi >= total || visited[pi]) continue;
    visited[pi] = 1;

    const byteIdx = pi * ch;
    const pxColor = [data[byteIdx], data[byteIdx + 1], data[byteIdx + 2]];
    const d = dist3(pxColor, bgColor);

    if (d > BG_THRESHOLD) continue;

    bgMask[pi] = 1;
    localDist[pi] = d;

    const x = pi % w;
    const y = (pi - x) / w;
    if (x > 0) queue.push(pi - 1);
    if (x < w - 1) queue.push(pi + 1);
    if (y > 0) queue.push(pi - w);
    if (y < h - 1) queue.push(pi + w);
  }

  // --- Step 3: Post-process — expand background near edges with looser threshold ---
  const expanded = new Uint8Array(total);
  expanded.set(bgMask);

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = idx(x, y);
      if (bgMask[i]) continue;

      let hasBgNeighbor = false;
      if (x > 0 && bgMask[idx(x - 1, y)]) hasBgNeighbor = true;
      if (x < w - 1 && bgMask[idx(x + 1, y)]) hasBgNeighbor = true;
      if (y > 0 && bgMask[idx(x, y - 1)]) hasBgNeighbor = true;
      if (y < h - 1 && bgMask[idx(x, y + 1)]) hasBgNeighbor = true;

      if (hasBgNeighbor) {
        const byteIdx = i * ch;
        const pxColor = [data[byteIdx], data[byteIdx + 1], data[byteIdx + 2]];
        const d = dist3(pxColor, bgColor);
        if (d < EDGE_THRESHOLD) {
          expanded[i] = 1;
        }
      }
    }
  }

  // Second expansion pass for smoother edges
  const expanded2 = new Uint8Array(total);
  expanded2.set(expanded);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = idx(x, y);
      if (expanded[i]) continue;

      let bgCount = 0;
      if (x > 0 && expanded[idx(x - 1, y)]) bgCount++;
      if (x < w - 1 && expanded[idx(x + 1, y)]) bgCount++;
      if (y > 0 && expanded[idx(x, y - 1)]) bgCount++;
      if (y < h - 1 && expanded[idx(x, y + 1)]) bgCount++;
      if (x > 0 && y > 0 && expanded[idx(x - 1, y - 1)]) bgCount++;
      if (x < w - 1 && y > 0 && expanded[idx(x + 1, y - 1)]) bgCount++;
      if (x > 0 && y < h - 1 && expanded[idx(x - 1, y + 1)]) bgCount++;
      if (x < w - 1 && y < h - 1 && expanded[idx(x + 1, y + 1)]) bgCount++;

      if (bgCount >= 5) {
        const byteIdx = i * ch;
        const pxColor = [data[byteIdx], data[byteIdx + 1], data[byteIdx + 2]];
        const d = dist3(pxColor, bgColor);
        if (d < EDGE_THRESHOLD * 0.8) {
          expanded2[i] = 1;
        }
      }
    }
  }

  // --- Step 4: Apply mask with edge feathering ---
  for (let i = 0; i < total; i++) {
    if (expanded2[i]) {
      data[i * ch + 3] = 0;
    }
  }

  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const i = idx(x, y);
      if (expanded2[i]) continue;
      const byteIdx = i * ch;
      if (data[byteIdx + 3] === 0) continue;

      let bgCount = 0;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          if (dx === 0 && dy === 0) continue;
          if (expanded2[idx(x + dx, y + dy)]) bgCount++;
        }
      }

      if (bgCount > 0) {
        const alpha = Math.max(0, Math.round(255 * (1 - bgCount / 8)));
        data[byteIdx + 3] = Math.min(data[byteIdx + 3], alpha);
      }
    }
  }

  await sharp(data, { raw: { width: w, height: h, channels: 4 } })
    .png()
    .toFile(outputPath);
}

// worker 模式：被主线程通过 new Worker(__filename) 启动
if (parentPort && workerData) {
  run(workerData)
    .then(() => parentPort.postMessage({ ok: true }))
    .catch((err) => parentPort.postMessage({ ok: false, error: err.message }));
}

module.exports = { run };
