/**
 * Bead Blueprint Image Processor
 * 
 * Hybrid recognition using algorithmic image processing + AI fallback.
 * 
 * Algorithm overview:
 * 1. Grid detection: Find white/light grid lines to identify cell boundaries
 * 2. Cell segmentation: Divide image into individual bead cells
 * 3. Color extraction: For each cell, extract dominant color (ignoring grid lines)
 * 4. Color matching: Match RGB to nearest bead color code
 * 5. Confidence evaluation: Return low_confidence if too many cells couldn't match
 */

const sharp = require("sharp");
const { BEAD_COLORS } = require("../constants/beadColors");

// Build color lookup with precomputed RGB values
const COLOR_MAP = new Map();
for (const color of BEAD_COLORS) {
  const rgb = hexToRgb(color.hexColor);
  if (rgb) {
    COLOR_MAP.set(color.colorCode, { rgb, colorGroup: color.colorGroup });
  }
}
const BEAD_COLOR_CODES = new Set([...COLOR_MAP.keys()]);

// Grid detection parameters
const GRID_WHITE_THRESHOLD = 200;       // Min average RGB to consider a pixel "white"
const GRID_LINE_MIN_LENGTH = 0.05;       // Min fraction of image dimension for a grid line
const GRID_LINE_MAX_GAP = 3;            // Max gap between segments to still count as one line
const GRID_EDGE_MARGIN = 0.02;          // Ignore edges (often white background)
const CELL_EDGE_MARGIN = 0.05;          // Ignore cell edges (may have grid line bleed)

// Confidence thresholds
const MIN_GRID_LINES = 2;                // Minimum grid lines to detect
const MAX_CELL_DIMENSION = 200;        // Max cell size in pixels (sanity check)
const MIN_CELL_DIMENSION = 5;           // Min cell size in pixels (sanity check)
const LOW_CONFIDENCE_UNMATCHED_RATIO = 0.4; // If >40% cells unmatched, low confidence

/**
 * Process a bead blueprint image using algorithmic approach.
 * 
 * @param {Buffer} imageBuffer - Raw image buffer
 * @returns {Object} { success, colors, lowConfidence, debug, cells, gridInfo }
 */
async function processBeadImage(imageBuffer) {
  // Step 1: Load and resize image for faster processing
  const image = await sharp(imageBuffer)
    .resize(1024, 1024, { fit: "inside", withoutEnlargement: true })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  
  const { data, info } = image;
  const { width, height, channels } = info;
  
  // Step 2: Detect grid lines
  const gridInfo = detectGridLines(data, width, height, channels);
  
  if (gridInfo.horizontalLines.length < MIN_GRID_LINES || 
      gridInfo.verticalLines.length < MIN_GRID_LINES) {
    return {
      success: false,
      lowConfidence: true,
      reason: "Grid detection failed - not enough lines found",
      colors: [],
      debug: { gridInfo, width, height }
    };
  }
  
  // Step 3: Segment cells and extract colors
  const cellResults = segmentAndExtractColors(data, width, height, channels, gridInfo);
  
  // Step 4: Match colors to bead codes
  const colorCounts = new Map();
  let matchedCount = 0;
  let unmatchedCount = 0;
  
  for (const cell of cellResults) {
    if (!cell.valid) {
      unmatchedCount++;
      continue;
    }
    
    const matchedCode = findNearestBeadColor(cell.rgb);
    if (matchedCode) {
      colorCounts.set(matchedCode, (colorCounts.get(matchedCode) || 0) + 1);
      matchedCount++;
    } else {
      unmatchedCount++;
    }
  }
  
  // Step 5: Calculate confidence
  const totalCells = cellResults.length;
  const unmatchedRatio = totalCells > 0 ? unmatchedCount / totalCells : 1;
  const lowConfidence = unmatchedRatio > LOW_CONFIDENCE_UNMATCHED_RATIO || 
                        matchedCount < MIN_GRID_LINES;
  
  // Build result
  const colors = [];
  for (const [colorCode, count] of colorCounts.entries()) {
    colors.push({ colorCode, quantityPerBuild: count });
  }
  
  // Sort by quantity descending
  colors.sort((a, b) => b.quantityPerBuild - a.quantityPerBuild);
  
  return {
    success: colors.length > 0,
    colors,
    lowConfidence,
    debug: {
      gridInfo: {
        horizontalLines: gridInfo.horizontalLines.length,
        verticalLines: gridInfo.verticalLines.length,
        estimatedCellSize: gridInfo.estimatedCellSize
      },
      totalCells,
      matchedCount,
      unmatchedCount,
      unmatchedRatio: Math.round(unmatchedRatio * 100) + "%"
    }
  };
}

/**
 * Detect horizontal and vertical grid lines in the image.
 * 
 * Strategy:
 * 1. Create a grayscale representation where white/light pixels are highlighted
 * 2. For each row, check if it's mostly white (horizontal line)
 * 3. For each column, check if it's mostly white (vertical line)
 * 4. Merge consecutive lines into line segments
 */
function detectGridLines(data, width, height, channels) {
  // Create grayscale and white mask
  const grayscale = new Uint8Array(width * height);
  const isWhite = new Uint8Array(width * height);
  
  for (let i = 0; i < width * height; i++) {
    const r = data[i * channels];
    const g = data[i * channels + 1];
    const b = data[i * channels + 2];
    
    // Grayscale (simple average)
    grayscale[i] = Math.round((r + g + b) / 3);
    
    // White/light pixel detection
    isWhite[i] = (r >= GRID_WHITE_THRESHOLD && 
                  g >= GRID_WHITE_THRESHOLD && 
                  b >= GRID_WHITE_THRESHOLD) ? 1 : 0;
  }
  
  // Find horizontal lines (rows with high white pixel density)
  const horizontalLines = findLines(isWhite, width, height, true);
  
  // Find vertical lines (columns with high white pixel density)
  const verticalLines = findLines(isWhite, width, height, false);
  
  // Estimate cell size from line spacing
  const avgHSpacing = horizontalLines.length > 1 
    ? medianDifferences(horizontalLines.map(l => l.position))
    : Math.round(height / 10);
  const avgVSpacing = verticalLines.length > 1 
    ? medianDifferences(verticalLines.map(l => l.position))
    : Math.round(width / 10);
  
  return {
    horizontalLines,
    verticalLines,
    estimatedCellSize: { height: avgHSpacing, width: avgVSpacing }
  };
}

/**
 * Find line positions in the given direction.
 * @param {Uint8Array} isWhite - White pixel mask
 * @param {width} - Image width
 * @param {height} - Image height
 * @param {horizontal} - True for horizontal lines (scan rows), False for vertical (scan columns)
 */
function findLines(isWhite, width, height, horizontal) {
  const dim = horizontal ? height : width;
  const otherDim = horizontal ? width : height;
  const lineThreshold = Math.round(otherDim * 0.3); // 30% white pixels to count as a line
  
  const lines = [];
  let consecutiveWhite = 0;
  let lineStart = -1;
  
  for (let i = 0; i < dim; i++) {
    let whiteCount = 0;
    
    // Count white pixels along this row/column
    for (let j = 0; j < otherDim; j++) {
      const idx = horizontal ? (i * width + j) : (j * width + i);
      whiteCount += isWhite[idx];
    }
    
    const isLineRow = whiteCount >= lineThreshold;
    
    if (isLineRow) {
      if (consecutiveWhite === 0) {
        lineStart = i;
      }
      consecutiveWhite++;
    } else {
      if (consecutiveWhite >= Math.round(dim * GRID_LINE_MIN_LENGTH)) {
        lines.push({ position: Math.round(lineStart + consecutiveWhite / 2), length: consecutiveWhite });
      }
      consecutiveWhite = 0;
      lineStart = -1;
    }
  }
  
  // Don't forget the last line
  if (consecutiveWhite >= Math.round(dim * GRID_LINE_MIN_LENGTH)) {
    lines.push({ position: Math.round(lineStart + consecutiveWhite / 2), length: consecutiveWhite });
  }
  
  return lines;
}

/**
 * Segment the image into cells and extract colors.
 */
function segmentAndExtractColors(data, width, height, channels, gridInfo) {
  const { horizontalLines, verticalLines } = gridInfo;
  const cells = [];
  
  // Sort line positions
  const hPositions = [0, ...horizontalLines.map(l => l.position), height];
  const vPositions = [0, ...verticalLines.map(l => l.position), width];
  
  // Iterate through each cell
  for (let hi = 0; hi < hPositions.length - 1; hi++) {
    for (let vi = 0; vi < vPositions.length - 1; vi++) {
      const y1 = hPositions[hi];
      const y2 = hPositions[hi + 1];
      const x1 = vPositions[vi];
      const x2 = vPositions[vi + 1];
      
      // Calculate cell dimensions
      const cellWidth = x2 - x1;
      const cellHeight = y2 - y1;
      
      // Skip invalid cells
      if (cellWidth < MIN_CELL_DIMENSION || cellHeight < MIN_CELL_DIMENSION ||
          cellWidth > MAX_CELL_DIMENSION * 3 || cellHeight > MAX_CELL_DIMENSION * 3) {
        continue;
      }
      
      // Extract color from cell (ignoring edges where grid lines may bleed)
      const marginX = Math.round(cellWidth * CELL_EDGE_MARGIN);
      const marginY = Math.round(cellHeight * CELL_EDGE_MARGIN);
      
      const colorRgb = extractCellColor(data, width, height, channels, 
        x1 + marginX, y1 + marginY,
        x2 - marginX, y2 - marginY);
      
      if (colorRgb) {
        cells.push({
          valid: true,
          rgb: colorRgb,
          x: x1, y: y1,
          w: cellWidth, h: cellHeight
        });
      } else {
        cells.push({ valid: false });
      }
    }
  }
  
  return cells;
}

/**
 * Extract dominant color from a cell region.
 * Ignores white/light pixels (grid line bleed).
 */
function extractCellColor(data, width, height, channels, x1, y1, x2, y2) {
  const pixelCount = (x2 - x1) * (y2 - y1);
  if (pixelCount <= 0) return null;
  
  let r = 0, g = 0, b = 0;
  let validPixels = 0;
  
  for (let y = y1; y < y2; y++) {
    for (let x = x1; x < x2; x++) {
      const idx = (y * width + x) * channels;
      const pr = data[idx];
      const pg = data[idx + 1];
      const pb = data[idx + 2];
      
      // Skip white/light pixels (grid line bleed or background)
      if (pr >= 220 && pg >= 220 && pb >= 220) continue;
      
      r += pr;
      g += pg;
      b += pb;
      validPixels++;
    }
  }
  
  if (validPixels < pixelCount * 0.3) {
    return null; // Cell is mostly white/background
  }
  
  return {
    r: Math.round(r / validPixels),
    g: Math.round(g / validPixels),
    b: Math.round(b / validPixels)
  };
}

/**
 * Find the nearest bead color for a given RGB color.
 * Uses weighted Euclidean distance (more weight on color difference).
 */
function findNearestBeadColor(rgb) {
  let bestCode = null;
  let bestDistance = Infinity;
  
  for (const [code, data] of COLOR_MAP.entries()) {
    const distance = colorDistance(rgb, data.rgb);
    
    if (distance < bestDistance) {
      bestDistance = distance;
      bestCode = code;
    }
  }
  
  // If distance is too large, no good match
  // Threshold based on perceptually significant difference
  if (bestDistance > 80) {
    return null;
  }
  
  return bestCode;
}

/**
 * Calculate color distance using weighted Euclidean distance.
 * Weights are based on human color perception (green is more sensitive).
 */
function colorDistance(c1, c2) {
  // Weights based on human perception
  const wR = 0.299;
  const wG = 0.587;
  const wB = 0.114;
  
  const dr = c1.r - c2.r;
  const dg = c1.g - c2.g;
  const db = c1.b - c2.b;
  
  return Math.sqrt(wR * dr * dr + wG * dg * dg + wB * db * db);
}

/**
 * Calculate median of differences between consecutive values.
 */
function medianDifferences(values) {
  if (values.length < 2) return 0;
  
  const diffs = [];
  for (let i = 1; i < values.length; i++) {
    diffs.push(values[i] - values[i - 1]);
  }
  
  diffs.sort((a, b) => a - b);
  return diffs[Math.floor(diffs.length / 2)];
}

/**
 * Convert hex color to RGB object.
 */
function hexToRgb(hex) {
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
  return result ? {
    r: parseInt(result[1], 16),
    g: parseInt(result[2], 16),
    b: parseInt(result[3], 16)
  } : null;
}

module.exports = {
  processBeadImage,
  findNearestBeadColor,
  colorDistance,
  hexToRgb,
  BEAD_COLORS,
  BEAD_COLOR_CODES
};
