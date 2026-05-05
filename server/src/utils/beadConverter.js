/**
 * beadConverter.js
 * 将普通图片转换为拼豆方格图
 */

const sharp = require("sharp");
const { BEAD_COLORS } = require("../constants/beadColors");

const UNMATCHED = { colorCode: "???", hexColor: "#DDDDDD", r: 221, g: 221, b: 221, lab: [85, 0, 0] };
const MAX_MATCH_DELTA_E_SQ = 2500; // Delta-E > 50 视为无匹配

// ==================== 工具函数 ====================

function rgbToLab(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  r = r > 0.04045 ? Math.pow((r + 0.055) / 1.055, 2.4) : r / 12.92;
  g = g > 0.04045 ? Math.pow((g + 0.055) / 1.055, 2.4) : g / 12.92;
  b = b > 0.04045 ? Math.pow((b + 0.055) / 1.055, 2.4) : b / 12.92;
  let x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
  let y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / 1.00000;
  let z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
  x = x > 0.008856 ? Math.pow(x, 1 / 3) : 7.787 * x + 16 / 116;
  y = y > 0.008856 ? Math.pow(y, 1 / 3) : 7.787 * y + 16 / 116;
  z = z > 0.008856 ? Math.pow(z, 1 / 3) : 7.787 * z + 16 / 116;
  return [(116 * y) - 16, 500 * (x - y), 200 * (y - z)];
}

function labDist(c1, c2) {
  const dL = c1[0] - c2[0];
  const da = c1[1] - c2[1];
  const db = c1[2] - c2[2];
  return dL * dL + da * da + db * db;
}

// ==================== 颜色分组（Color Pooling） ====================

const PALETTE_LAB = BEAD_COLORS.map(({ colorCode, hexColor, isTransparent }) => {
  const hex = hexColor.replace("#", "");
  const r = parseInt(hex.slice(0, 2), 16);
  const g = parseInt(hex.slice(2, 4), 16);
  const b = parseInt(hex.slice(4, 6), 16);
  return { colorCode, hexColor, r, g, b, lab: rgbToLab(r, g, b), isTransparent: !!isTransparent };
});

const TRANSPARENT_COLORS = PALETTE_LAB.filter(c => c.isTransparent);

function buildColorGroups() {
  const used = new Set();
  const groups = [];
  for (const color of PALETTE_LAB) {
    if (used.has(color.colorCode)) continue;
    const group = [color];
    used.add(color.colorCode);
    for (const other of PALETTE_LAB) {
      if (used.has(other.colorCode)) continue;
      for (const member of group) {
        if (labDist(member.lab, other.lab) < 100) {
          group.push(other);
          used.add(other.colorCode);
          break;
        }
      }
    }
    group.sort((a, b) => a.lab[0] - b.lab[0]);
    const rep = group[Math.floor(group.length / 2)];
    groups.push({ id: groups.length, rep, colors: group });
  }
  return groups;
}

const COLOR_GROUPS = buildColorGroups();

const CODE_TO_GROUP = {};
for (const g of COLOR_GROUPS) {
  for (const c of g.colors) {
    CODE_TO_GROUP[c.colorCode] = g.id;
  }
}
CODE_TO_GROUP["???"] = -1;

// ==================== 匹配函数 ====================

function findClosestGroup(r, g, b) {
  const lab = rgbToLab(r, g, b);
  let bestId = COLOR_GROUPS[0].id;
  let bestDist = Infinity;
  for (const group of COLOR_GROUPS) {
    const d = labDist(lab, group.rep.lab);
    if (d < bestDist) {
      bestDist = d;
      bestId = group.id;
    }
  }
  return COLOR_GROUPS[bestId];
}

function findBestInGroup(group, r, g, b) {
  const lab = rgbToLab(r, g, b);
  let best = group.colors[0];
  let bestDist = Infinity;
  for (const c of group.colors) {
    const d = labDist(lab, c.lab);
    if (d < bestDist) {
      bestDist = d;
      best = c;
    }
  }
  if (bestDist > MAX_MATCH_DELTA_E_SQ) return null;
  return best;
}

function matchPixel(r, g, b, a) {
  if (a < 50) return UNMATCHED;
  if (a < 200) {
    const alpha = a / 255;
    r = Math.round(r * alpha + 255 * (1 - alpha));
    g = Math.round(g * alpha + 255 * (1 - alpha));
    b = Math.round(b * alpha + 255 * (1 - alpha));
  }
  const group = findClosestGroup(r, g, b);
  return findBestInGroup(group, r, g, b) || UNMATCHED;
}

// ==================== 邻域平滑 ====================

function smoothByNeighbors(grid, rows, cols, passCount) {
  for (let pass = 0; pass < passCount; pass++) {
    const newGrid = [];
    for (let r = 0; r < rows; r++) {
      newGrid[r] = [];
      for (let c = 0; c < cols; c++) {
        const self = grid[r][c];
        const selfGid = CODE_TO_GROUP[self.colorCode];
        const cnt = {};
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            if (dr === 0 && dc === 0) continue;
            const nr = r + dr, nc = c + dc;
            if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;
            const gid = CODE_TO_GROUP[grid[nr][nc].colorCode];
            cnt[gid] = (cnt[gid] || 0) + 1;
          }
        }
        let domGid = selfGid, domCnt = 0;
        for (const [gidStr, count] of Object.entries(cnt)) {
          if (count > domCnt) { domCnt = count; domGid = parseInt(gidStr); }
        }
        if (domCnt >= 4 && domGid !== selfGid) {
          let adopted = null;
          for (let dr = -1; dr <= 1; dr++) {
            for (let dc = -1; dc <= 1; dc++) {
              if (dr === 0 && dc === 0) continue;
              const nr = r + dr, nc = c + dc;
              if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;
              if (CODE_TO_GROUP[grid[nr][nc].colorCode] === domGid) { adopted = grid[nr][nc]; break; }
            }
            if (adopted) break;
          }
          if (adopted) { newGrid[r][c] = adopted; continue; }
        }
        newGrid[r][c] = self;
      }
    }
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        grid[r][c] = newGrid[r][c];
      }
    }
  }
  return grid;
}

// ==================== 方向一致性优化 ====================

function directionalSmooth(grid, rows, cols, passCount) {
  for (let pass = 0; pass < passCount; pass++) {
    for (let r = 0; r < rows; r++) {
      for (let c = 1; c < cols - 1; c++) {
        const selfG = CODE_TO_GROUP[grid[r][c].colorCode];
        const leftG = CODE_TO_GROUP[grid[r][c - 1].colorCode];
        const rightG = CODE_TO_GROUP[grid[r][c + 1].colorCode];
        if (selfG !== leftG && selfG !== rightG && leftG === rightG) {
          grid[r][c] = grid[r][c - 1];
        }
      }
    }
    for (let c = 0; c < cols; c++) {
      for (let r = 1; r < rows - 1; r++) {
        const selfG = CODE_TO_GROUP[grid[r][c].colorCode];
        const upG = CODE_TO_GROUP[grid[r - 1][c].colorCode];
        const downG = CODE_TO_GROUP[grid[r + 1][c].colorCode];
        if (selfG !== upG && selfG !== downG && upG === downG) {
          grid[r][c] = grid[r - 1][c];
        }
      }
    }
  }
  return grid;
}

// ==================== 主函数 ====================

async function convertToBeadImage(imageBuffer, options = {}) {
  const cols = options.cols || 48;
  const cellSize = options.cellSize || 16;
  const showGrid = options.showGrid !== false;
  const showLegend = options.showLegend !== false;

  // Step 1: rotate first, then read metadata to get correct dimensions
  const rotated = sharp(imageBuffer).rotate();
  const meta = await rotated.metadata();
  const aspectRatio = meta.height / meta.width;
  const rows = options.rows > 0 ? options.rows : Math.max(1, Math.round(cols * aspectRatio));

  const resized = await rotated
    .resize(cols, rows, { fit: "inside", kernel: "lanczos3", background: { r: 255, g: 255, b: 255 } })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  const pixelData = resized.data;
  const channels = resized.info.channels;
  const actualCols = resized.info.width;
  const actualRows = resized.info.height;

  // Step 2: 分组匹配 + 拒绝阈值 + 透明色支持
  const grid = [];
  for (let row = 0; row < actualRows; row++) {
    grid[row] = [];
    for (let col = 0; col < actualCols; col++) {
      const idx = (row * actualCols + col) * channels;
      const r = pixelData[idx], g = pixelData[idx + 1], b = pixelData[idx + 2];
      const a = channels === 4 ? pixelData[idx + 3] : 255;
      grid[row][col] = matchPixel(r, g, b, a);
    }
  }

  // Step 3: 邻域平滑 — 动态轮数
  const pixels = actualCols * actualRows;
  const smoothPasses = Math.min(3, Math.max(1, Math.floor(Math.sqrt(pixels) / 30)));
  smoothByNeighbors(grid, actualRows, actualCols, smoothPasses);

  // Step 4: 方向一致性 — 动态轮数
  const dirPasses = pixels > 2500 ? 2 : 1;
  directionalSmooth(grid, actualRows, actualCols, dirPasses);

  // Step 5: 统计颜色
  const colorCount = new Map();
  for (let row = 0; row < actualRows; row++) {
    for (let col = 0; col < actualCols; col++) {
      const code = grid[row][col].colorCode;
      colorCount.set(code, (colorCount.get(code) || 0) + 1);
    }
  }
  const colors = Array.from(colorCount.entries())
    .filter(([code]) => code !== "???")
    .map(([colorCode, quantity]) => ({ colorCode, quantity }))
    .sort((a, b) => b.quantity - a.quantity);

  // ==================== 渲染 SVG ====================
  const gridWidth = actualCols * cellSize;
  const gridHeight = actualRows * cellSize;

  let svgCells = "";
  for (let row = 0; row < actualRows; row++) {
    for (let col = 0; col < actualCols; col++) {
      const { hexColor, colorCode } = grid[row][col];
      const x = col * cellSize;
      const y = row * cellSize;
      svgCells += `<rect x="${x}" y="${y}" width="${cellSize}" height="${cellSize}" fill="${hexColor}"/>`;

      if (cellSize >= 10) {
        const fontSize = Math.max(4, Math.floor(cellSize * 0.32));
        const cx = x + cellSize / 2;
        const cy = y + cellSize / 2 + fontSize * 0.35;
        const hex = hexColor.replace("#", "");
        const rr = parseInt(hex.slice(0, 2), 16);
        const gg = parseInt(hex.slice(2, 4), 16);
        const bb = parseInt(hex.slice(4, 6), 16);
        const luminance = 0.299 * rr + 0.587 * gg + 0.114 * bb;
        const textColor = luminance < 140 ? "#ffffff" : "#000000";
        svgCells += `<text x="${cx}" y="${cy}" text-anchor="middle" font-size="${fontSize}" fill="${textColor}" font-family="monospace" font-weight="bold">${colorCode}</text>`;
      }
    }
  }

  let svgGrid = "";
  if (showGrid) {
    const gridColor = "#cccccc";
    const strokeWidth = Math.max(0.5, cellSize * 0.04);
    for (let col = 0; col <= actualCols; col++) {
      svgGrid += `<line x1="${col * cellSize}" y1="0" x2="${col * cellSize}" y2="${gridHeight}" stroke="${gridColor}" stroke-width="${strokeWidth}"/>`;
    }
    for (let row = 0; row <= actualRows; row++) {
      svgGrid += `<line x1="0" y1="${row * cellSize}" x2="${gridWidth}" y2="${row * cellSize}" stroke="${gridColor}" stroke-width="${strokeWidth}"/>`;
    }
  }

  let legendSvg = "";
  let legendHeight = 0;
  if (showLegend && colors.length > 0) {
    const legendPadding = 12;
    const legendItemH = 22;
    const legendCellSize = 16;
    const legendFontSize = 11;
    const legendCols = Math.max(1, Math.floor(gridWidth / 120));
    const legendRows = Math.ceil(colors.length / legendCols);
    const headerH = 28;
    legendHeight = headerH + legendRows * legendItemH + legendPadding * 2;

    legendSvg += `<rect x="0" y="${gridHeight}" width="${gridWidth}" height="${legendHeight}" fill="none"/>`;
    legendSvg += `<line x1="0" y1="${gridHeight}" x2="${gridWidth}" y2="${gridHeight}" stroke="#dddddd" stroke-width="1"/>`;
    legendSvg += `<text x="${legendPadding}" y="${gridHeight + headerH - 8}" font-size="13" font-family="monospace" font-weight="bold" fill="#333333">颜色说明 (${colors.length} 种)</text>`;

    colors.forEach((c, i) => {
      const colIdx = i % legendCols;
      const rowIdx = Math.floor(i / legendCols);
      const itemW = gridWidth / legendCols;
      const ix = colIdx * itemW + legendPadding;
      const iy = gridHeight + headerH + rowIdx * legendItemH + legendPadding;
      const colorInfo = BEAD_COLORS.find(bc => bc.colorCode === c.colorCode);
      const hex = colorInfo ? colorInfo.hexColor : "#cccccc";
      legendSvg += `<rect x="${ix}" y="${iy}" width="${legendCellSize}" height="${legendCellSize}" fill="${hex}" rx="2"/>`;
      legendSvg += `<rect x="${ix}" y="${iy}" width="${legendCellSize}" height="${legendCellSize}" fill="none" stroke="#aaaaaa" stroke-width="0.5" rx="2"/>`;
      legendSvg += `<text x="${ix + legendCellSize + 5}" y="${iy + legendCellSize - 4}" font-size="${legendFontSize}" font-family="monospace" fill="#222222">${c.colorCode} ${c.quantity}</text>`;
    });
  }

  const totalHeight = gridHeight + legendHeight;
  const svgContent = `<svg xmlns="http://www.w3.org/2000/svg" width="${gridWidth}" height="${totalHeight}">
  ${svgCells}
  ${svgGrid}
  ${legendSvg}
</svg>`;

  const outputBuffer = await sharp(Buffer.from(svgContent))
    .png({ compressionLevel: 6 })
    .toBuffer();

  return { imageBuffer: outputBuffer, colors, gridSize: { cols: actualCols, rows: actualRows } };
}

module.exports = { convertToBeadImage };
