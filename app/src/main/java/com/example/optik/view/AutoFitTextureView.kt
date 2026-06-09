package com.example.optik.view

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import android.view.View

/**
 * A [TextureView] that can be adjusted to a specified aspect ratio.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative.")
        }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            // Thực hiện tính toán để CENTER CROP thay vì FIT CENTER
            // Để full màn hình và cắt đi phần dư, ta chọn kích thước lớn hơn
            // (Thường AutoFitTextureView mặc định là FIT_CENTER, ta sửa lại thành CENTER_CROP)
            
            val ratio = ratioHeight.toFloat() / ratioWidth.toFloat()
            val viewRatio = height.toFloat() / width.toFloat()
            
            if (ratio > viewRatio) {
                // Tỷ lệ camera dài hơn tỷ lệ view -> fit width, crop height
                setMeasuredDimension(width, (width * ratio).toInt())
            } else {
                // Tỷ lệ camera ngắn hơn tỷ lệ view -> fit height, crop width
                setMeasuredDimension((height / ratio).toInt(), height)
            }
        }
    }
}
