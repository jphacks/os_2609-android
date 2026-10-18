package com.google.ar.core.examples.kotlin.helloar.monster

import com.google.ar.core.examples.kotlin.helloar.model.MonsterAnimation
import com.google.ar.core.examples.kotlin.helloar.model.MonsterAnimationRequest
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * モンスターのアニメーションを切り替える（iOS の MonsterAnimator と同じ使い方）
 *
 *     animator.loop(MonsterAnimation.WALK)       // くり返し流す（歩く、立っているなど）
 *     animator.playOnce(MonsterAnimation.PUNCH)  // 1 回だけ流して、くり返していたアニメーションに戻る
 *     animator.play(request)                     // MonsterAnimationRequest のとおりに流す（待ってから、続けて流すなど）
 *     animator.changeLoop(MonsterAnimation.DUCK) // くり返すアニメーションを変える（1 回だけ流している途中なら、終わってから）
 *     animator.freeze(0.2)                       // 今のポーズのまま少しの間止める（攻撃が当たった瞬間の「ヒットストップ」）
 *
 * SceneView の playAnimation は混ぜずに切り替わるので使わず、毎フレーム自分でポーズを決める（Filament の Animator を直接使う）
 * 切り替えるときは前のアニメーションと少しの間混ぜて、ポーズが急に飛ばないようにする
 */
class MonsterAnimator(private val node: ModelNode, private val scope: CoroutineScope) {
  /** 流している 1 本のアニメーション。時刻は System.nanoTime（毎フレームの時刻と同じ基準） */
  private class Clip(val index: Int, var startNanos: Long, val speed: Double, val repeats: Boolean)

  private val animator = node.animator
  /** アニメーションの名前（Idle など）から、glb の中での番号を引く */
  private val indices = (0 until animator.animationCount).associateBy { animator.getAnimationName(it) }
  private var current: Clip? = null
  /** 切り替える前のアニメーションの番号と、切り替えたときのポーズの時刻。混ぜている間だけある */
  private var previous: Pair<Int, Float>? = null
  private var switchedAt = 0L
  /** 止めている間の時刻と、止めるのをやめる時刻。止めていなければ null */
  private var frozenAt: Long? = null
  private var frozenUntil = 0L

  /** くり返し流しているアニメーション。1 回だけ流すアニメーションが終わったら、これに戻る */
  private var looping = MonsterAnimation.IDLE
  /** 1 回だけ流すアニメーションの途中か、最後のポーズで止まっている */
  private var isPlayingOnce = false
  private var returnJob: Job? = null
  /** play() で、流し始めるのを待っているジョブ */
  private var requestJob: Job? = null

  init {
    // 毎フレーム、今のポーズを決める
    node.onFrame = ::onFrame
  }

  /** 1 回流すのにかかる時間（秒）。モデルにそのアニメーションがなければ 0 */
  fun duration(animation: MonsterAnimation): Double =
    indices[animation.clipName]?.let { animator.getAnimationDuration(it).toDouble() } ?: 0.0

  /**
   * MonsterAnimationRequest のとおりにアニメーションを流す
   * 流している途中で次の play() が呼ばれたら、前の分の残りは流さない
   */
  fun play(request: MonsterAnimationRequest) {
    requestJob?.cancel()
    requestJob = scope.launch {
      delay(seconds(request.delay))
      // 途中のアニメーションは終わりのポーズで止めておき、混ぜ始める時間になったら次を流す
      for (animation in request.animations.dropLast(1)) {
        playOnce(animation, staysAtEnd = true, speed = request.speed)
        delay(seconds((duration(animation) - MonsterAnimation.TRANSITION_DURATION) / request.speed))
      }
      val last = request.animations.lastOrNull() ?: return@launch
      when (request.ending) {
        MonsterAnimationRequest.Ending.BACK_TO_LOOP -> playOnce(last, speed = request.speed)
        MonsterAnimationRequest.Ending.STAYS_AT_END -> playOnce(last, staysAtEnd = true, speed = request.speed)
        MonsterAnimationRequest.Ending.REPEATS -> loop(last)
      }
    }
  }

  /**
   * くり返すアニメーションを変える（HP が減って弱ったときなど）
   * loop() と違い、1 回だけ流している途中ならそれを止めず、終わって戻るときに切り替わる
   * 最後のポーズで止まっている（倒れている）間は切り替えない
   */
  fun changeLoop(animation: MonsterAnimation) {
    looping = animation
    if (isPlayingOnce) return
    start(animation, repeats = true)
  }

  /** アニメーションをくり返し流す */
  fun loop(animation: MonsterAnimation) {
    looping = animation
    isPlayingOnce = false
    returnJob?.cancel()
    returnJob = null
    start(animation, repeats = true)
  }

  /**
   * アニメーションを 1 回だけ流す。終わったら、くり返していたアニメーションに戻る
   * staysAtEnd を true にすると、戻らずに最後のポーズのまま止まる（倒れる DEATH など）
   * speed で流す速さを変えられる（1 で普通の速さ）
   */
  fun playOnce(animation: MonsterAnimation, staysAtEnd: Boolean = false, speed: Double = 1.0) {
    isPlayingOnce = true
    returnJob?.cancel()
    returnJob = null
    start(animation, repeats = false, speed = speed)
    if (staysAtEnd) return
    returnJob = scope.launch {
      // 戻るときも混ぜるので、その時間だけ早めに切り替え始める
      delay(seconds((duration(animation) - MonsterAnimation.TRANSITION_DURATION) / speed))
      isPlayingOnce = false
      start(looping, repeats = true)
    }
  }

  /**
   * 今のポーズのまま、duration 秒だけ止める。止めている間に次のアニメーションが始まったら、そちらを流す
   * 1 回だけ流すアニメーションの終わる時間は延びないので、短い時間だけ使う
   */
  fun freeze(duration: Double) {
    val now = System.nanoTime()
    if (frozenAt == null) frozenAt = now
    frozenUntil = now + (duration * 1e9).toLong()
  }

  private fun start(animation: MonsterAnimation, repeats: Boolean, speed: Double = 1.0) {
    val index = indices[animation.clipName] ?: return
    val now = System.nanoTime()
    unfreeze(now)
    current?.let { previous = it.index to time(it, now) }
    switchedAt = now
    current = Clip(index, now, speed, repeats)
  }

  private fun onFrame(frameTimeNanos: Long) {
    val clip = current ?: return
    var now = frameTimeNanos
    val frozenAt = frozenAt
    if (frozenAt != null) {
      if (now < frozenUntil) now = frozenAt else unfreeze(now)
    }
    animator.applyAnimation(clip.index, time(clip, now))
    previous?.let { (index, time) ->
      val alpha = (now - switchedAt) / 1e9 / MonsterAnimation.TRANSITION_DURATION
      if (alpha < 1) {
        animator.applyCrossFade(index, time, alpha.coerceAtLeast(0.0).toFloat())
      } else {
        previous = null
      }
    }
    animator.updateBoneMatrices()
  }

  /** 止めていたら、止めていた分だけ時刻を進めて、止める前の続きから流す */
  private fun unfreeze(now: Long) {
    val frozenAt = frozenAt ?: return
    val paused = minOf(now, frozenUntil) - frozenAt
    current?.let { it.startNanos += paused }
    switchedAt += paused
    this.frozenAt = null
  }

  /** clip の now の時点のポーズの時刻（秒）。くり返すなら最初に戻り、1 回だけなら最後で止まる */
  private fun time(clip: Clip, now: Long): Float {
    val duration = animator.getAnimationDuration(clip.index)
    val elapsed = ((now - clip.startNanos) / 1e9 * clip.speed).toFloat().coerceAtLeast(0f)
    return if (clip.repeats) elapsed % duration else minOf(elapsed, duration)
  }

  private fun seconds(value: Double) = (value.coerceAtLeast(0.0) * 1000).toLong()
}
