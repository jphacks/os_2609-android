package com.google.ar.core.examples.kotlin.helloar.model.battle

import android.os.SystemClock

/**
 * スタミナ。通常攻撃とよけるたびに減り、時間がたつと回復する
 * 使い切ると、へとへとになって REST_DURATION 秒の間休憩する（攻撃もよけるのもできない）
 * 連打し続けると休憩で手が止まるので、スタミナを見ながらリズムよく攻撃するほうが強い
 * クールダウンと同じく、入力した端末だけで数える（相手には送らない）
 * 時刻は SystemClock.elapsedRealtime()（ミリ秒）で持つ。変えるときは新しい値を返す
 */
data class Stamina(
  /** 最後に増減したときの値と時刻。今の値は、ここから回復した分を足して求める */
  private val storedValue: Double = MAX,
  private val storedAt: Long = SystemClock.elapsedRealtime(),
  /** 休憩が終わる時刻。休憩していなければ null か、過ぎた時刻 */
  val restEndsAt: Long? = null,
) {
  fun isResting(now: Long): Boolean = restEndsAt?.let { now < it } ?: false

  /**
   * 休憩が終わったことを記録する。休憩が終わる時刻（restEndsAt）に呼ぶ
   * 時刻を過ぎれば isResting() は false になるが、値が変わらないと画面が描き直されないので、ここで変える
   */
  fun finishRest(): Stamina = copy(restEndsAt = null)

  /** now の時点の値。休憩中は、0 から満タンまで、休憩が終わるのに合わせて増えていく */
  fun value(now: Long): Double {
    if (restEndsAt != null && now < restEndsAt) {
      return MAX * (1 - (restEndsAt - now) / 1000.0 / REST_DURATION)
    }
    return minOf(storedValue + RECOVERY_PER_SECOND * (now - storedAt) / 1000.0, MAX)
  }

  /** 通常攻撃の分を使う。使い切ったら休憩を始める。休憩中でないことを確かめてから呼ぶ */
  fun spendForAttack(now: Long): Stamina {
    val remaining = value(now) - ATTACK_COST
    if (remaining <= 0) {
      val restEndsAt = now + (REST_DURATION * 1000).toLong()
      // 休憩が終わった時点で満タンになっているようにする
      return Stamina(storedValue = MAX, storedAt = restEndsAt, restEndsAt = restEndsAt)
    }
    return copy(storedValue = remaining, storedAt = now)
  }

  /** よける分を使う。休憩中や、足りないときは使えずに null を返す（よけるだけでは休憩にならない） */
  fun spendForDodge(now: Long): Stamina? {
    val value = value(now)
    if (isResting(now) || value < DODGE_COST) return null
    return copy(storedValue = value - DODGE_COST, storedAt = now)
  }

  companion object {
    const val MAX = 100.0
    /** 満タンから全力で連打すると（0.3 秒に 1 回）、8 回目でへとへとになる */
    const val ATTACK_COST = 20.0
    const val DODGE_COST = 20.0
    /** 1 秒に回復する量。0.8 秒に 1 回の攻撃なら、減らずに続けられる */
    const val RECOVERY_PER_SECOND = 25.0
    /**
     * へとへとになってから、また動けるようになるまでの時間（秒）。終わると満タンに戻る
     * へとへとになりやすいぶん、短めにしている
     */
    const val REST_DURATION = 2.5
  }
}
