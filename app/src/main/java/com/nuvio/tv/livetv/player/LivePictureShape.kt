package com.nuvio.tv.livetv.player

import android.graphics.Rect
import android.view.View
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.ui.screens.player.AspectMode
import com.nuvio.tv.ui.screens.player.resolveAspectScale
import kotlin.math.abs

/**
 * How a channel's picture shape is worked out. Streams carry a "pixel shape" flag that says how
 * wide each pixel should be drawn. Real SD broadcasts (720×576, 720×480) and HDV (1440×1080) need
 * it, but some 24/7 channels re-encode movies to 1920×800 and leave a wrong flag in, so the movie
 * comes out squashed and tall instead of wide with black bars.
 */
enum class PictureShape(val label: String) {
    /** Trust the flag, except on full-HD and HD widths, where it's almost always a mistake. */
    AUTO("Auto"),
    /** Always draw pixels square: 1920×800 shows as 2.40:1. */
    REAL("Use real size"),
    /** Always follow the stream's flag, as before. */
    STREAM("Use stream's shape");

    companion object {
        fun of(name: String?): PictureShape = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

internal object LivePictureShape {
    /** Widths that are always square-pixel when encoded properly. */
    private val SQUARE_WIDTHS = setOf(1280, 1920, 2560, 3840)

    /** The stream's flag says something other than square pixels. */
    fun hasFlag(par: Float): Boolean = par > 0f && abs(par - 1f) > 0.01f

    /** True when the flag is being ignored for this video and choice. */
    fun ignoresFlag(width: Int, par: Float, shape: PictureShape): Boolean = hasFlag(par) && when (shape) {
        PictureShape.REAL -> true
        PictureShape.STREAM -> false
        PictureShape.AUTO -> width in SQUARE_WIDTHS
    }

    /** The shape to draw (width ÷ height), or null before the size is known. */
    fun aspect(width: Int, height: Int, par: Float, shape: PictureShape): Float? {
        if (width <= 0 || height <= 0) return null
        val p = if (ignoresFlag(width, par, shape) || par <= 0f) 1f else par
        return width * p / height
    }

    fun aspect(size: VideoSize, shape: PictureShape): Float? =
        aspect(size.width, size.height, size.pixelWidthHeightRatio, shape)

    /** "2.40:1" */
    fun ratioText(aspect: Float): String = "%.2f:1".format(aspect)

    /**
     * Sizes the picture in [view] to the worked-out shape, then applies the picture-size mode
     * (Fit, Crop, Stretch, …) on top, the same way Nuvio's movie player does but from that shape.
     */
    fun apply(view: PlayerView, shape: PictureShape, mode: AspectMode?) {
        val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout
            ?: return
        // The preview window (no screen-size mode) is never zoomed or stretched: undo whatever
        // the full-screen size (Slight Zoom, Crop, Stretch…) left on the picture. Without this
        // the zoom stayed on when going back to the guide and the video spilled out of the
        // preview window.
        if (mode == null) reset(frame)
        val size = view.player?.videoSize ?: return
        val aspect = aspect(size, shape) ?: return
        frame.setAspectRatio(aspect)
        if (mode == null) return
        reset(frame)
        val viewAspect = if (view.width > 0 && view.height > 0) view.width.toFloat() / view.height else 0f
        val scale = resolveAspectScale(mode, viewAspect, aspect)
        frame.scaleX = scale.scaleX
        frame.scaleY = scale.scaleY
        center(view, frame)
    }

    private fun reset(v: View) {
        v.scaleX = 1f; v.scaleY = 1f
        v.translationX = 0f; v.translationY = 0f
        if (v.width > 0) v.pivotX = v.width / 2f
        if (v.height > 0) v.pivotY = v.height / 2f
    }

    private fun center(parent: PlayerView, target: View) {
        if (parent.width <= 0 || parent.height <= 0 || target.width <= 0 || target.height <= 0) return
        val r = Rect(0, 0, target.width, target.height)
        parent.offsetDescendantRectToMyCoords(target, r)
        target.translationX = parent.width / 2f - (r.left + r.width() / 2f)
        target.translationY = parent.height / 2f - (r.top + r.height() / 2f)
    }
}
