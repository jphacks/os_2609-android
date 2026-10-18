package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * HP などの棒グラフ。名前と数字を上に、斜めに切った棒を下に出す
 * 減ったときは、減る前の値を白く残し、少し待ってから追いつかせる（格闘ゲームのように、どれだけ減ったかがわかる）
 */
@Composable
fun StatBarComponent(title: String, value: Int, maxValue: Int, color: Color) {
  val ratio by animateFloatAsState(value.toFloat() / maxValue, tween(150), label = "value")
  /** 白く残す分の終わりの割合。減ったあと、少し待ってから value に追いつく */
  val trail = remember { Animatable(value.toFloat() / maxValue) }
  var previous by remember { mutableIntStateOf(value) }

  LaunchedEffect(value) {
    // 増えたときは白く残す分がないので、色の付いた棒と一緒に伸ばす（先に白い棒だけが伸びて見えないようにする）
    if (value > previous) trail.snapTo(value.toFloat() / maxValue)
    previous = value
    // 連打している間は待ち直すので、まとめて減る
    delay(TRAIL_DELAY_MILLIS)
    trail.animateTo(value.toFloat() / maxValue, tween(400, easing = FastOutSlowInEasing))
  }

  Column {
    Row(verticalAlignment = Alignment.Bottom) {
      Text(title, style = BattleStyle.display(17.sp), color = color)
      Spacer(Modifier.weight(1f))
      Text("$value", style = BattleStyle.display(17.sp), color = Color.White)
      Text(" / $maxValue", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
    }
    val shape = slantedRectangle(6.dp)
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(14.dp)
        .clip(shape)
        .border(1.5.dp, Color.White, shape),
    ) {
      drawRect(Color.Black)
      drawRect(Color.White, size = Size(size.width * maxOf(trail.value, ratio), size.height))
      drawRect(color, size = Size(size.width * ratio, size.height))
      // 目盛り
      for (index in 1 until SEGMENT_COUNT) {
        val x = size.width * index / SEGMENT_COUNT
        drawRect(Color.Black.copy(alpha = 0.5f), Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
      }
    }
  }
}

/** 減ってから、白く残した分を減らし始めるまでの時間（ミリ秒） */
private const val TRAIL_DELAY_MILLIS = 400L
/** 目盛りで区切る数 */
private const val SEGMENT_COUNT = 10
