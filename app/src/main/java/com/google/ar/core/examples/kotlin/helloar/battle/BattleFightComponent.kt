package com.google.ar.core.examples.kotlin.helloar.battle

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.ar.core.examples.kotlin.helloar.battle.ar.BattleArComponent
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster
import com.google.ar.core.examples.kotlin.helloar.model.battle.Fighter
import com.google.ar.core.examples.kotlin.helloar.model.battle.MonsterAnimation
import com.google.ar.core.examples.kotlin.helloar.model.battle.MonsterAnimationRequest
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 対戦中の画面（iOS の BattleFightComponent）。後ろの AR の床の上でモンスター同士が戦い、その上に HP を重ねる
 * 操作は 2 つだけ。画面をタップすると通常攻撃、左右にスワイプするとよける（必殺技とフィーバーは自動）
 * 当たったときの音と振動、フィーバーのチャンス・フィーバー・相手の必殺技の予告・勝利の演出もここで出す
 * 1 回の対戦の中だけで使う見た目の状態（流すアニメーション、続けて当てた回数など）はここに持つ
 * 対戦が終わるとこの部品ごとなくなるので、次の対戦には前の状態が残らない
 */
@Composable
fun BattleFightComponent(
  viewModel: BattleViewModel,
  me: Fighter,
  opponent: Fighter,
  myMonster: BattleMonster,
  opponentMonster: BattleMonster,
) {
  /** タップした位置の波紋。クールダウン中でも出るので、タップが届いているかがわかる */
  var tapRipple by remember { mutableStateOf<TapRipple?>(null) }
  /** 自分と相手のモンスターに流すアニメーション。どれを流すかは見た目の話なので、この画面で決める */
  var myAnimation by remember { mutableStateOf<MonsterAnimationRequest?>(null) }
  var opponentAnimation by remember { mutableStateOf<MonsterAnimationRequest?>(null) }
  /**
   * 必殺技の動きを流し終わる時刻（SystemClock.elapsedRealtime）。それまでは、そのモンスターに通常攻撃やのけぞりの動きを流さない
   * 流すと、必殺技の溜めや攻撃の動きが途中で切れてしまうため（倒れるときだけは流す）
   */
  var mySpecialEndsAt by remember { mutableLongStateOf(0L) }
  var opponentSpecialEndsAt by remember { mutableLongStateOf(0L) }
  /** 最後によけた・よけられた必殺技。「よけた！」「よけられた…」を出すのに使う */
  var lastDodgedHit by remember { mutableStateOf<BattleViewModel.Hit?>(null) }
  /** 最後に相手へ当たった必殺技かとどめ。画面を光らせるのに使う */
  var lastFlashHitOnOpponent by remember { mutableStateOf<BattleViewModel.Hit?>(null) }
  /** 続けて当てた回数。前に当ててから COMBO_TIMEOUT 秒以内に次を当てると増える */
  var comboCount by remember { mutableIntStateOf(0) }
  /** 決着が確定してから、結果を出し始めたか。勝ったときは「VICTORY!」を見せてから結果を出す */
  var isResultShown by remember { mutableStateOf(false) }
  /** 自分の受け取っていない必殺技の数の前の値。増えたら、自分が必殺技を撃った */
  var previousIncomingSpecials by remember { mutableIntStateOf(0) }
  val shake = remember { Animatable(0f) }
  val view = LocalView.current
  val scope = rememberCoroutineScope()
  val swipeDistance = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }

  /**
   * AR の中でモンスターが光るのと同じタイミングで、音を鳴らして振動させる
   * 次の攻撃で取り消さない（通常攻撃が必殺技の溜めの途中で当たっても、必殺技の音を鳴らす）
   */
  fun playImpact(hit: BattleViewModel.Hit) {
    val sound = when {
      hit.isFinishing -> BattleSoundPlayer.Sound.FinishHit
      hit.isSpecial && !hit.isDodged -> BattleSoundPlayer.Sound.SpecialHit
      hit.isFever -> BattleSoundPlayer.Sound.FeverHit
      else -> BattleSoundPlayer.Sound.Hit
    }
    scope.launch {
      delay(millis(hit.impactDelay))
      BattleSoundPlayer.play(sound)
      view.perform(
        when {
          (hit.isSpecial && !hit.isDodged) || hit.isFinishing -> BattleHaptic.HEAVY
          else -> BattleHaptic.RIGID
        },
      )
    }
  }

  // 相手に当たったら、自分が攻撃して相手がのけぞる。当てるたびに待ち直し、次を当てないまま時間が過ぎたら、続けて当てた回数を 0 に戻す
  // HP は引数の opponent ではなく viewModel から読む（引数は前に描いたときの値のことがある）
  LaunchedEffect(viewModel.lastHitOnOpponent) {
    val hit = viewModel.lastHitOnOpponent ?: return@LaunchedEffect
    val current = viewModel.opponent ?: return@LaunchedEffect
    val now = SystemClock.elapsedRealtime()
    if ((hit.isSpecial && !hit.isDodged) || hit.isFinishing) lastFlashHitOnOpponent = hit
    if (hit.isDodged) lastDodgedHit = hit
    if (hit.isSpecial) {
      mySpecialEndsAt = now + millis(myMonster.specialAttack.approximateDuration)
      myAnimation = attackAnimation(myMonster, hit)
    } else if (now >= mySpecialEndsAt) {
      myAnimation = attackAnimation(myMonster, hit)
    }
    // よけたときは、のけぞらずに横へかわす（かわす動きは BattleArSceneController が付ける）
    if (current.hp == 0 || (!hit.isDodged && now >= opponentSpecialEndsAt)) {
      opponentAnimation = reactionAnimation(current.hp, hit)
    }
    comboCount += 1
    playImpact(hit)
    delay(millis(COMBO_TIMEOUT))
    comboCount = 0
  }

  LaunchedEffect(viewModel.lastHitOnMe) {
    val hit = viewModel.lastHitOnMe ?: return@LaunchedEffect
    val current = viewModel.me ?: return@LaunchedEffect
    val now = SystemClock.elapsedRealtime()
    if (hit.isDodged) lastDodgedHit = hit
    if (hit.isSpecial) {
      opponentSpecialEndsAt = now + millis(opponentMonster.specialAttack.approximateDuration)
      opponentAnimation = attackAnimation(opponentMonster, hit)
    } else if (now >= opponentSpecialEndsAt) {
      opponentAnimation = attackAnimation(opponentMonster, hit)
    }
    // よけたときは、スワイプしたときにもうかわしているので、のけぞらない
    if (current.hp == 0 || (!hit.isDodged && now >= mySpecialEndsAt)) {
      myAnimation = reactionAnimation(current.hp, hit)
    }
    // 攻撃を受けたら、カメラの映像ごと揺らす
    for (x in listOf(-12f, 12f, -6f, 0f)) {
      shake.animateTo(x, tween(50))
    }
  }

  // 相手が必殺技を撃ったら、結果が決まるまで相手のモンスターが溜める
  LaunchedEffect(viewModel.incomingDanger) {
    if (viewModel.incomingDanger == null) return@LaunchedEffect
    BattleSoundPlayer.play(BattleSoundPlayer.Sound.Danger)
    view.perform(BattleHaptic.WARNING)
    opponentSpecialEndsAt = Long.MAX_VALUE
    opponentAnimation = SPECIAL_CHARGE
  }

  // 自分が必殺技を撃ったら（相手の受け取っていない必殺技が増えたら）、結果が届くまで自分のモンスターが溜める
  val incomingSpecials = viewModel.opponent?.incomingSpecials ?: 0
  LaunchedEffect(incomingSpecials) {
    if (incomingSpecials > previousIncomingSpecials) {
      mySpecialEndsAt = Long.MAX_VALUE
      myAnimation = SPECIAL_CHARGE
    }
    previousIncomingSpecials = incomingSpecials
  }

  // フィーバーが始まったときは、音を鳴らして成功の振動
  LaunchedEffect(viewModel.feverEndsAt != null) {
    if (viewModel.feverEndsAt == null) return@LaunchedEffect
    BattleSoundPlayer.play(BattleSoundPlayer.Sound.FeverStart)
    view.perform(BattleHaptic.SUCCESS)
  }
  // よけるためにスワイプしたら軽く振動させ、へとへとになったときと、よけられなかったときはエラーの振動で知らせる
  LaunchedEffect(viewModel.lastDodge) {
    if (viewModel.lastDodge != null) view.perform(BattleHaptic.LIGHT)
  }
  LaunchedEffect(viewModel.lastFailedDodge) {
    if (viewModel.lastFailedDodge != null) view.perform(BattleHaptic.ERROR)
  }
  val isResting = viewModel.isResting
  LaunchedEffect(isResting) {
    if (isResting) view.perform(BattleHaptic.ERROR)
  }

  // 決着が確定したら、勝った側が喜び、少し待ってから結果を出す
  LaunchedEffect(viewModel.outcome) {
    val outcome = viewModel.outcome ?: return@LaunchedEffect
    val current = viewModel.me ?: return@LaunchedEffect
    val currentOpponent = viewModel.opponent ?: return@LaunchedEffect
    val celebration = MonsterAnimationRequest(
      listOf(MonsterAnimation.YES, MonsterAnimation.WAVE), delay = CELEBRATION_DELAY, ending = MonsterAnimationRequest.Ending.REPEATS,
    )
    // 確定した勝敗が、この端末の HP と食い違うこともある（ほぼ同時に倒し合ったときなど）
    // そのときは負けた側がまだ倒れていないので、ここで倒す
    val defeat = MonsterAnimationRequest(listOf(MonsterAnimation.DEATH), ending = MonsterAnimationRequest.Ending.STAYS_AT_END)
    when (outcome) {
      BattleViewModel.Outcome.WON -> {
        BattleSoundPlayer.play(BattleSoundPlayer.Sound.Victory)
        view.perform(BattleHaptic.SUCCESS)
        myAnimation = celebration
        if (currentOpponent.hp > 0) opponentAnimation = defeat
      }
      BattleViewModel.Outcome.LOST -> {
        opponentAnimation = celebration
        if (current.hp > 0) myAnimation = defeat
      }
    }
    delay(millis(if (outcome == BattleViewModel.Outcome.WON) VICTORY_DURATION else DEFEAT_DURATION))
    isResultShown = true
  }

  Box(Modifier.fillMaxSize()) {
    // 後ろ：AR の床の上で戦うモンスター。攻撃を受けたら揺れる
    BattleArComponent(
      myMonster = myMonster,
      opponentMonster = opponentMonster,
      myIdle = if (isResting) MonsterAnimation.DUCK else idleAnimation(me),
      opponentIdle = idleAnimation(opponent),
      myAnimation = myAnimation,
      opponentAnimation = opponentAnimation,
      hitOnOpponent = viewModel.lastHitOnOpponent,
      hitOnMe = viewModel.lastHitOnMe,
      danger = viewModel.incomingDanger,
      myDodge = viewModel.lastDodge,
      isFeverActive = viewModel.feverEndsAt != null,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer { translationX = shake.value.dp.toPx() },
    )

    // 画面のどこをタップしても攻撃する。左右にスワイプするとよける
    // 指を動かしたらスワイプ、動かさずに離したらタップにする
    Box(
      Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var last = down.position
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              last = change.position
              if (!change.pressed) break
            }
            val move = last - down.position
            if (move.getDistance() < swipeDistance) {
              tapRipple = TapRipple(down.position)
              viewModel.attack()
            } else if (abs(move.x) > abs(move.y)) {
              viewModel.dodge(toRight = move.x > 0)
            }
          }
        },
    )
    tapRipple?.let { ripple ->
      key(ripple) {
        Box(Modifier.offset { IntOffset(ripple.location.x.toInt() - 22.dp.roundToPx(), ripple.location.y.toInt() - 22.dp.roundToPx()) }) {
          TapRippleComponent()
        }
      }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
      // 1. 上部：相手の名前と HP
      Box(Modifier.fillMaxWidth().battlePanel(BattleStyle.pink)) {
        StatBarComponent(opponent.name, opponent.hp, opponent.maxHP, BattleStyle.pink)
      }
      // 2. 中央：後ろの AR の床の上で戦うモンスターが見えるように空けておく。右上に、続けて当てた回数を出す
      // 下の方に、スタミナが減ってきたら「ハァハァ…」、へとへとで休憩している間は「へとへと…」と出す
      Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
        Box(Modifier.align(Alignment.TopEnd)) { ComboComponent(comboCount) }
        Box(Modifier.align(Alignment.BottomCenter)) {
          if (isResting) RestComponent() else BreathComponent(viewModel.stamina)
        }
      }
      // 3. 下部：自分の HP（スタミナと必殺技ゲージは、出さなくても遊べるので出さない）
      Box(Modifier.fillMaxWidth().battlePanel(BattleStyle.lime)) {
        StatBarComponent(me.name, me.hp, me.maxHP, BattleStyle.lime)
      }
    }

    // 画面全体に重ねる演出
    if (viewModel.feverEndsAt != null) FeverComponent()
    viewModel.incomingDanger?.let { danger ->
      key(danger) { DangerComponent(isDodgeFailed = viewModel.lastFailedDodge?.danger === danger) }
    }
    viewModel.chance?.let { chance ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(chance) { ChanceComponent(chance) }
      }
    }
    lastDodgedHit?.let { hit ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(hit) { DodgeComponent(isMine = hit === viewModel.lastHitOnMe) }
      }
    }
    // 必殺技やとどめが当たった瞬間、画面全体を光らせる。とどめは白く光らせる
    lastFlashHitOnOpponent?.let { hit ->
      key(hit) { FlashComponent(if (hit.isFinishing) Color.White else Color.Yellow, hit.impactDelay) }
    }
    // 勝ちが確定したら「VICTORY!」を出し、少し見せてから結果を全面に出す
    // 勝ったかどうかは、サーバーで確定した結果（outcome）だけで決める
    val outcome = viewModel.outcome
    AnimatedVisibility(outcome == BattleViewModel.Outcome.WON && !isResultShown, enter = fadeIn(), exit = fadeOut()) {
      VictoryComponent()
    }
    AnimatedVisibility(outcome != null && isResultShown, enter = fadeIn(), exit = fadeOut()) {
      outcome?.let { BattleResultComponent(it, opponent.name, onBack = viewModel::leaveBattle) }
    }
  }
}

private class TapRipple(val location: Offset)

/** 必殺技を撃ってから、相手が受けたかよけたかが決まるまでの溜めの動き。跳んで、空中で待つ */
private val SPECIAL_CHARGE = MonsterAnimationRequest(
  listOf(MonsterAnimation.JUMP, MonsterAnimation.JUMP_IDLE), ending = MonsterAnimationRequest.Ending.REPEATS,
)

/** 決着してから、勝った側が喜び始めるまでの時間（秒）。最後の攻撃の動きを見せてから喜ぶ */
private const val CELEBRATION_DELAY = 1.0
/** この時間（秒）次の攻撃を当てなかったら、続けて当てた回数を 0 に戻す */
private const val COMBO_TIMEOUT = 1.2
/** HP がこの割合より少なくなったら、立っている間の動きを弱った動きにする */
private const val WEAK_HP_RATIO = 0.2
/** 確定してから結果を出すまでの時間（秒）。勝ったときは「VICTORY!」を見せ、負けたときは倒れるところを見せる */
private const val VICTORY_DURATION = 3.5
private const val DEFEAT_DURATION = 1.2
/** この距離より指を動かしたら、タップではなくスワイプ（よける）にする */
private val SWIPE_DISTANCE = 30.dp

private fun millis(seconds: Double) = (seconds * 1000).toLong()

/**
 * 必殺技のアニメーションのおおよその長さ（秒）。この間は、通常攻撃の動きで必殺技の動きを切らない
 * 本当の長さはモデルを読み込むまでわからないので、全モンスター共通の長さ（iOS の MonsterAnimation の区間）を使う
 */
private val MonsterAnimation.approximateDuration: Double
  get() = when (this) {
    MonsterAnimation.WEAPON -> 0.875
    MonsterAnimation.JUMP -> 0.375
    MonsterAnimation.PUNCH -> 0.875
    else -> 1.0
  }

/** 立っている間にくり返すアニメーション。HP が少ないと、頭を抱えてしゃがむ弱った動きになる */
private fun idleAnimation(fighter: Fighter): MonsterAnimation =
  if (fighter.hp < fighter.maxHP * WEAK_HP_RATIO) MonsterAnimation.DUCK else MonsterAnimation.IDLE

/** 攻撃する側のアニメーション。必殺技の溜めは、撃ったときに流し始めている */
private fun attackAnimation(monster: BattleMonster, hit: BattleViewModel.Hit) =
  MonsterAnimationRequest(listOf(if (hit.isSpecial) monster.specialAttack else monster.normalAttack))

/**
 * 攻撃を受けた側のアニメーション。攻撃が当たるタイミングでのけぞり、HP が 0 になったら倒れたままになる
 * とどめのときは、当たった瞬間に少し止まってから（ヒットストップ）、ゆっくり倒れる
 */
private fun reactionAnimation(hp: Int, hit: BattleViewModel.Hit) =
  if (hp == 0) {
    MonsterAnimationRequest(
      listOf(MonsterAnimation.DEATH),
      delay = hit.impactDelay + BattleViewModel.Hit.HIT_STOP_DURATION,
      ending = MonsterAnimationRequest.Ending.STAYS_AT_END,
      speed = BattleViewModel.Hit.FINISHING_DEATH_SPEED,
    )
  } else {
    MonsterAnimationRequest(listOf(MonsterAnimation.HIT_REACT), delay = hit.impactDelay)
  }
