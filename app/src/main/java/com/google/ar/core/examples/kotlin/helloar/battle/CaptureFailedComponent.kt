package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 野生のモンスターとのバトルで負けたときに、大きく「捕獲失敗…」を出す（iOS の CaptureFailedComponent。VictoryComponent の負けた版）
 * 左右から斜めの帯が飛び込んできて交差し、その上に文字がたたきつけられる
 * 少し見せたあと、BattleFightComponent が捕獲画面に戻す
 */
@Composable
fun CaptureFailedComponent(monsterName: String) {
  val shown = remember { Animatable(0f) }
  LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }

  Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
    // 交差する 2 本の帯。左と右から飛び込んでくる
    StripeComponent(BattleStyle.red, rotation = -12f, offsetX = { -500.dp.toPx() * (1 - shown.value) })
    StripeComponent(BattleStyle.ink, rotation = 8f, offsetX = { 500.dp.toPx() * (1 - shown.value) })

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .padding(horizontal = 16.dp)
        .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) },
    ) {
      Text(
        "捕獲失敗…",
        // 幅の狭い端末（360dp）でも、帯ごと 1 行に収まる大きさにする
        style = BattleStyle.display(56.sp),
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
          .graphicsLayer {
            // 大きいところから、たたきつけるように縮める
            val scale = 2.2f - 1.2f * shown.value
            scaleX = scale
            scaleY = scale
            rotationZ = -4f
          }
          .onBand(BattleStyle.red, slant = 24.dp),
      )
      Text(
        "${monsterName}に逃げられた！",
        style = BattleStyle.display(20.sp),
        color = Color.Black,
        maxLines = 1,
        modifier = Modifier.onBand(BattleStyle.cyan),
      )
    }
  }
}

/** 画面の幅より長い、黒い縁取りの帯。offsetX（px）だけ横にずらす */
@Composable
private fun StripeComponent(color: Color, rotation: Float, offsetX: Density.() -> Float) {
  Box(
    Modifier
      // 画面より長くして、斜めにしても端が見えないようにする
      .requiredSize(900.dp, 90.dp)
      .graphicsLayer {
        translationX = offsetX()
        rotationZ = rotation
        alpha = 0.9f
      }
      .background(color)
      .border(4.dp, Color.Black),
  )
}
