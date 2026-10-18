package com.google.ar.core.examples.kotlin.helloar.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withClip
import com.google.ar.core.examples.kotlin.helloar.R
import com.google.ar.core.examples.kotlin.helloar.model.Location
import com.google.ar.core.examples.kotlin.helloar.model.MapPosition

/**
 * 屋内マップ本体。地点と、プレイヤーの現在地を描く
 * 位置は [MapPosition]（0〜1 の割合）を、実際の表示サイズに掛けて求める
 * 縦横比は 3:4 で、置かれた場所に収まる大きさにする
 */
class IndoorMapView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {
  companion object {
    private const val ASPECT_RATIO = 3f / 4f
    /** 位置は推定し直すたびに飛ぶので、少しだけ時間をかけて動かす。次の測定の間は止めておきたいので、測定時間（2 秒）より十分短くする */
    private const val MOVE_DURATION_MS = 500L
    private val FLOOR_COLOR = Color.rgb(158, 214, 158)
    private val CURRENT_COLOR = Color.rgb(0, 122, 255)
  }

  var locations: List<Location> = emptyList()
    set(value) {
      field = value
      invalidate()
    }

  var currentLocation: Location? = null
    set(value) {
      field = value
      invalidate()
    }

  /** プレイヤーを描く位置。地点と地点の間のこともある */
  var currentPosition: MapPosition? = null
    set(value) {
      if (field == value) return
      field = value
      moveDisplayedPosition(to = value)
    }

  /** 実際に描いている位置。currentPosition に向かってアニメーションで動く */
  private var displayedPosition: MapPosition? = null
  private var moveAnimator: ValueAnimator? = null

  private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FLOOR_COLOR }
  private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = sp(12f)
    typeface = Typeface.DEFAULT_BOLD
    textAlign = Paint.Align.CENTER
  }
  private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CURRENT_COLOR }
  private val playerIcon = ContextCompat.getDrawable(context, R.drawable.ic_directions_walk)!!.mutate().apply {
    setTint(Color.WHITE)
  }
  private val clipPath = Path()
  private val bounds = RectF()

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    // 3:4 のまま、与えられた幅と高さの両方に収まる大きさにする
    val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
    val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
    var width = maxWidth
    var height = (width / ASPECT_RATIO).toInt()
    if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && height > maxHeight) {
      height = maxHeight
      width = (height * ASPECT_RATIO).toInt()
    }
    setMeasuredDimension(width, height)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    bounds.set(0f, 0f, w.toFloat(), h.toFloat())
    clipPath.reset()
    clipPath.addRoundRect(bounds, dp(24f), dp(24f), Path.Direction.CW)
  }

  override fun onDraw(canvas: Canvas) {
    canvas.withClip(clipPath) {
      // 床
      drawRect(bounds, floorPaint)

      for (location in locations) {
        drawMarker(this, location, isCurrent = location.id == currentLocation?.id)
      }

      // プレイヤーは一番手前に描く
      displayedPosition?.let { drawPlayer(this, it) }
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    moveAnimator?.cancel()
  }

  /** 地点の目印（白い丸）と、その下に名前を描く */
  private fun drawMarker(canvas: Canvas, location: Location, isCurrent: Boolean) {
    val x = location.position.x.toFloat() * width
    val y = location.position.y.toFloat() * height
    canvas.drawCircle(x, y, dp(5f), markerPaint)

    labelPaint.color = if (isCurrent) CURRENT_COLOR else Color.WHITE
    // 現在地ではプレイヤーアイコンに隠れないよう、さらに下に出す
    val textTop = y + dp(if (isCurrent) 19f else 7f)
    canvas.drawText(location.name, x, textTop - labelPaint.ascent(), labelPaint)
  }

  private fun drawPlayer(canvas: Canvas, position: MapPosition) {
    val x = position.x.toFloat() * width
    val y = position.y.toFloat() * height
    val radius = dp(18f)
    canvas.drawCircle(x, y, radius, playerPaint)
    val iconHalf = (radius * 0.65f).toInt()
    playerIcon.setBounds(x.toInt() - iconHalf, y.toInt() - iconHalf, x.toInt() + iconHalf, y.toInt() + iconHalf)
    playerIcon.draw(canvas)
  }

  /** 描いている位置から新しい位置まで、少し時間をかけて動かす */
  private fun moveDisplayedPosition(to: MapPosition?) {
    moveAnimator?.cancel()
    val from = displayedPosition
    // 初めて見つかったときや、見失ったときは動かさずに切り替える
    if (from == null || to == null) {
      displayedPosition = to
      invalidate()
      return
    }
    moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = MOVE_DURATION_MS
      interpolator = AccelerateDecelerateInterpolator()
      addUpdateListener {
        val ratio = it.animatedValue as Float
        displayedPosition = MapPosition(
          x = from.x + (to.x - from.x) * ratio,
          y = from.y + (to.y - from.y) * ratio,
        )
        invalidate()
      }
      start()
    }
  }

  private fun dp(value: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

  private fun sp(value: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
