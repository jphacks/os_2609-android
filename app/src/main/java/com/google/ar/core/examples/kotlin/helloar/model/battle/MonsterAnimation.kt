package com.google.ar.core.examples.kotlin.helloar.model.battle

/**
 * モンスターのモデルに入っているアニメーション（全モンスター共通の 14 種類）
 * glb にはアニメーションが名前付きで別々に入っているので、名前（clipName）で選んで流す
 * （iOS の USDZ は 1 本につないで入れているので区間で切り出すが、Android ではその必要がない）
 * 流すのは MonsterAnimator（battle/ar）が担当する
 */
enum class MonsterAnimation(val clipName: String) {
  /** 倒れる */
  DEATH("Death"),
  /** しゃがむ */
  DUCK("Duck"),
  /** 攻撃を受けてのけぞる */
  HIT_REACT("HitReact"),
  /** 立ったまま体を揺らす */
  IDLE("Idle"),
  /** 跳ぶ */
  JUMP("Jump"),
  /** 空中にいる */
  JUMP_IDLE("Jump_Idle"),
  /** 着地する */
  JUMP_LAND("Jump_Land"),
  /** 首を横に振る */
  NO("No"),
  /** パンチする */
  PUNCH("Punch"),
  /** 走る */
  RUN("Run"),
  /** 歩く */
  WALK("Walk"),
  /** 手を振る */
  WAVE("Wave"),
  /** 武器を振る */
  WEAPON("Weapon"),
  /** うなずく */
  YES("Yes");

  companion object {
    /** 前のアニメーションから切り替えるのにかける時間（秒）。この間は前のアニメーションと混ぜて、ポーズが急に飛ばないようにする */
    const val TRANSITION_DURATION = 0.2
  }
}

/** どのアニメーションをどう流すか。バトル画面（BattleFightComponent）が決め、BattleArSceneController がそのとおりに流す */
class MonsterAnimationRequest(
  /** 順に 1 回ずつ流すアニメーション（必殺技の溜め → 攻撃など） */
  val animations: List<MonsterAnimation>,
  /** 流し始めるまでの待ち時間（秒）。攻撃の動きが当たるタイミングに、のけぞりを合わせるときに使う */
  val delay: Double = 0.0,
  val ending: Ending = Ending.BACK_TO_LOOP,
  /** 流す速さ。1 で普通の速さ、0.5 で半分の速さ（とどめを刺されて倒れるときのスローモーションなど） */
  val speed: Double = 1.0,
) {
  /** 最後のアニメーションを流し終わったあと、どうするか */
  enum class Ending {
    /** くり返していたアニメーション（立っているなど）に戻る */
    BACK_TO_LOOP,
    /** 最後のポーズのまま止まる（倒れたとき） */
    STAYS_AT_END,
    /** 最後のアニメーションをくり返す（勝って喜び続けるとき） */
    REPEATS,
  }

  /** 待ち時間をなくした同じ流し方。モンスターを置く前に届いていた分を、置いたときに流すのに使う */
  fun withoutDelay() = MonsterAnimationRequest(animations, 0.0, ending, speed)
}
