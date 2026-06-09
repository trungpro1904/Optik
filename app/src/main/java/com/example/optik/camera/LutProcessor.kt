package com.example.optik.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsic3DLUT
import android.renderscript.Type
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object LutProcessor {

    fun processAndSaveLut(
        context: Context,
        jpegBytes: ByteArray,
        lutFileName: String?,
        outputStream: java.io.OutputStream
    ): Boolean {
        var tempInFile: File? = null
        var tempOutFile: File? = null
        try {
            tempInFile = File.createTempFile("orig", ".jpg", context.cacheDir)
            tempInFile.writeBytes(jpegBytes)
            val oldExif = ExifInterface(tempInFile.absolutePath)

            val options = BitmapFactory.Options()
            options.inMutable = true
            options.inPreferredConfig = Bitmap.Config.ARGB_8888 // Ép kiểu ARGB_8888
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                ?: return false

            // LUÔN LUÔN xử lý qua GPU để kiểm chứng thuật toán. 
            // Nếu lutFileName là null hoặc "Gốc", applyLutWithRenderScript sẽ dùng Identity LUT.
            applyLutWithRenderScript(context, bitmap, lutFileName)

            tempOutFile = File.createTempFile("processed", ".jpg", context.cacheDir)
            val fos = FileOutputStream(tempOutFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            fos.close()
            bitmap.recycle()

            copyExif(oldExif, tempOutFile.absolutePath)

            val fis = FileInputStream(tempOutFile)
            fis.copyTo(outputStream)
            fis.close()
            return true
        } catch (e: Exception) {
            Log.e("LutProcessor", "Failed to process LUT and save", e)
            return false
        } finally {
            tempInFile?.delete()
            tempOutFile?.delete()
        }
    }

    private fun applyLutWithRenderScript(context: Context, bitmap: Bitmap, lutFileName: String?) {
        val rs = RenderScript.create(context)
        try {
            val lutSize = 64 
            val lutIntArray = IntArray(lutSize * lutSize * lutSize)

            if (lutFileName == null || lutFileName.equals("Gốc", ignoreCase = true)) {
                // IDENTITY LUT: Đảm bảo độ sáng chuẩn (255)
                for (b in 0 until lutSize) {
                    val ib = (b * 255) / (lutSize - 1)
                    val bOffset = b * lutSize * lutSize
                    for (g in 0 until lutSize) {
                        val ig = (g * 255) / (lutSize - 1)
                        val gOffset = g * lutSize
                        for (r in 0 until lutSize) {
                            val ir = (r * 255) / (lutSize - 1)
                            // Đóng gói 0xAABBGGRR (Little Endian RGBA)
                            val packed = (0xFF shl 24) or (ib shl 16) or (ig shl 8) or ir
                            lutIntArray[r + gOffset + bOffset] = packed
                        }
                    }
                }
            } else {
                val lutOptions = BitmapFactory.Options().apply {
                    inScaled = false // QUAN TRỌNG: Tránh Android tự resize ảnh LUT
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val lutStream = context.assets.open(lutFileName)
                val lutBitmap = BitmapFactory.decodeStream(lutStream, null, lutOptions)
                lutStream.close()
                if (lutBitmap == null) return

                val lutPixels = IntArray(lutBitmap.width * lutBitmap.height)
                lutBitmap.getPixels(lutPixels, 0, lutBitmap.width, 0, 0, lutBitmap.width, lutBitmap.height)

                // Tự động xác định kích thước LUT từ ảnh Hald CLUT
                // Hald CLUT level N: kích thước ảnh = N³ × N³, LUT size = N²
                // Ví dụ: level 8 → ảnh 512×512, LUT size 64
                val imgWidth = lutBitmap.width
                val imgHeight = lutBitmap.height
                val totalPixels = imgWidth * imgHeight
                
                // Xác minh: totalPixels phải = lutSize^3
                val expectedPixels = lutSize * lutSize * lutSize
                if (totalPixels < expectedPixels) {
                    Log.e("LutProcessor", "Hald LUT image too small: ${imgWidth}x${imgHeight} " +
                        "(${totalPixels} pixels), expected at least $expectedPixels")
                    lutBitmap.recycle()
                    return
                }

                // HALD CLUT: Đọc theo thứ tự tuyến tính pixel (scan order)
                // Pixel thứ i (đọc từ trái sang phải, trên xuống dưới):
                //   lutR = i % lutSize
                //   lutG = (i / lutSize) % lutSize  
                //   lutB = i / (lutSize * lutSize)
                for (i in 0 until expectedPixels) {
                    val color = lutPixels[i]
                    
                    // Tách kênh từ Android Bitmap (ARGB packed int)
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val a = (color shr 24) and 0xFF

                    // Đóng gói cho RenderScript ScriptIntrinsic3DLUT (ABGR format)
                    val packed = (a shl 24) or (b shl 16) or (g shl 8) or r

                    // Hald CLUT tuyến tính: R thay đổi nhanh nhất, B chậm nhất
                    val lutR = i % lutSize
                    val lutG = (i / lutSize) % lutSize
                    val lutB = i / (lutSize * lutSize)

                    val index = lutR + (lutG * lutSize) + (lutB * lutSize * lutSize)
                    lutIntArray[index] = packed
                }
                lutBitmap.recycle()
            }

            val lutType = Type.Builder(rs, Element.U8_4(rs))
                .setX(lutSize).setY(lutSize).setZ(lutSize).create()
            val lutAlloc = Allocation.createTyped(rs, lutType)
            lutAlloc.copyFromUnchecked(lutIntArray)

            val script3DLut = ScriptIntrinsic3DLUT.create(rs, Element.U8_4(rs))
            script3DLut.setLUT(lutAlloc)

            // Dùng ARGB_8888 để GPU xử lý chính xác nhất
            val outBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val allocIn = Allocation.createFromBitmap(rs, bitmap)
            val allocOut = Allocation.createFromBitmap(rs, outBitmap)

            script3DLut.forEach(allocIn, allocOut)
            allocOut.copyTo(bitmap)

            outBitmap.recycle()
            allocIn.destroy(); allocOut.destroy(); lutAlloc.destroy()
            script3DLut.destroy(); lutType.destroy()
        } catch (e: Exception) {
            Log.e("LutProcessor", "RenderScript error", e)
        } finally {
            rs.destroy()
        }
    }

    private fun copyExif(oldExif: ExifInterface, newFilePath: String) {
        val newExif = ExifInterface(newFilePath)
        val attributes = listOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SHARPNESS
        )

        for (tag in attributes) {
            val value = oldExif.getAttribute(tag)
            if (value != null) {
                newExif.setAttribute(tag, value)
            }
        }
        newExif.saveAttributes()
    }
}
