package com.google.ar.core.examples.kotlin.helloar.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.examples.kotlin.helloar.battle.BattleButtonComponent
import com.google.ar.core.examples.kotlin.helloar.battle.BattleHaptic
import com.google.ar.core.examples.kotlin.helloar.battle.BattleStyle
import com.google.ar.core.examples.kotlin.helloar.battle.onBand
import com.google.ar.core.examples.kotlin.helloar.battle.perform
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.monster.MonsterModelComponent
import java.util.Locale
import kotlin.random.Random

/**
 * 捕獲成功の画面（iOS の MonsterDetailView）。モンスターをタップして捕まえたときに出す
 * 「GET!!」をたたきつけ、後ろで光の筋を回し、紙吹雪を降らせる（バトル画面と同じ、ビビッドでパキッとした見た目）
 * 3D モデル・名前・大きさを出す。3D は指で回せる
 */
@Composable
fun MonsterDetailComponent(monster: Monster, onClose: () -> Unit) {
  // 0 から 1 へ弾むように進む。文字をたたきつけ、モンスターを弾ませるのに使う
  val appear = remember { Animatable(0f) }
  val view = LocalView.current
  LaunchedEffect(Unit) {
    view.perform(BattleHaptic.SUCCESS)
    appear.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
  }
  val transition = rememberInfiniteTransition(label = "detail")
  val rayDegrees by transition.animateFloat(
    0f, 360f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "rays",
  )
  // 「GET!!」の帯の色を、ピンクと水色で点滅させる
  val blink by transition.animateFloat(
    0f, 1f, infiniteRepeatable(tween(250, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
  )

  Box(
    Modifier
      .fillMaxSize()
      .background(BattleStyle.ink)
      // 後ろの画面にタップが届かないよう、ここで受け止める
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
  ) {
    RaysComponent(rayDegrees, Modifier.graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) })
    ConfettiComponent()

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(16.dp),
    ) {
      Text(
        "GET!!",
        style = BattleStyle.display(88.sp),
        color = BattleStyle.yellow,
        modifier = Modifier
          .padding(top = 16.dp)
          .rotate(-6f)
          // 大きいところから、たたきつけるように縮める
          .scale(2.5f - 1.5f * appear.value)
          .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
          .onBand(if (blink > 0.5f) BattleStyle.cyan else BattleStyle.pink, slant = 28.dp),
      )
      Text("捕獲成功！", style = BattleStyle.display(28.sp), color = Color.White)

      // 小さいモンスターも大きく見えるよう、どれも同じ大きさにそろえる（大きさは下に文字で出す）
      // iOS はモンスターも弾ませるが、3D は別の画面（SurfaceView）に描くので、Compose の scale では大きさが変わらない。弾ませない
      MonsterModelComponent(monster, facingDegrees = 0f, modifier = Modifier.fillMaxWidth().weight(1f))

      Text(
        monster.name,
        style = BattleStyle.display(40.sp),
        color = Color.Black,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.onBand(BattleStyle.cyan),
      )
      Text("大きさ ${formatSize(monster.size)}m", style = BattleStyle.display(20.sp), color = BattleStyle.lime)
      Box(Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)) {
        BattleButtonComponent("つぎへ", onClick = onClose)
      }
    }
  }
}

/** 大きさを、小数点以下を 2 桁まで（いらない 0 は付けずに）出す。1.25 → 1.25、1.0 → 1 */
private fun formatSize(size: Float): String =
  String.format(Locale.ROOT, "%.2f", size).trimEnd('0').trimEnd('.')

/** 画面の真ん中から放射状に広がり、回り続ける光の筋 */
@Composable
private fun RaysComponent(degrees: Float, modifier: Modifier) {
  Canvas(modifier.fillMaxSize()) {
    val length = size.maxDimension
    val width = 40.dp.toPx()
    rotate(degrees) {
      repeat(RAY_COUNT) { index ->
        rotate(index * 360f / RAY_COUNT) {
          drawRect(
            color = RAY_COLORS[index % RAY_COLORS.size].copy(alpha = 0.35f),
            topLeft = Offset(center.x - width / 2, center.y - length),
            size = Size(width, length),
          )
        }
      }
    }
  }
}

/** 上から降り続ける、ビビッドな色の四角い紙吹雪 */
@Composable
private fun ConfettiComponent() {
  /** 紙吹雪 1 枚ぶん。位置や速さは作るときにランダムに決める */
  class Piece {
    val x = Random.nextFloat()
    val delay = Random.nextFloat() * 2
    val duration = 1.8f + Random.nextFloat() * 1.4f
    val spin = 180f + Random.nextFloat() * 540f
    val color = CONFETTI_COLORS.random()
  }
  val pieces = remember { List(40) { Piece() } }
  var time by remember { mutableLongStateOf(0L) }
  LaunchedEffect(Unit) {
    while (true) withFrameNanos { time = it }
  }

  Canvas(Modifier.fillMaxSize()) {
    val seconds = time / 1e9f
    val pieceSize = Size(8.dp.toPx(), 14.dp.toPx())
    val margin = 20.dp.toPx()
    for (piece in pieces) {
      // 落ちきったら上に戻って、くり返し降らせる
      val progress = ((seconds + piece.delay) % piece.duration) / piece.duration
      val position = Offset(piece.x * size.width, -margin + progress * (size.height + margin * 2))
      rotate(progress * piece.spin, pivot = position) {
        drawRect(piece.color, topLeft = position - Offset(pieceSize.width / 2, pieceSize.height / 2), size = pieceSize)
      }
    }
  }
}

/** 光の筋の本数 */
private const val RAY_COUNT = 16
private val RAY_COLORS = listOf(BattleStyle.yellow, BattleStyle.pink, BattleStyle.cyan)
private val CONFETTI_COLORS = listOf(BattleStyle.yellow, BattleStyle.pink, BattleStyle.cyan, BattleStyle.lime)
