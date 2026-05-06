package com.example.couplecredit.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BeadGridView extends View {

    private static final int FALLBACK_COLOR = Color.parseColor("#DDDDDD");

    private String[][] gridData;
    private int[][] cellColors;
    private boolean[][] cellDarkText;
    private final Map<String, Integer> colorMap = new HashMap<>();
    private String highlightColor = null;

    private float offsetX = 0;
    private float offsetY = 0;
    private float scale = 1f;
    private float minScale = 0.5f;
    private float maxScale = 5f;

    private int cellSize = 24;
    private final Rect textBounds = new Rect();

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public BeadGridView(Context context) {
        this(context, null);
    }

    public BeadGridView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BeadGridView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        gridPaint.setColor(Color.parseColor("#cccccc"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.5f);

        highlightPaint.setColor(Color.WHITE);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(2f);
    }

    public void setGridData(List<List<String>> data, Map<String, String> colorCodeToHex) {
        if (data == null || data.isEmpty()) {
            this.gridData = null;
            this.cellColors = null;
            this.cellDarkText = null;
            this.colorMap.clear();
            invalidate();
            return;
        }
        int rows = data.size();
        int cols = 0;
        for (List<String> row : data) {
            if (row != null && row.size() > cols) cols = row.size();
        }
        this.gridData = new String[rows][cols];
        this.cellColors = new int[rows][cols];
        this.cellDarkText = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            List<String> row = data.get(r);
            for (int c = 0; c < cols; c++) {
                String code = (row != null && c < row.size()) ? row.get(c) : "???";
                this.gridData[r][c] = code;
            }
        }

        this.colorMap.clear();
        if (colorCodeToHex != null) {
            for (Map.Entry<String, String> entry : colorCodeToHex.entrySet()) {
                try {
                    this.colorMap.put(entry.getKey(), Color.parseColor(entry.getValue()));
                } catch (Exception ignored) {
                    this.colorMap.put(entry.getKey(), FALLBACK_COLOR);
                }
            }
        }
        this.colorMap.put("???", FALLBACK_COLOR);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Integer cv = colorMap.get(gridData[r][c]);
                int colorVal = cv != null ? cv : FALLBACK_COLOR;
                cellColors[r][c] = colorVal;
                int lum = (int) (0.299 * Color.red(colorVal) + 0.587 * Color.green(colorVal) + 0.114 * Color.blue(colorVal));
                cellDarkText[r][c] = lum < 140;
            }
        }

        float viewW = getWidth() > 0 ? (float) getWidth() : 800f;
        float viewH = getHeight() > 0 ? (float) getHeight() : 400f;
        float scaleW = viewW / (cols * cellSize);
        float scaleH = viewH / (rows * cellSize);
        scale = Math.min(scaleW, scaleH);
        minScale = Math.min(scale, 0.5f);
        maxScale = 5f;
        offsetX = 0;
        offsetY = 0;

        invalidate();
    }

    public void setHighlightColor(String colorCode) {
        if (colorCode != null && colorCode.equals(this.highlightColor)) {
            this.highlightColor = null;
        } else {
            this.highlightColor = colorCode;
        }
        invalidate();
    }

    public String getHighlightColor() {
        return highlightColor;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (gridData == null || gridData.length == 0 || gridData[0].length == 0) return;

        int rows = gridData.length;
        int cols = gridData[0].length;

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        float fontSize = Math.max(6, cellSize * 0.32f);
        textPaint.setTextSize(fontSize);

        float gridStrokeW = Math.max(0.5f, cellSize * 0.04f);
        gridPaint.setStrokeWidth(gridStrokeW);

        boolean hasHighlight = highlightColor != null;
        boolean showText = cellSize >= 10 && scale >= 0.8f;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String code = gridData[r][c];
                float x = c * cellSize;
                float y = r * cellSize;

                boolean isHighlighted = hasHighlight && highlightColor.equals(code);
                boolean isDimmed = hasHighlight && !isHighlighted;

                cellPaint.setColor(cellColors[r][c]);
                cellPaint.setAlpha(isDimmed ? 77 : 255);
                canvas.drawRect(x, y, x + cellSize, y + cellSize, cellPaint);

                if (isHighlighted) {
                    highlightPaint.setStrokeWidth(2f / scale);
                    canvas.drawRect(x, y, x + cellSize, y + cellSize, highlightPaint);
                }

                if (showText) {
                    textPaint.setColor(cellDarkText[r][c] ? Color.WHITE : Color.BLACK);
                    textPaint.setAlpha(isDimmed ? 77 : 255);
                    float cx = x + cellSize / 2f;
                    float cy = y + cellSize / 2f + fontSize * 0.35f;
                    canvas.drawText(code, cx, cy, textPaint);
                }
            }
        }

        if (scale > 0.3f) {
            for (int c = 0; c <= cols; c++) {
                canvas.drawLine(c * cellSize, 0, c * cellSize, rows * cellSize, gridPaint);
            }
            for (int r = 0; r <= rows; r++) {
                canvas.drawLine(0, r * cellSize, cols * cellSize, r * cellSize, gridPaint);
            }
        }

        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean scaleHandled = scaleDetector.onTouchEvent(event);
        boolean gestureHandled = gestureDetector.onTouchEvent(event);
        if (scaleHandled || gestureHandled) return true;
        return super.onTouchEvent(event);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float newScale = Math.max(minScale, Math.min(maxScale, scale * factor));
            float pivotX = detector.getFocusX();
            float pivotY = detector.getFocusY();
            offsetX = pivotX - (pivotX - offsetX) * (newScale / scale);
            offsetY = pivotY - (pivotY - offsetY) * (newScale / scale);
            scale = newScale;
            invalidate();
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            offsetX -= distanceX;
            offsetY -= distanceY;
            invalidate();
            return true;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }
    }
}
