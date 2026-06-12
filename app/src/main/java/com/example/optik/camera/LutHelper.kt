package com.example.optik.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.floor

object LutHelper {
    
    // Cache LUT bitmaps to avoid reloading and out of memory
    private val lutCache = mutableMapOf<String, Bitmap>()
    private val processedThumbnails = mutableMapOf<String?, Bitmap>()
    private var sampleThumbnail: Bitmap? = null

    suspend fun initAndCacheThumbnails(context: Context, lutFiles: List<String?>) = withContext(Dispatchers.Default) {
        val cacheDir = java.io.File(context.cacheDir, "lut_thumbs")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        for (lutFile in lutFiles) {
            val fileName = lutFile ?: "original"
            val cacheFile = java.io.File(cacheDir, "$fileName.png")
            
            if (cacheFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null) {
                    processedThumbnails[lutFile] = bitmap
                    continue
                }
            }

            // Not found in cache, generate it
            val bitmap = applyLutToThumbnailInternal(context, lutFile)
            processedThumbnails[lutFile] = bitmap

            // Save to cache
            try {
                java.io.FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Clean up raw LUT bitmaps to free RAM
        lutCache.clear()
        sampleThumbnail?.recycle()
        sampleThumbnail = null
    }

    suspend fun getThumbnail(context: Context, lutFileName: String?): Bitmap {
        return processedThumbnails[lutFileName] ?: applyLutToThumbnailInternal(context, lutFileName)
    }

    private suspend fun applyLutToThumbnailInternal(context: Context, lutFileName: String?, sampleFileName: String = "sample_photo-2.JPG"): Bitmap = withContext(Dispatchers.Default) {
        if (sampleThumbnail == null) {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            context.assets.open(sampleFileName).use { BitmapFactory.decodeStream(it, null, options) }
            
            // Calculate inSampleSize for a thumbnail of ~150x150
            options.inSampleSize = calculateInSampleSize(options, 150, 150)
            options.inJustDecodeBounds = false
            
            val bitmap = context.assets.open(sampleFileName).use { BitmapFactory.decodeStream(it, null, options) }
            // Center crop and scale to exactly 150x150 for uniformity
            val minDim = kotlin.math.min(bitmap!!.width, bitmap.height)
            val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - minDim) / 2, (bitmap.height - minDim) / 2, minDim, minDim)
            sampleThumbnail = Bitmap.createScaledBitmap(cropped, 150, 150, true)
            if (cropped != bitmap) cropped.recycle()
            bitmap.recycle()
        }

        val thumbnail = sampleThumbnail!!
        if (lutFileName == null || lutFileName.isEmpty()) {
            return@withContext thumbnail
        }

        var lutBitmap = lutCache[lutFileName]
        if (lutBitmap == null) {
            lutBitmap = context.assets.open(lutFileName).use { BitmapFactory.decodeStream(it) }
            lutCache[lutFileName] = lutBitmap!!
        }

        // Apply CPU LUT
        applyHaldClut(thumbnail, lutBitmap!!)
    }

    private fun applyHaldClut(source: Bitmap, lut: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val lutWidth = lut.width
        val lutPixels = IntArray(lutWidth * lut.height)
        lut.getPixels(lutPixels, 0, lutWidth, 0, 0, lutWidth, lut.height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            val rScaled = (r * 63.0f) / 255.0f
            val gScaled = (g * 63.0f) / 255.0f
            val bScaled = (b * 63.0f) / 255.0f

            val rInt = rScaled.toInt()
            val gInt = gScaled.toInt()
            val bInt = bScaled.toInt()

            val xBlock = gInt % 8
            val yLocal = gInt / 8

            val lutX = xBlock * 64 + rInt
            val lutY = bInt * 8 + yLocal

            if (lutX in 0 until 512 && lutY in 0 until 512) {
                val lutColor = lutPixels[lutY * lutWidth + lutX]
                pixels[i] = Color.argb(
                    Color.alpha(color),
                    Color.red(lutColor),
                    Color.green(lutColor),
                    Color.blue(lutColor)
                )
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
