package com.google.ar.core.examples.kotlin.helloar.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 画面の外にいるモンスターの方向を、画面の端に出す矢印で示す（iOS の MonsterArrow）
 * カメラの映像の上に、画面いっぱいの大きさで重ねる。タップは受け取らず、後ろのモンスターのタップを邪魔しない
 * CaptureRenderer が GL スレッドから毎フレーム update を呼ぶ
 */
class MonsterArrowView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {
  companion object {
    /** 矢印を出す位置の、画面の端からの距離（dp）。上の案内に重ならないよう、少し内側に出す */
    private const val INSET_DP = 70f
    /** 矢印の三角形の高さ（dp） */
    private const val ARROW_SIZE_DP = 36f
  }

  private val density = resources.displayMetrics.density
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.rgb(255, 214, 10)
    // カメラの映像が明るくても見えるよう、影を付ける
    setShadowLayer(4 * density, 0f, 0f, Color.argb(150, 0, 0, 0))
  }
  /** 上向きの三角形。中心が (0, 0) */
  private val arrow = Path().apply {
    val half = ARROW_SIZE_DP * density / 2
    moveTo(0f, -half)
    lineTo(half, half)
    lineTo(-half, half)
    close()
  }

  // GL スレッドで書き、メインスレッドの onDraw で読む
  @Volatile private var arrowX = 0f
  @Volatile private var arrowY = 0f
  /** 矢印の向き（度）。0 で上向き */
  @Volatile private var arrowDegrees = 0f
  @Volatile private var isArrowShown = false

  /**
   * モンスターが画面に映るはずの点（ピクセル）に向けて矢印を出す。target が null なら隠す
   * isBehind はモンスターがカメラの後ろにいるか。後ろにいるときの点は、カメラの前に折り返して求めたもの
   * 画面の真ん中から target へ線を引き、画面の端（INSET_DP だけ内側）とぶつかる所に置く
   */
  fun update(target: Pair<Float, Float>?, isBehind: Boolean) {
    val shown = target != null && (isBehind || !isOnScreen(target))
    if (shown) {
      val centerX = width / 2f
      val centerY = height / 2f
      var dx = target!!.first - centerX
      var dy = target.second - centerY
      // ちょうど真後ろにいるときは、振り返るよう下を指す
      if (hypot(dx, dy) < 1) {
        dx = 0f
        dy = 1f
      }
      // 縦と横のうち、先に画面の端とぶつかる方に合わせる（0 で割ると無限大になり、もう一方が選ばれる）
      val inset = INSET_DP * density
      val scale = minOf((centerX - inset) / abs(dx), (centerY - inset) / abs(dy))
      arrowX = centerX + dx * scale
      arrowY = centerY + dy * scale
      // 矢印は上向きなので、右向き（0）から 90° 足す
      arrowDegrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90
    }
    if (shown || isArrowShown) {
      isArrowShown = shown
      postInvalidateOnAnimation()
    }
  }

  private fun isOnScreen(point: Pair<Float, Float>) =
    point.first in 0f..width.toFloat() && point.second in 0f..height.toFloat()

  override fun onDraw(canvas: Canvas) {
    if (!isArrowShown) return
    canvas.save()
    canvas.translate(arrowX, arrowY)
    canvas.rotate(arrowDegrees)
    canvas.drawPath(arrow, paint)
    canvas.restore()
  }
}
