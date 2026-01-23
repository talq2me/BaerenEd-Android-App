package com.talq2me.baerened

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 8f
    }
    
    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 20f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    private val blueLinePaint = Paint().apply {
        color = Color.parseColor("#2196F3") // Blue for middle lines
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val pinkLinePaint = Paint().apply {
        color = Color.parseColor("#E91E63") // Pink for top and bottom lines
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private var isEraserMode = false
    private val paths = mutableListOf<Path>()
    private val pathPaint = mutableListOf<Paint>()
    private val isEraserPath = mutableListOf<Boolean>()
    private var currentPath = Path()
    private var currentPaint = paint
    
    private var lastX = 0f
    private var lastY = 0f
    
    // Lined paper configuration - 4 lines with equal whitespace between them
    // Top: pink line, whitespace, blue line, whitespace, blue line, whitespace, pink line at bottom
    // 5 equal gaps (top margin, 3 between lines, bottom margin) = height/5 each
    private val topPinkLineY: Float
        get() = height / 5f
    private val firstBlueLineY: Float
        get() = height * 2f / 5f
    private val secondBlueLineY: Float
        get() = height * 3f / 5f
    private val bottomPinkLineY: Float
        get() = height * 4f / 5f
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw white background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Draw lined paper
        drawLinedPaper(canvas)
        
        // Draw all paths (drawing paths first, then eraser paths on top)
        for (i in paths.indices) {
            if (isEraserPath[i]) {
                // For eraser paths, use a layer to properly clear
                val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                canvas.drawPath(paths[i], pathPaint[i])
                canvas.restoreToCount(layer)
            } else {
                canvas.drawPath(paths[i], pathPaint[i])
            }
        }
        
        // Draw current path (with proper layer for eraser)
        if (!currentPath.isEmpty) {
            if (isEraserMode) {
                // For eraser, we need to use a layer to properly clear
                val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                canvas.drawPath(currentPath, currentPaint)
                canvas.restoreToCount(layer)
            } else {
                canvas.drawPath(currentPath, currentPaint)
            }
        }
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Ensure we have valid dimensions
        if (w > 0 && h > 0) {
            invalidate()
        }
    }
    
    private fun drawLinedPaper(canvas: Canvas) {
        // Top pink line
        canvas.drawLine(0f, topPinkLineY, width.toFloat(), topPinkLineY, pinkLinePaint)
        
        // First blue line (middle region)
        canvas.drawLine(0f, firstBlueLineY, width.toFloat(), firstBlueLineY, blueLinePaint)
        
        // Second blue line (middle region)
        canvas.drawLine(0f, secondBlueLineY, width.toFloat(), secondBlueLineY, blueLinePaint)
        
        // Bottom pink line
        canvas.drawLine(0f, bottomPinkLineY, width.toFloat(), bottomPinkLineY, pinkLinePaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path()
                currentPath.moveTo(x, y)
                currentPaint = if (isEraserMode) {
                    Paint(eraserPaint)
                } else {
                    Paint(paint)
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)
                
                if (dx >= 4 || dy >= 4) {
                    currentPath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    lastX = x
                    lastY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(lastX, lastY)
                paths.add(Path(currentPath))
                pathPaint.add(Paint(currentPaint))
                isEraserPath.add(isEraserMode)
                currentPath.reset()
                invalidate()
            }
        }
        
        return true
    }
    
    fun setEraserMode(enabled: Boolean) {
        isEraserMode = enabled
    }
    
    fun clear() {
        paths.clear()
        pathPaint.clear()
        isEraserPath.clear()
        currentPath.reset()
        invalidate()
    }
    
    fun getDrawingBitmap(): Bitmap? {
        if (paths.isEmpty()) return null
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Draw white background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Draw all paths (drawing paths first, then eraser paths)
        for (i in paths.indices) {
            if (isEraserPath[i]) {
                // For eraser paths, use a layer to properly clear
                val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                canvas.drawPath(paths[i], pathPaint[i])
                canvas.restoreToCount(layer)
            } else {
                canvas.drawPath(paths[i], pathPaint[i])
            }
        }
        
        return bitmap
    }
}
