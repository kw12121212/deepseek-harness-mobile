package app.deepseek.harness.mobile

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal enum class AttachmentStripStatus { READY, UPLOADING, FAILED }
internal data class AttachmentStripItem(val id: Long, val name: String, val size: Long, val imageBytes: ByteArray?, val status: AttachmentStripStatus)

internal class AttachmentStripView(context: Context, private val palette: AppPalette) : HorizontalScrollView(context) {
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    init { isHorizontalScrollBarEnabled = false; addView(row); visibility = View.GONE }

    fun render(items: List<AttachmentStripItem>, onRemove: (Long) -> Unit, onRetry: (Long) -> Unit, onPreview: (Long) -> Unit) {
        row.removeAllViews()
        visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEach { item ->
            val card = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(palette.control); cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), if (item.status == AttachmentStripStatus.FAILED) palette.red else palette.borderSubtle)
                }
            }
            if (item.imageBytes != null) {
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "${item.name}, open original image"
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(item.imageBytes, 0, item.imageBytes.size, bounds)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
                    setImageBitmap(BitmapFactory.decodeByteArray(item.imageBytes, 0, item.imageBytes.size, options))
                    setOnClickListener { onPreview(item.id) }
                }
                card.addView(image, LinearLayout.LayoutParams(dp(48), dp(48)))
            } else {
                card.addView(TextView(context).apply {
                    text = "▤"; textSize = 21f; gravity = Gravity.CENTER; setTextColor(palette.muted)
                    contentDescription = "File"
                }, LinearLayout.LayoutParams(dp(36), dp(48)))
                val label = TextView(context).apply {
                    val size = android.text.format.Formatter.formatShortFileSize(context, item.size)
                    text = if (item.status == AttachmentStripStatus.FAILED) "${item.name}\nUpload failed · Retry" else "${item.name}\n$size"
                    textSize = 12f; setTextColor(if (item.status == AttachmentStripStatus.FAILED) palette.red else palette.text)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(4), 0)
                    contentDescription = text
                    if (item.status == AttachmentStripStatus.FAILED) setOnClickListener { onRetry(item.id) }
                }
                card.addView(label, LinearLayout.LayoutParams(dp(136), dp(48)))
            }
            if (item.status == AttachmentStripStatus.UPLOADING) {
                card.addView(ProgressBar(context).apply { isIndeterminate = true; contentDescription = "Uploading" }, LinearLayout.LayoutParams(dp(24), dp(24)))
            }
            card.addView(TextView(context).apply {
                text = "×"; textSize = 22f; gravity = Gravity.CENTER
                setTextColor(palette.text); contentDescription = "Remove ${item.name}"
                isClickable = true; isFocusable = true
                setOnClickListener { onRemove(item.id) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(card, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
        }
    }
}
