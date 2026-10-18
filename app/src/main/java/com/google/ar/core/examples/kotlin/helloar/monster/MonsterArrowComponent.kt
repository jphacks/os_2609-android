package com.google.ar.core.examples.kotlin.helloar.monster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ar.core.examples.kotlin.helloar.battle.BattleStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 画面の外にいるモンスターの方向を、画面の端の矢印で示す（iOS の MonsterArrow）。捕獲画面とバトル画面で使う
 * angle は画面の真ん中から見たモンスターの方向（ラジアン。右が 0、下向きが正）
 * 上や下の表示（案内や HP）に重ならないよう、端から inset だけ内側に出す
 */
@Composable
fun MonsterArrowComponent(angle: Float, inset: Dp) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val halfWidth = maxWidth.value / 2 - inset.value
    val halfHeight = maxHeight.value / 2 - inset.value
    // 真ん中から angle の方向へ伸ばした線が、内側の四角にぶつかる所に置く
    val dx = cos(angle)
    val dy = sin(angle)
    val t = minOf(halfWidth / maxOf(abs(dx), 0.001f), halfHeight / maxOf(abs(dy), 0.001f))
    Canvas(
      Modifier
        .align(Alignment.Center)
        .offset((dx * t).dp, (dy * t).dp)
        .size(44.dp),
    ) {
      rotate(Math.toDegrees(angle.toDouble()).toFloat()) {
        val path = Path().apply {
          moveTo(size.width, size.height / 2)
          lineTo(0f, 0f)
          lineTo(size.width * 0.25f, size.height / 2)
          lineTo(0f, size.height)
          close()
        }
        drawPath(path, BattleStyle.yellow)
      }
    }
  }
}
