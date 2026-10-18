package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.examples.kotlin.helloar.logic.FeverLottery
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import kotlinx.coroutines.delay

// 画面全体に重ねる大きな演出（フィーバー、相手の必殺技の予告、チャンス、勝利、結果）
// どれもタップを受け取らない（下の画面のタップで攻撃し続けられるようにする）。結果だけはボタンを置く

/** フィーバー中は、画面の縁を金色に光らせる。始まったときに「FEVER!」を出す */
@Composable
fun FeverComponent() {
  val scale = remember { Animatable(2.5f) }
  val opacity = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    opacity.snapTo(1f)
    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    delay(900)
    opacity.animateTo(0f, tween(250))
  }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    EdgeGlowComponent(BattleStyle.yellow, pulseMillis = 350)
    Text(
      "FEVER!",
      style = BattleStyle.display(96.sp),
      color = BattleStyle.yellow,
      modifier = Modifier
        .graphicsLayer {
          scaleX = scale.value
          scaleY = scale.value
          alpha = opacity.value
        }
        .onBand(BattleStyle.pink, slant = 24.dp),
    )
  }
}

/**
 * 相手の必殺技の予告（「DANGER!」）。この間に左右にスワイプするとよけられる
 * isDodgeFailed：スワイプしたが、休憩中やスタミナが足りなくてよけられなかったか
 */
@Composable
fun DangerComponent(isDodgeFailed: Boolean) {
  // 横から勢いよく入ってくる
  val slide = remember { Animatable(-400f) }
  LaunchedEffect(Unit) { slide.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    EdgeGlowComponent(BattleStyle.red, pulseMillis = 120)
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .graphicsLayer {
          translationX = slide.value.dp.toPx()
          rotationZ = -6f
        }
        .fillMaxWidth()
        .background(BattleStyle.red)
        .border(4.dp, BattleStyle.yellow)
        .padding(vertical = 12.dp),
    ) {
      Text("⚠ DANGER!", style = BattleStyle.display(64.sp), color = Color.White)
      Text(
        if (isDodgeFailed) "スタミナが足りなくて、よけられない！" else "左右にスワイプでよけろ！",
        style = BattleStyle.display(20.sp),
        color = Color.Black,
        modifier = Modifier.background(BattleStyle.yellow).padding(horizontal = 16.dp, vertical = 2.dp),
      )
    }
  }
}

/**
 * フィーバーのチャンス。帯の色が 青 → 緑 → 赤 → 金 と、決まった色まで 1 段ずつ上がる
 * 金まで上がればフィーバー、上がらなければ帯が砕ける
 * 色を上げる間隔は BattleViewModel.CHANCE_STEP_DURATION に合わせる
 */
@Composable
fun ChanceComponent(chance: FeverLottery.Chance) {
  var level by remember { mutableStateOf(FeverLottery.Level.BLUE) }
  var isBroken by remember { mutableStateOf(false) }
  val slide = remember { Animatable(-600f) }
  val bounce = remember { Animatable(1f) }
  val view = LocalView.current
  val stepMillis = (BattleViewModel.CHANCE_STEP_DURATION * 1000).toLong()

  LaunchedEffect(Unit) {
    slide.animateTo(0f, spring(dampingRatio = 0.7f))
  }
  LaunchedEffect(Unit) {
    BattleSoundPlayer.play(view.context, BattleSoundPlayer.Sound.ChanceStep(FeverLottery.Level.BLUE))
    for (next in FeverLottery.Level.entries.drop(1)) {
      if (next.ordinal > chance.finalLevel.ordinal) break
      delay(stepMillis)
      level = next
      BattleSoundPlayer.play(view.context, BattleSoundPlayer.Sound.ChanceStep(next))
      view.perform(if (next == FeverLottery.Level.GOLD) BattleHaptic.HEAVY else BattleHaptic.RIGID)
      // 色が上がるたびに、一瞬大きくなって弾む
      bounce.snapTo(1.12f)
      bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy))
    }
    delay(stepMillis)
    if (!chance.willWin) {
      isBroken = true
      BattleSoundPlayer.play(view.context, BattleSoundPlayer.Sound.ChanceBreak)
      view.perform(BattleHaptic.ERROR)
    }
  }
  // 砕けたら、少し回りながら落ちて消える
  val broken by animateFloatAsState(if (isBroken) 1f else 0f, tween(450), label = "break")

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer {
        translationX = slide.value.dp.toPx()
        rotationZ = -4f
        alpha = 1 - broken
      },
  ) {
    Text(
      "チャンス！",
      style = BattleStyle.display(56.sp),
      color = if (level == FeverLottery.Level.RED) Color.White else Color.Black,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .graphicsLayer {
          scaleX = bounce.value
          scaleY = bounce.value
          rotationZ = 10f * broken
          translationY = 40.dp.toPx() * broken
        }
        .fillMaxWidth()
        .background(level.color, slantedRectangle(40.dp))
        .padding(vertical = 14.dp),
    )
    // 今どの色まで上がったかを、4 つの四角で見せる
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for (step in FeverLottery.Level.entries) {
        Box(
          Modifier
            .size(width = 32.dp, height = 10.dp)
            .background(if (step.ordinal <= level.ordinal) step.color else Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White),
        )
      }
    }
  }
}

private val FeverLottery.Level.color: Color
  get() = when (this) {
    FeverLottery.Level.BLUE -> BattleStyle.cyan
    FeverLottery.Level.GREEN -> BattleStyle.lime
    FeverLottery.Level.RED -> BattleStyle.red
    FeverLottery.Level.GOLD -> BattleStyle.yellow
  }

/** 勝ちが確定したら出す「VICTORY!」。後ろで光の筋が回る */
@Composable
fun VictoryComponent() {
  val shown = remember { Animatable(0f) }
  LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
  val transition = rememberInfiniteTransition(label = "victory")
  val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "rays")

  Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
    // 光の筋
    Canvas(Modifier.size(520.dp).graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }) {
      rotate(rotation) {
        for (index in 0 until RAY_COUNT) {
          rotate(360f * index / RAY_COUNT) {
            drawRect(
              (if (index % 2 == 0) BattleStyle.yellow else BattleStyle.pink).copy(alpha = 0.7f),
              topLeft = Offset(center.x - 12.dp.toPx(), center.y - 260.dp.toPx()),
              size = Size(24.dp.toPx(), 260.dp.toPx()),
            )
          }
        }
      }
    }
    Text(
      "VICTORY!",
      style = BattleStyle.display(80.sp),
      color = BattleStyle.yellow,
      maxLines = 1,
      modifier = Modifier
        .graphicsLayer {
          val scale = 0.2f + 0.8f * shown.value
          scaleX = scale
          scaleY = scale
          alpha = shown.value.coerceIn(0f, 1f)
        }
        .onBand(BattleStyle.pink, slant = 24.dp),
    )
  }
}

/** 光の筋の本数 */
private const val RAY_COUNT = 12

/** 勝敗の結果。「ロビーに戻る」でロビーに戻る */
@Composable
fun BattleResultComponent(outcome: BattleViewModel.Outcome, opponentName: String, onBack: () -> Unit) {
  val isWon = outcome == BattleViewModel.Outcome.WON
  val accent = if (isWon) BattleStyle.yellow else BattleStyle.red
  val shape = slantedRectangle(24.dp)
  Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier
        .padding(horizontal = 24.dp)
        .hardShadow(shape, offset = 8.dp)
        .background(BattleStyle.ink, shape)
        .border(4.dp, accent, shape)
        .padding(32.dp),
    ) {
      Text(if (isWon) "👑" else "✖", fontSize = 56.sp, color = accent)
      Text(if (isWon) "勝利！" else "敗北…", style = BattleStyle.display(64.sp), color = accent)
      Text(
        if (isWon) "${opponentName}に勝った！" else "${opponentName}に負けてしまった…",
        color = Color.White,
        fontSize = 17.sp,
      )
      Box(Modifier.height(8.dp))
      BattleButtonComponent("ロビーに戻る", color = accent, onClick = onBack)
    }
  }
}
