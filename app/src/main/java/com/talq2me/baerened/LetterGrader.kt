package com.talq2me.baerened

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Grades handwritten letters by comparing drawn strokes to expected letter shapes.
 * This is a simplified grading system that checks basic characteristics:
 * - Presence of expected strokes
 * - General shape recognition
 * - Position on the baseline
 */
object LetterGrader {
    
    fun gradeWord(word: String, drawing: Bitmap, canvasWidth: Int, canvasHeight: Int): Int {
        val wordLower = word.lowercase()
        var totalScore = 0.0
        var letterCount = 0
        
        // Calculate approximate letter width (assuming letters are evenly spaced)
        val approximateLetterWidth = canvasWidth / word.length
        
        // Grade each letter
        for (i in wordLower.indices) {
            val letter = wordLower[i]
            if (letter.isLetter()) {
                val letterX = (i * approximateLetterWidth + approximateLetterWidth / 2).toInt()
                val letterScore = gradeLetter(letter, drawing, letterX, canvasWidth, canvasHeight)
                totalScore += letterScore
                letterCount++
            }
        }
        
        if (letterCount == 0) return 1
        
        val averageScore = totalScore / letterCount
        
        // Convert score to stars (0.0-1.0 scale) - be lenient for kids
        return when {
            averageScore >= 0.6 -> 3 // Excellent
            averageScore >= 0.4 -> 2 // Good
            else -> 1 // Needs practice
        }
    }
    
    private fun gradeLetter(letter: Char, drawing: Bitmap, centerX: Int, canvasWidth: Int, canvasHeight: Int): Double {
        // Calculate letter region in bitmap coordinates
        val approximateLetterWidth = canvasWidth.toDouble() / drawing.width
        val letterRegionWidth = (approximateLetterWidth * 0.8).toInt() // 80% of letter width
        val startX = (centerX - letterRegionWidth / 2).coerceAtLeast(0)
        val endX = (centerX + letterRegionWidth / 2).coerceAtMost(canvasWidth - 1)
        
        // Convert to bitmap coordinates
        val regionStartX = (startX * drawing.width / canvasWidth).coerceAtLeast(0)
        val regionEndX = (endX * drawing.width / canvasWidth).coerceAtMost(drawing.width - 1)
        val regionWidth = (regionEndX - regionStartX).coerceAtLeast(1)
        
        val regionHeight = (drawing.height * 0.6).toInt() // Focus on middle 60% of height
        val regionStartY = (drawing.height * 0.2).toInt()
        val regionEndY = (drawing.height * 0.8).toInt()
        
        if (regionWidth <= 0 || regionHeight <= 0 || regionStartX >= drawing.width || regionStartY >= drawing.height) {
            return 0.4 // Give some credit even if region calculation is off
        }
        
        // Extract region bitmap
        val letterRegion = try {
            Bitmap.createBitmap(
                drawing,
                regionStartX.coerceIn(0, drawing.width - 1),
                regionStartY.coerceIn(0, drawing.height - 1),
                regionWidth.coerceAtMost(drawing.width - regionStartX),
                regionHeight.coerceAtMost(drawing.height - regionStartY)
            )
        } catch (e: Exception) {
            return 0.4 // Give some credit if extraction fails
        }
        
        // Check if there's any drawing in this region
        val hasDrawing = hasDrawingInRegion(letterRegion)
        if (!hasDrawing) {
            letterRegion.recycle()
            return 0.3 // Low score if nothing drawn, but not zero
        }
        
        // Basic shape recognition based on letter characteristics
        val shapeScore = evaluateLetterShape(letter, letterRegion)
        
        // Position score (check if drawing is roughly in the right vertical position)
        val positionScore = evaluatePosition(letter, letterRegion, regionStartY, drawing.height)
        
        letterRegion.recycle()
        
        // Combine scores - be more lenient for kids
        val combinedScore = (shapeScore * 0.5 + positionScore * 0.3 + 0.2).coerceIn(0.0, 1.0) // Add base 0.2 for effort
        return combinedScore
    }
    
    private fun hasDrawingInRegion(bitmap: Bitmap): Boolean {
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val isDark = Color.red(pixel) < 200 || Color.green(pixel) < 200 || Color.blue(pixel) < 200
                if (alpha > 50 && isDark) {
                    return true
                }
            }
        }
        return false
    }
    
    private fun evaluateLetterShape(letter: Char, region: Bitmap): Double {
        // Simplified shape evaluation
        // This is a basic implementation - could be enhanced with more sophisticated recognition
        
        val pixelCount = countDarkPixels(region)
        val density = pixelCount.toDouble() / (region.width * region.height)
        
        // Different letters have different characteristics
        return when (letter) {
            'a', 'e', 'o' -> {
                // Round letters - should have moderate density
                if (density in 0.05..0.25) 0.8 else 0.4
            }
            'i', 'l', 't' -> {
                // Tall, thin letters - should have lower density
                if (density in 0.02..0.15) 0.8 else 0.4
            }
            'g', 'j', 'p', 'q', 'y' -> {
                // Letters with descenders - should have higher density
                if (density in 0.08..0.3) 0.8 else 0.4
            }
            'b', 'd', 'h', 'k' -> {
                // Tall letters with loops - should have moderate-high density
                if (density in 0.06..0.25) 0.8 else 0.4
            }
            'f' -> {
                // Special case - tall with crossbar
                if (density in 0.05..0.2) 0.8 else 0.4
            }
            'm', 'w' -> {
                // Wide letters - should have higher density
                if (density in 0.1..0.3) 0.8 else 0.4
            }
            'n', 'u', 'v' -> {
                // Medium width letters
                if (density in 0.06..0.25) 0.8 else 0.4
            }
            'r', 's', 'z' -> {
                // Medium complexity letters
                if (density in 0.05..0.2) 0.8 else 0.4
            }
            'c' -> {
                // Round, open letter
                if (density in 0.04..0.2) 0.8 else 0.4
            }
            'x' -> {
                // Cross shape
                if (density in 0.05..0.2) 0.8 else 0.4
            }
            else -> {
                // Default scoring
                if (density in 0.04..0.25) 0.7 else 0.3
            }
        }
    }
    
    private fun countDarkPixels(bitmap: Bitmap): Int {
        var count = 0
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val isDark = Color.red(pixel) < 200 || Color.green(pixel) < 200 || Color.blue(pixel) < 200
                if (alpha > 50 && isDark) {
                    count++
                }
            }
        }
        return count
    }
    
    private fun evaluatePosition(letter: Char, region: Bitmap, regionY: Int, canvasHeight: Int): Double {
        // Check if the drawing is positioned correctly relative to baseline
        // For most letters, drawing should be in the middle region
        // For descenders (g, j, p, q, y), drawing should extend lower
        
        val normalizedY = regionY.toDouble() / canvasHeight
        
        return when (letter) {
            'g', 'j', 'p', 'q', 'y' -> {
                // Descenders should be lower
                if (normalizedY in 0.3..0.6) 0.8 else 0.5
            }
            'b', 'd', 'f', 'h', 'k', 'l', 't' -> {
                // Tall letters should be higher
                if (normalizedY in 0.1..0.4) 0.8 else 0.5
            }
            else -> {
                // Regular letters should be in middle
                if (normalizedY in 0.2..0.5) 0.8 else 0.5
            }
        }
    }
}
