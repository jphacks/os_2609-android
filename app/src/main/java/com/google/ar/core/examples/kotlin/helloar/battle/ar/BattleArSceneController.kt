package com.google.ar.core.examples.kotlin.helloar.battle.ar

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster
import com.google.ar.core.examples.kotlin.helloar.model.battle.MonsterAnimation
import com.google.ar.core.examples.kotlin.helloar.model.battle.MonsterAnimationRequest
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.TextNode
import kotlin.math.atan2
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * バトル画面の AR のシーンを操作する（iOS の BattleARSceneController と同じ役割）
 * 担当：床が見つかったら自分と相手のモンスターを置くこと、アニメーション（立っている間の動きも）を流すこと、
 * 攻撃したときに踏み込ませること、よけるときに横にかわさせること、攻撃が当たったら受けた側を光らせてダメージの数字を出すこと、
 * フィーバー中に自分のモンスターを金色に光らせること、相手の必殺技の予告で相手を赤く光らせること、
 * 相手のモンスターが画面の外にいるときに、その方向を知らせること
 * iOS の火花と金色の光の粒は、SceneView に粒を出す仕組みがないので、モンスターを光らせる演出で代わりにしている
 *
 * @param scope アニメーションを動かすのに使う。Compose の rememberCoroutineScope（毎フレームの時計を持っている）を渡す
 */
class BattleArSceneController(
  private val myMonster: BattleMonster,
  private val opponentMonster: BattleMonster,
  private val engine: Engine,
  private val modelLoader: ModelLoader,
  private val materialLoader: MaterialLoader,
  private val cameraNode: ARCameraNode,
  private val scope: CoroutineScope,
) {
  /** 床が見つかったら、その上に置くアンカー。BattleArComponent がこれを AnchorNode にして、attach() に渡す */
  var floorAnchor by mutableStateOf<Anchor?>(null)
    private set
  /** 2 体とも床に置き終わったか。置くまでは「床を映して」と案内する */
  var isPlaced by mutableStateOf(false)
    private set
  /** 相手のモンスターが画面の外にいるときの、画面の真ん中から見た相手の方向（ラジアン。右が 0、下向きが正）。画面の中にいれば null */
  var opponentArrowAngle by mutableStateOf<Float?>(null)
    private set

  private var anchorNode: AnchorNode? = null
  /** 最後のフレームのカメラの位置と向き。モンスターを置く向きを決めるのに使う */
  private var cameraPose: Pose? = null
  /** モンスターを置く場所と向きを持つノード。踏み込みやよける動きは、これを動かす */
  private var myHolder: Node? = null
  private var opponentHolder: Node? = null
  private var myModel: ModelNode? = null
  private var opponentModel: ModelNode? = null
  private var myAnimator: MonsterAnimator? = null
  private var opponentAnimator: MonsterAnimator? = null
  /**
   * 置いた場所（床のアンカーから見た位置）。踏み込んだあとはここに戻る
   * 踏み込んでいる途中の場所から次の踏み込みを始めると、少しずつずれていくため、置いたときの値を持っておく
   */
  private var myHome = Position(0f)
  private var opponentHome = Position(0f)
  /** 踏み込みやよける動きのジョブ。新しい動きを始めたら、前の動きは止める */
  private val moveJobs = mutableMapOf<Node, Job>()
  /** モンスターを光らせるジョブ。新しく光らせたら、前の光は止める */
  private val glowJobs = mutableMapOf<ModelNode, Job>()

  /** 最後に受け取ったアニメーションや出来事。変わったときだけ流す（モンスターを置く前に届いた分も、ここに残しておく） */
  private var myAnimation: MonsterAnimationRequest? = null
  private var opponentAnimation: MonsterAnimationRequest? = null
  private var hitOnOpponent: BattleViewModel.Hit? = null
  private var hitOnMe: BattleViewModel.Hit? = null
  private var danger: BattleViewModel.Danger? = null
  private var myDodge: BattleViewModel.Dodge? = null
  private var isFeverActive = false
  /** 立っている間にくり返すアニメーション。置く前に受け取った分は、置いたときに流す */
  private var myIdle = MonsterAnimation.IDLE
  private var opponentIdle = MonsterAnimation.IDLE

  /** 床に置いたときのモンスターの高さ（m）。ダメージの数字を頭の上に出すのに使う */
  private val myHeight: Float
    get() = myMonster.size * MY_SIZE_SCALE
  private val opponentHeight: Float
    get() = opponentMonster.size * OPPONENT_SIZE_SCALE

  /** AR のフレームごとに呼ぶ。床を探し、相手のモンスターの方向を求める */
  fun onSessionUpdated(session: Session, frame: Frame) {
    if (frame.camera.trackingState != TrackingState.TRACKING) return
    cameraPose = frame.camera.pose
    if (floorAnchor == null) {
      findFloor(session, frame)
    }
    updateArrow(frame)
  }

  /** 床のアンカーの AnchorNode ができたら呼ぶ。モデルを読み込んで、床に置く */
  fun attach(node: AnchorNode) {
    if (anchorNode != null) return
    anchorNode = node
    scope.launch { placeMonsters(node) }
  }

  /**
   * 画面の状態に合わせて、アニメーションを流したり踏み込ませたりする
   * 前と同じ出来事なら何もしない（画面は関係のない状態が変わったときも描き直されるため）
   */
  fun update(
    myIdle: MonsterAnimation,
    opponentIdle: MonsterAnimation,
    myAnimation: MonsterAnimationRequest?,
    opponentAnimation: MonsterAnimationRequest?,
    hitOnOpponent: BattleViewModel.Hit?,
    hitOnMe: BattleViewModel.Hit?,
    danger: BattleViewModel.Danger?,
    myDodge: BattleViewModel.Dodge?,
    isFeverActive: Boolean,
  ) {
    if (myIdle != this.myIdle) {
      this.myIdle = myIdle
      myAnimator?.changeLoop(myIdle)
    }
    if (opponentIdle != this.opponentIdle) {
      this.opponentIdle = opponentIdle
      opponentAnimator?.changeLoop(opponentIdle)
    }
    if (myAnimation != null && myAnimation !== this.myAnimation) {
      this.myAnimation = myAnimation
      myAnimator?.play(myAnimation)
    }
    if (opponentAnimation != null && opponentAnimation !== this.opponentAnimation) {
      this.opponentAnimation = opponentAnimation
      opponentAnimator?.play(opponentAnimation)
    }
    if (myDodge != null && myDodge !== this.myDodge) {
      this.myDodge = myDodge
      sidestep(myHolder, myHome, opponentHome, myDodge.toRight)
    }
    if (hitOnOpponent != null && hitOnOpponent !== this.hitOnOpponent) {
      this.hitOnOpponent = hitOnOpponent
      lunge(myHolder, myHome, opponentHome)
      // 相手によけられたら、当たるタイミングで相手が横にかわす
      if (hitOnOpponent.isDodged) {
        sidestep(
          opponentHolder, opponentHome, myHome, Random.nextBoolean(),
          delaySeconds = hitOnOpponent.impactDelay - SIDESTEP_DURATION,
        )
      }
      val color = when {
        hitOnOpponent.isFinishing || hitOnOpponent.isFever -> GOLD
        hitOnOpponent.isSpecial -> Color.YELLOW
        else -> ORANGE
      }
      showImpact(hitOnOpponent, myAnimator, opponentAnimator, opponentModel, opponentHome, opponentHeight, color)
    }
    if (hitOnMe != null && hitOnMe !== this.hitOnMe) {
      this.hitOnMe = hitOnMe
      lunge(opponentHolder, opponentHome, myHome)
      showImpact(hitOnMe, opponentAnimator, myAnimator, myModel, myHome, myHeight, PURPLE)
    }
    if (danger != null && danger !== this.danger) {
      this.danger = danger
      blinkWarning()
    }
    if (isFeverActive != this.isFeverActive) {
      this.isFeverActive = isFeverActive
      myModel?.let { setEmissive(it, baseEmissive(it)) }
    }
  }

  /** 画面を閉じたときに呼ぶ。アンカーを外す */
  fun stop() {
    floorAnchor?.detach()
  }

  /**
   * カメラより下にある、20cm 四方以上の水平な面を床とみなし、一番低いものを選ぶ（机の上には置かない）
   * 見つかったら、カメラの真下の床にアンカーを置く
   */
  private fun findFloor(session: Session, frame: Frame) {
    val cameraPose = frame.camera.pose
    val floor = session.getAllTrackables(Plane::class.java)
      .filter {
        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
          it.trackingState == TrackingState.TRACKING &&
          it.subsumedBy == null &&
          it.extentX >= MINIMUM_FLOOR_SIZE && it.extentZ >= MINIMUM_FLOOR_SIZE &&
          it.centerPose.ty() < cameraPose.ty() - MINIMUM_FLOOR_DEPTH
      }
      .minByOrNull { it.centerPose.ty() } ?: return
    floorAnchor = session.createAnchor(Pose.makeTranslation(cameraPose.tx(), floor.centerPose.ty(), cameraPose.tz()))
  }

  /** カメラの前の床に、自分のモンスターを手前、相手のモンスターを奥に置き、向かい合わせる */
  private suspend fun placeMonsters(anchorNode: AnchorNode) {
    // 置く場所は、置くときのカメラの向きで決める。カメラは -Z 方向を向いているので、その向きを床と平行にしたものを「前」にする
    // 真下を向いていると前がわからないので、そのときはワールドの -Z を前にする
    val z = cameraPose?.zAxis ?: floatArrayOf(0f, 0f, 1f)
    val flat = Float3(-z[0], 0f, -z[2])
    val forward = if (flat.x * flat.x + flat.z * flat.z > 0.01f) normalize(flat) else Float3(0f, 0f, -1f)
    val right = Float3(-forward.z, 0f, forward.x)
    myHome = forward * MY_DISTANCE - right * SIDE_OFFSET
    opponentHome = forward * OPPONENT_DISTANCE + right * SIDE_OFFSET

    // モデルの読み込みは時間がかかるので、読み込み終わってから床に置く
    val me = loadMonster(myMonster, myHeight) ?: return
    val opponent = loadMonster(opponentMonster, opponentHeight) ?: return
    for ((holder, home, target) in listOf(Triple(me.first, myHome, opponentHome), Triple(opponent.first, opponentHome, myHome))) {
      holder.position = home
      // モデルは +Z 方向が正面なので、+Z が相手の方を向くように Y 軸で回す
      val direction = target - home
      holder.rotation = Rotation(y = Math.toDegrees(atan2(direction.x, direction.z).toDouble()).toFloat())
      anchorNode.addChildNode(holder)
    }
    myHolder = me.first
    myModel = me.second
    opponentHolder = opponent.first
    opponentModel = opponent.second
    myAnimator = MonsterAnimator(me.second, scope).apply { loop(myIdle) }
    opponentAnimator = MonsterAnimator(opponent.second, scope).apply { loop(opponentIdle) }
    setEmissive(me.second, baseEmissive(me.second))
    // 置く前に、最後に残る動き（倒れたまま、喜び続ける）が届いていたら流す
    // 床が見つかる前に決着したときに、立った姿にならないようにする
    // のけぞりなど 1 回で終わる動きは、もう終わっているはずなので流さない
    for ((animator, animation) in listOf(myAnimator to myAnimation, opponentAnimator to opponentAnimation)) {
      if (animation == null || animation.ending == MonsterAnimationRequest.Ending.BACK_TO_LOOP) continue
      animator?.play(animation.withoutDelay())
    }
    isPlaced = true
  }

  /**
   * モンスターの 3D モデル（glb）を読み込み、高さを合わせる。読み込めなければ null
   * 返すのは、場所と向きを持つノードと、その中のモデル
   */
  private suspend fun loadMonster(monster: BattleMonster, height: Float): Pair<Node, ModelNode>? {
    val instance = modelLoader.loadModelInstance(monster.modelAssetPath) ?: return null
    val model = ModelNode(instance, autoAnimate = false)
    // モデルは 3〜4m の大きさで作られているので、指定の高さになるように縮小する
    // 腕を横に広げた姿勢で作られていて横幅の方が大きいので、一番長い辺ではなく高さで合わせる
    val modelHeight = model.extents.y
    val scale = if (modelHeight > 0) height / modelHeight else 1f
    model.scale = Scale(scale)
    // 足元が床に乗るように、モデルの一番下を高さ 0 に合わせる
    model.position = Position(0f, -(model.center.y - model.halfExtent.y) * scale, 0f)
    val holder = Node(engine)
    holder.addChildNode(model)
    return holder to model
  }

  /** 相手のモンスターが画面の外にいたら、その方向を opponentArrowAngle に入れる */
  private fun updateArrow(frame: Frame) {
    val holder = opponentHolder ?: return
    val target = holder.worldPosition + Float3(0f, opponentHeight / 2, 0f)
    val view = cameraNode.worldToView(target)
    if (view != null && view.x in 0f..1f && view.y in 0f..1f) {
      opponentArrowAngle = null
      return
    }
    // カメラから見た相手の場所（x が右、y が上）。カメラの後ろにいても、方向がわかる
    val local = frame.camera.pose.inverse().transformPoint(floatArrayOf(target.x, target.y, target.z))
    opponentArrowAngle = atan2(-local[1], local[0])
  }

  /** 相手の方へ少し踏み込んで、元の場所に戻る（攻撃する演出） */
  private fun lunge(holder: Node?, home: Position, target: Position) {
    holder ?: return
    val stepped = home + normalize(target - home) * LUNGE_DISTANCE
    move(holder) {
      animate(LUNGE_DURATION, ::easeOut) { holder.position = lerp(home, stepped, it) }
      animate(LUNGE_DURATION * 2, ::easeInOut) { holder.position = lerp(stepped, home, it) }
    }
  }

  /**
   * delay 秒待ってから、横に素早く動き、少しして元の場所に戻る（よける演出）
   * toRight は、モンスターから見た右（手前にいる自分のモンスターなら、カメラから見た右とほぼ同じ）
   */
  private fun sidestep(holder: Node?, home: Position, target: Position, toRight: Boolean, delaySeconds: Double = 0.0) {
    holder ?: return
    // 相手の方向を床の上で 90 度回すと、モンスターから見た右になる
    val forward = normalize(target - home)
    val right = Float3(-forward.z, 0f, forward.x)
    val stepped = home + right * (if (toRight) SIDESTEP_DISTANCE else -SIDESTEP_DISTANCE)
    move(holder) {
      delay(millis(delaySeconds))
      animate(SIDESTEP_DURATION, ::easeOut) { holder.position = lerp(home, stepped, it) }
      delay(millis(SIDESTEP_DURATION + SIDESTEP_HOLD))
      animate(SIDESTEP_DURATION * 2, ::easeInOut) { holder.position = lerp(stepped, home, it) }
    }
  }

  private fun move(holder: Node, block: suspend () -> Unit) {
    moveJobs[holder]?.cancel()
    moveJobs[holder] = scope.launch { block() }
  }

  /**
   * 攻撃が当たるタイミングで、受けた側を一瞬光らせ、頭の上にダメージの数字を出す
   * とどめのときは、2 体とも少しの間止めて（ヒットストップ）、光る時間を延ばす
   */
  private fun showImpact(
    hit: BattleViewModel.Hit,
    attacker: MonsterAnimator?,
    target: MonsterAnimator?,
    model: ModelNode?,
    home: Position,
    height: Float,
    color: Int,
  ) {
    // モンスターを置く前に当たった攻撃は、何も出さない
    model ?: return
    scope.launch {
      delay(millis(hit.impactDelay))
      if (hit.isFinishing) {
        attacker?.freeze(BattleViewModel.Hit.HIT_STOP_DURATION)
        target?.freeze(BattleViewModel.Hit.HIT_STOP_DURATION)
      }
      flash(model, if (hit.isFinishing) FINISHING_FLASH_DURATION else FLASH_DURATION)
      showDamage(hit, home, height, color)
    }
  }

  /** モンスターを一瞬白く光らせてから、元に戻す */
  private fun flash(model: ModelNode, duration: Double) {
    glow(model) {
      setEmissive(model, Float3(FLASH_EMISSIVE))
      delay(millis(duration))
    }
  }

  /** 相手の必殺技の予告の間、相手を赤く点滅させる */
  private fun blinkWarning() {
    val model = opponentModel ?: return
    val interval = millis(BattleViewModel.DANGER_DURATION / (WARNING_BLINK_COUNT * 2))
    glow(model) {
      repeat(WARNING_BLINK_COUNT) {
        setEmissive(model, Float3(FLASH_EMISSIVE, 0f, 0f))
        delay(interval)
        setEmissive(model, baseEmissive(model))
        delay(interval)
      }
    }
  }

  /** 光らせて、終わったら元の光り方（フィーバー中なら金色）に戻す */
  private fun glow(model: ModelNode, block: suspend () -> Unit) {
    glowJobs[model]?.cancel()
    glowJobs[model] = scope.launch {
      try {
        block()
      } finally {
        setEmissive(model, baseEmissive(model))
      }
    }
  }

  /** ふだんの光り方。フィーバー中の自分のモンスターだけ、金色に光る */
  private fun baseEmissive(model: ModelNode): Float3 =
    if (model === myModel && isFeverActive) Float3(FEVER_EMISSIVE, FEVER_EMISSIVE * 0.8f, FEVER_EMISSIVE * 0.2f) else Float3(0f)

  /**
   * モデルの材質が自分で光る色を変える。光で照らすのではなく、材質そのものを明るくする
   * 材質に emissiveFactor がない（glTF の材質でない）ときは何もしない（ないものを書き換えると落ちるため）
   */
  private fun setEmissive(model: ModelNode, color: Float3) {
    for (material in model.modelInstance.materialInstances) {
      if (material.material.hasParameter("emissiveFactor")) {
        material.setParameter("emissiveFactor", color.x, color.y, color.z)
      }
    }
  }

  /** 受けた側の頭の上にダメージの数字を出す。弾むように大きくなってから、浮かび上がりながら小さくなって消える */
  private fun showDamage(hit: BattleViewModel.Hit, home: Position, height: Float, color: Int) {
    val anchorNode = anchorNode ?: return
    val textHeight = when {
      hit.isFinishing -> FINISHING_DAMAGE_SIZE
      hit.isSpecial -> SPECIAL_DAMAGE_SIZE
      hit.isFever -> FEVER_DAMAGE_SIZE
      else -> DAMAGE_SIZE
    }
    // 文字の画像は 512 × 128 なので、4：1 の大きさで出す
    val text = TextNode(
      materialLoader = materialLoader,
      text = "-${hit.damage}",
      fontSize = 110f,
      textColor = color,
      backgroundColor = Color.TRANSPARENT,
      widthMeters = textHeight * 4,
      heightMeters = textHeight,
      // いつもカメラの方を向くようにする
      cameraPositionProvider = { cameraNode.worldPosition },
    )
    // 連打しても数字が重ならないよう、毎回少し左右と上にずらす
    val scatter = DAMAGE_SCATTER
    val start = home + Float3(Random.nextFloat() * scatter * 2 - scatter, height + 0.15f + Random.nextFloat() * scatter, 0f)
    text.position = start
    text.scale = Scale(0.3f)
    anchorNode.addChildNode(text)
    scope.launch {
      try {
        // 小さい大きさから、少し大きくなりすぎてから戻る（弾む）
        animate(DAMAGE_POP_DURATION, ::easeOutBack) { text.scale = Scale(0.3f + 0.7f * it) }
        animate(DAMAGE_DURATION - DAMAGE_POP_DURATION, ::easeOut) {
          text.position = start + Float3(0f, DAMAGE_RISE * it, 0f)
          text.scale = Scale(1f - 0.7f * it * it)
        }
      } finally {
        anchorNode.removeChildNode(text)
        text.destroy()
      }
    }
  }

  /** duration 秒かけて、毎フレーム 0 から 1 まで進む値で block を呼ぶ */
  private suspend fun animate(duration: Double, easing: (Float) -> Float, block: (Float) -> Unit) {
    val start = withFrameNanos { it }
    while (true) {
      val now = withFrameNanos { it }
      val t = ((now - start) / 1e9 / duration).toFloat().coerceIn(0f, 1f)
      block(easing(t))
      if (t >= 1f) return
    }
  }

  private companion object {
    /**
     * モンスターの大きさ。現実の大きさ（BattleMonster.size）に掛ける
     * 手前の自分のモンスターは、現実の大きさのままだと画面からはみ出すので小さくする
     * 相手は、自分より大きくして見やすくする
     */
    const val MY_SIZE_SCALE = 0.5f
    const val OPPONENT_SIZE_SCALE = 0.8f
    /** 床が見つかったときのカメラから見た、自分と相手のモンスターまでの距離（m） */
    const val MY_DISTANCE = 1.1f
    const val OPPONENT_DISTANCE = 2.5f
    /** 正面から左右にずらす距離（m）。自分は左、相手は右に置き、斜めに向かい合わせる */
    const val SIDE_OFFSET = 0.35f
    /** 床とみなす面の大きさの最小（m）と、カメラから床までの高さの最小（m）。これより高い面は机などとみなす */
    const val MINIMUM_FLOOR_SIZE = 0.2f
    const val MINIMUM_FLOOR_DEPTH = 0.5f
    /** 攻撃するときに相手の方へ踏み込む距離（m）と、踏み込むのにかける時間（秒） */
    const val LUNGE_DISTANCE = 0.25f
    const val LUNGE_DURATION = 0.12
    /** よけるときに横へ動く距離（m）と、動くのにかける時間（秒）、横にいる時間（秒） */
    const val SIDESTEP_DISTANCE = 0.3f
    const val SIDESTEP_DURATION = 0.1
    const val SIDESTEP_HOLD = 0.3
    /** ダメージの数字の文字の高さ（m）。通常攻撃、フィーバー、必殺技、とどめ */
    const val DAMAGE_SIZE = 0.18f
    const val FEVER_DAMAGE_SIZE = 0.26f
    const val SPECIAL_DAMAGE_SIZE = 0.32f
    const val FINISHING_DAMAGE_SIZE = 0.42f
    /** ダメージの数字が浮かび上がる高さ（m）と、消えるまでの時間（秒） */
    const val DAMAGE_RISE = 0.3f
    const val DAMAGE_DURATION = 0.8
    /** 連打しても数字が重なりすぎないよう、出す場所を左右と上にずらす幅（m） */
    const val DAMAGE_SCATTER = 0.15f
    /** ダメージの数字が出たときに、小さい大きさから弾んで大きくなるのにかける時間（秒） */
    const val DAMAGE_POP_DURATION = 0.15
    /** 攻撃が当たったときに、受けた側を光らせている時間（秒） */
    const val FLASH_DURATION = 0.08
    const val FINISHING_FLASH_DURATION = 0.25
    /**
     * 光らせるときの明るさ（材質の emissiveFactor）。Filament はカメラの露出に合わせて暗くするので、1 より大きくする
     * 明るさが足りない・まぶしすぎるときは、ここを変える
     */
    const val FLASH_EMISSIVE = 6f
    const val FEVER_EMISSIVE = 1.5f
    /** 相手の必殺技の予告で、相手が赤く点滅する回数 */
    const val WARNING_BLINK_COUNT = 4
    /** ダメージの数字の色 */
    val GOLD = Color.rgb(255, 204, 51)
    val ORANGE = Color.rgb(255, 149, 0)
    val PURPLE = Color.rgb(175, 82, 222)

    fun millis(seconds: Double) = (seconds.coerceAtLeast(0.0) * 1000).toLong()
    fun lerp(from: Position, to: Position, t: Float) = from + (to - from) * t
    fun easeOut(t: Float) = 1 - (1 - t) * (1 - t)
    fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) / 2
    /** 少し行きすぎてから戻る */
    fun easeOutBack(t: Float): Float {
      val c = 1.70158f
      val u = t - 1
      return 1 + (c + 1) * u * u * u + c * u * u
    }
  }
}
