package com.google.ar.core.examples.kotlin.helloar.battle

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.examples.kotlin.helloar.model.battle.Stamina
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// バトル画面の演出（タップの波紋、続けて当てた回数、画面の光、画面の縁の光、へとへと、よけた）と振動
// 効果音は BattleSoundPlayer が鳴らす
// フィーバー・相手の必殺技の予告・チャンス・勝利・結果は BattleBannerComponents にある
// 踏み込み、モンスターが光る演出、ダメージの数字は AR の中で見せる（BattleArSceneController）

/** 振動の種類（iOS の sensoryFeedback に近いものを選ぶ）。本体の設定で振動を切っているときは鳴らない */
enum class BattleHaptic { LIGHT, RIGID, HEAVY, SUCCESS, WARNING, ERROR }

fun View.perform(haptic: BattleHaptic) {
  val constant = when (haptic) {
    BattleHaptic.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
    BattleHaptic.RIGID -> HapticFeedbackConstants.KEYBOARD_TAP
    BattleHaptic.HEAVY, BattleHaptic.WARNING -> HapticFeedbackConstants.LONG_PRESS
    BattleHaptic.SUCCESS ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
    BattleHaptic.ERROR ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
  }
  performHapticFeedback(constant)
}

/** タップした位置に広がって消えるひし形。位置は親が決める */
@Composable
fun TapRippleComponent() {
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) { progress.animateTo(1f, tween(400)) }
  Canvas(Modifier.size(44.dp).graphicsLayer {
    val scale = 0.2f + 0.8f * progress.value
    scaleX = scale
    scaleY = scale
    alpha = 1 - progress.value
  }) {
    rotate(45f) {
      drawRect(BattleStyle.yellow, style = Stroke(3.dp.toPx()))
    }
  }
}

/** 続けて当てた回数（「5 HIT!」）。2 回目から出し、回数が増えるほど文字が大きく、10 回からはピンクになる */
@Composable
fun ComboComponent(count: Int) {
  if (count < 2) return
  // 回数が増えるたびに、一瞬大きくなって弾む
  val scale = remember { Animatable(1f) }
  LaunchedEffect(count) {
    scale.snapTo(1.3f)
    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
  }
  Text(
    "$count HIT!",
    style = BattleStyle.display((32 + minOf(count, 20)).sp),
    color = if (count >= 10) BattleStyle.pink else BattleStyle.yellow,
    // 画面の右端に置くので、右端を基準に大きくする（真ん中を基準にすると、はみ出す）
    modifier = Modifier
      .graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        transformOrigin = TransformOrigin(1f, 0.5f)
      }
      .onBand(Color.Black),
  )
}

/**
 * 画面全体が一瞬光って消える（必殺技ととどめの演出）
 * delay 秒待ってから光る。必殺技の溜めの動きが終わって、攻撃が当たるタイミングに合わせる
 */
@Composable
fun FlashComponent(color: Color, delaySeconds: Double) {
  val opacity = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    delay((delaySeconds * 1000).toLong())
    opacity.animateTo(0.6f, tween(50, easing = LinearEasing))
    opacity.animateTo(0f, tween(500))
  }
  Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) {
    Canvas(Modifier.fillMaxSize()) { drawRect(color) }
  }
}

/** 画面の縁が光る（フィーバー中は金色、相手の必殺技の予告では赤）。明るくなったり暗くなったりをくり返す */
@Composable
fun EdgeGlowComponent(color: Color, pulseMillis: Int) {
  val transition = rememberInfiniteTransition(label = "edge")
  val brightness by transition.animateFloat(
    initialValue = 0.35f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(pulseMillis), RepeatMode.Reverse),
    label = "brightness",
  )
  Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = brightness }) {
    // 内側ほど薄くなる線を重ねて、ぼかしたように見せる
    val width = 28.dp.toPx()
    for (step in 0 until 6) {
      val inset = width * step / 6
      drawRect(
        color.copy(alpha = 0.5f * (1 - step / 6f)),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(width / 6),
      )
    }
    drawRect(color.copy(alpha = 0.8f), style = Stroke(4.dp.toPx()))
  }
}

/** へとへとで休憩している間に出す。上から落ちてきて、ふらふら揺れる */
@Composable
fun RestComponent() {
  val drop = remember { Animatable(-40f) }
  LaunchedEffect(Unit) { drop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
  val transition = rememberInfiniteTransition(label = "rest")
  val tilt by transition.animateFloat(3f, -3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "tilt")
  val shape = slantedRectangle(16.dp)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .graphicsLayer {
        translationY = drop.value.dp.toPx()
        rotationZ = tilt
      }
      .border(3.dp, Color.Black, shape)
      .onBand(BattleStyle.red),
  ) {
    Text("へとへと…", style = BattleStyle.display(40.sp), color = Color.White)
    Text("連打しすぎ！ひと休み", style = BattleStyle.display(16.sp), color = Color.White)
  }
}

/** スタミナが減ってきたら「ハァハァ…」と出す。休憩する前に、連打をゆるめるきっかけにする */
@Composable
fun BreathComponent(stamina: Stamina) {
  // スタミナは時間とともに回復するので、0.1 秒ごとに見直す
  val now by produceState(SystemClock.elapsedRealtime()) {
    while (isActive) {
      delay(100)
      value = SystemClock.elapsedRealtime()
    }
  }
  if (stamina.value(now) < Stamina.MAX * BREATH_THRESHOLD) {
    Text("ハァハァ…", style = BattleStyle.display(24.sp), color = Color.Black, modifier = Modifier.onBand(BattleStyle.yellow))
  }
}

/** スタミナがこの割合より少なくなったら「ハァハァ…」を出す */
private const val BREATH_THRESHOLD = 0.35

/** 必殺技をよけた（自分）・よけられた（相手）ときに出す文字 */
@Composable
fun DodgeComponent(isMine: Boolean) {
  val scale = remember { Animatable(0.4f) }
  val opacity = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    opacity.snapTo(1f)
    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    delay(900)
    opacity.animateTo(0f, tween(200))
  }
  Text(
    if (isMine) "よけた！" else "よけられた…",
    style = BattleStyle.display(56.sp),
    color = if (isMine) Color.Black else Color.White,
    modifier = Modifier
      .graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        alpha = opacity.value
      }
      .onBand(if (isMine) BattleStyle.lime else Color.Gray),
  )
}
