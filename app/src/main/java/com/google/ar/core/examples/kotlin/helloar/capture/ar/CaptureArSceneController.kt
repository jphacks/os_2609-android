package com.google.ar.core.examples.kotlin.helloar.capture.ar

import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.kotlin.helloar.model.MonsterAnimation
import com.google.ar.core.examples.kotlin.helloar.model.WildMonster
import com.google.ar.core.examples.kotlin.helloar.monster.MonsterAnimator
import com.google.ar.core.examples.kotlin.helloar.monster.loadMonsterNode
import com.google.ar.core.examples.kotlin.helloar.random
import com.google.ar.core.examples.kotlin.helloar.viewmodel.CaptureViewModel
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 捕獲画面の AR のシーンを操作する（iOS の ARSceneController）
 * 担当：床を選んでアンカーを置くこと、モンスターの配置、モンスターを歩かせること、
 * モンスターが画面の外にいるときに、その方向を知らせること、歩いた距離やタップなどシーンのイベントを ViewModel に伝えること
 *
 * onSessionUpdated は AR のフレームごとにメインスレッドで呼ばれる。モンスターの動きもここで毎フレーム進める
 *
 * @param scope モデルの読み込みに使う。Compose の rememberCoroutineScope（画面から外れると止まる）を渡す
 */
class CaptureArSceneController(
  private val viewModel: CaptureViewModel,
  private val engine: Engine,
  private val modelLoader: ModelLoader,
  private val cameraNode: ARCameraNode,
  private val scope: CoroutineScope,
) {
  /** モンスターを置く床のアンカー。CaptureArComponent がこれを AnchorNode にして、attach() に渡す */
  var floorAnchor by mutableStateOf<Anchor?>(null)
    private set
  /** モンスターが画面の外にいるときの、画面の真ん中から見たモンスターの方向（ラジアン。右が 0、下向きが正）。画面の中にいれば null */
  var arrowAngle by mutableStateOf<Float?>(null)
    private set
  /** AR の画面の大きさ（ピクセル）。タップした点から奥へ伸びる線を求めるのに使う */
  var viewportWidth = 1
  var viewportHeight = 1

  /** 床に置いたモンスター 1 体ぶんの、ノードと動き。位置は床のアンカーから見た座標 */
  private class PlacedMonster(
    val wild: WildMonster,
    /** 場所と向きを持つノード。歩かせるときは、これを動かす */
    val holder: Node,
    val model: ModelNode,
    val animator: MonsterAnimator,
    /** 出現した場所（x, z）。この周りだけを歩く */
    val homeX: Float,
    val homeZ: Float,
    var x: Float,
    var y: Float,
    var z: Float,
    /** 向き（Y 軸まわりの角度、ラジアン）。0 で +Z を向く */
    var yaw: Float,
  ) {
    /** 歩く速さ（m/秒） */
    val speed = WALK_SPEED_PER_HEIGHT * wild.monster.size

    var phase = Phase.REST
    var phaseStart = 0f
    var phaseDuration = 0f
    /** 今の動き（向きを変える・歩く）の始まりと終わり */
    var fromX = 0f
    var fromY = 0f
    var fromZ = 0f
    var toX = 0f
    var toY = 0f
    var toZ = 0f
    var fromYaw = 0f
    var toYaw = 0f

    /** 縮んで消え始めた時刻。消え始めていなければ null */
    var disappearStart: Float? = null
  }

  /** 「向きを変える → 歩く → 少し止まる」をくり返す */
  private enum class Phase { TURN, WALK, REST }

  private var anchorNode: AnchorNode? = null
  /** 床に置いたモンスター（縮んで消えている途中のものも含む） */
  private val monsters = mutableListOf<PlacedMonster>()
  /** モデルを読み込んでいる途中のモンスター。読み込み終わる前に、同じモンスターをもう一度置かないようにする */
  private val loadingIds = mutableSetOf<UUID>()
  /** 最後に歩いたとみなした、床の上での自分の位置（ワールド座標の x, z） */
  private var lastWalkPoint: Pair<Float, Float>? = null
  /** 最後のフレームのセッションと、カメラの位置と向き */
  private var session: Session? = null
  private var cameraPose = Pose.IDENTITY
  /** 最後のフレームのカメラの行列。タップした点から奥へ伸びる線を求めるのに使う */
  private val viewMatrix = FloatArray(16)
  private val projectionMatrix = FloatArray(16)
  private var isTracking = false
  /**
   * 画面に戻ってきたので、床とモンスターを置き直すか
   * 画面を離れている間に ARCore が位置を見失っていることがあるので、床を探すところからやり直す
   */
  private var needsFloorReset = false

  /** モンスターの動きに使う時刻（秒）。Float でも精度が落ちないよう、このコントローラーを作ったときから数える */
  private val clockStart = System.nanoTime()
  private fun now() = (System.nanoTime() - clockStart) / 1e9f

  /** 画面に戻ってきたときに呼ぶ */
  fun resume() {
    needsFloorReset = true
  }

  /** 画面を離れたときに呼ぶ。床が見えていない扱いにして、逃げるまでの時間を数えないようにする */
  fun pause() {
    viewModel.floorStateChanged(isFound = false)
  }

  /**
   * AR の画面が外れるとき（捕獲成功の画面に切り替える、画面を閉じる）に呼ぶ。アンカーを外し、床がなくなったことを伝える
   * ノードは ARSceneView がシーンごと片付けるので、ここでは消さない（片付けたあとに消すと落ちる）
   */
  fun stop() {
    floorAnchor?.detach()
    viewModel.floorStateChanged(isFound = false)
  }

  /** AR のフレームごとに呼ぶ */
  fun onSessionUpdated(session: Session, frame: Frame) {
    this.session = session
    val camera = frame.camera
    if (needsFloorReset) {
      needsFloorReset = false
      removeFloor()
    }
    isTracking = camera.trackingState == TrackingState.TRACKING
    if (!isTracking) {
      arrowAngle = null
      return
    }

    val now = now()
    cameraPose = camera.pose
    camera.getViewMatrix(viewMatrix, 0)
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    updateFloor(session)
    sync(now)
    measureWalk(camera)
    val anchor = floorAnchor
    if (anchor == null || anchor.trackingState != TrackingState.TRACKING) {
      arrowAngle = null
      return
    }
    monsters.filter { it.disappearStart == null }.forEach { updateWander(it, now) }
    updateDisappearing(now)
    updateArrow()
  }

  /** 床のアンカーの AnchorNode ができたら呼ぶ。モンスターはこの子として置く */
  fun attach(node: AnchorNode) {
    anchorNode = node
  }

  /**
   * まだ床のアンカーがなく、置ける床がもう見つかっていれば、そこにアンカーを置く
   * アンカーは平面に結び付けず、見つけたときの床の位置に固定する
   * 歩き回ると ARCore は平面をまとめて、元の平面を消すことがある。平面に結び付けると、そのたびにモンスターが消えてしまう
   * アンカーを ARCore が追跡できなくなったら（STOPPED）、床を探すところからやり直す
   */
  private fun updateFloor(session: Session) {
    val anchor = floorAnchor
    if (anchor != null) {
      if (anchor.trackingState == TrackingState.STOPPED) removeFloor()
      return
    }
    val floor = floorPlanes(session).minByOrNull { distanceToCamera(it.centerPose) } ?: return
    // 平面の向き（Y 軸まわりの回転）は平面ごとにばらばらなので、回転なしで置き、アンカーの座標の軸をワールドとそろえる
    val center = floor.centerPose
    floorAnchor = session.createAnchor(Pose.makeTranslation(center.tx(), center.ty(), center.tz()))
    // 出現しているモンスターがいれば、AnchorNode ができてからの sync で置かれる
    viewModel.floorStateChanged(isFound = true)
  }

  /** 見つかっている平面のうち、モンスターを置ける床 */
  private fun floorPlanes(session: Session): List<Plane> =
    session.getAllTrackables(Plane::class.java).filter(::canPlaceMonster)

  /**
   * モンスターを置ける平面か。置く場所の条件はここだけで決める
   * 机や棚の上も水平面なので、床に絞る（絞らないと、机の高さに置かれて宙に浮いて見える）
   */
  private fun canPlaceMonster(plane: Plane): Boolean =
    plane.trackingState == TrackingState.TRACKING &&
      // ほかの平面にまとめられた平面は、もう更新されない
      plane.subsumedBy == null &&
      plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
      plane.extentX >= MINIMUM_PLANE_EXTENT &&
      plane.extentZ >= MINIMUM_PLANE_EXTENT &&
      cameraPose.ty() - plane.centerPose.ty() >= MINIMUM_FLOOR_DEPTH_BELOW_CAMERA

  private fun distanceToCamera(pose: Pose) =
    hypot(hypot(pose.tx() - cameraPose.tx(), pose.ty() - cameraPose.ty()), pose.tz() - cameraPose.tz())

  /**
   * 床のアンカーから見た点（x, z）での、床の高さ（床のアンカーから見た y）。床の平面がなければ null
   * その点に一番近い床の平面の、今の高さを使う
   * 床のアンカーは最初に見つけた床の高さに固定しているが、ARCore は歩くうちに床の高さを直していく
   * アンカーの高さのまま置くと、最初の誤差や歩いたぶんのずれで、離れた場所では宙に浮いたり床に沈んだりする
   */
  private fun floorHeight(x: Float, z: Float): Float? {
    val anchorPose = floorAnchor?.pose ?: return null
    val session = session ?: return null
    val world = anchorPose.transformPoint(floatArrayOf(x, 0f, z))
    val plane = floorPlanes(session)
      .minByOrNull { hypot(it.centerPose.tx() - world[0], it.centerPose.tz() - world[2]) } ?: return null
    return anchorPose.inverse().transformPoint(floatArrayOf(world[0], plane.centerPose.ty(), world[2]))[1]
  }

  /**
   * 床のアンカーと、置いていたモンスターをすべて消す
   * ViewModel に床がなくなったことを伝えるので、次に床のアンカーを置いたときにモンスターも置き直される
   */
  private fun removeFloor() {
    for (monster in monsters) {
      anchorNode?.removeChildNode(monster.holder)
      monster.holder.destroy()
    }
    monsters.clear()
    loadingIds.clear()
    floorAnchor?.detach()
    floorAnchor = null
    anchorNode = null
    // 位置の基準が変わることがあるので、歩いた距離もそこから測り直す
    lastWalkPoint = null
    viewModel.floorStateChanged(isFound = false)
  }

  /** ViewModel の出現しているモンスターに合わせて、床のモンスターを置いたり消したりする（iOS の sync） */
  private fun sync(now: Float) {
    val anchorNode = anchorNode ?: return
    val shown = listOfNotNull(viewModel.state.value.wildMonster)
    val ids = shown.map { it.id }.toSet()

    // 一覧からいなくなったのは、捕まえられたか逃げたモンスター
    for (monster in monsters) {
      if (monster.disappearStart == null && monster.wild.id !in ids) monster.disappearStart = now
    }
    for (wild in shown) {
      if (wild.id !in loadingIds && monsters.none { it.wild.id == wild.id }) appear(wild, anchorNode)
    }
  }

  /** モデルを読み込んで、自分の前の方の床に置く。読み込んでいる間に床が置き直されたり、逃げたりしたら置かない */
  private fun appear(wild: WildMonster, anchorNode: AnchorNode) {
    loadingIds += wild.id
    scope.launch {
      val loaded = loadMonsterNode(engine, modelLoader, wild.monster, wild.monster.size)
      if (!loadingIds.remove(wild.id) || loaded == null) {
        loaded?.first?.destroy()
        return@launch
      }
      val (holder, model) = loaded
      val anchorPose = floorAnchor?.pose
      if (anchorPose == null || this@CaptureArSceneController.anchorNode !== anchorNode ||
        viewModel.state.value.wildMonster?.id != wild.id
      ) {
        holder.destroy()
        return@launch
      }
      val (homeX, homeZ) = randomSpawnPoint(anchorPose)
      val monster = PlacedMonster(
        wild = wild,
        holder = holder,
        model = model,
        animator = MonsterAnimator(model, scope).apply { loop(MonsterAnimation.IDLE) },
        homeX = homeX,
        homeZ = homeZ,
        x = homeX,
        // モデルは足元を原点にしているので、y を床の高さにすると床の上に立つ
        y = floorHeight(homeX, homeZ) ?: 0f,
        z = homeZ,
        yaw = (0f..(2 * PI).toFloat()).random(),
      )
      // 出てすぐは止まっていて、少ししてから歩き出す
      monster.phaseStart = now()
      monster.phaseDuration = REST_RANGE.random()
      place(monster)
      anchorNode.addChildNode(holder)
      monsters += monster
    }
  }

  /** モンスターの動きを 1 フレームぶん進める。今の動きが終わったら、次の動きを始める */
  private fun updateWander(monster: PlacedMonster, now: Float) {
    val progress =
      if (monster.phaseDuration > 0) ((now - monster.phaseStart) / monster.phaseDuration).coerceAtMost(1f) else 1f
    when (monster.phase) {
      Phase.TURN -> {
        monster.yaw = lerpAngle(monster.fromYaw, monster.toYaw, progress)
        if (progress >= 1) startWalk(monster, now)
      }
      Phase.WALK -> {
        monster.x = lerp(monster.fromX, monster.toX, progress)
        monster.y = lerp(monster.fromY, monster.toY, progress)
        monster.z = lerp(monster.fromZ, monster.toZ, progress)
        if (progress >= 1) startRest(monster, now)
      }
      Phase.REST -> if (progress >= 1) startTurn(monster, now)
    }
    place(monster)
  }

  /** 次に歩いていく点を決め、そちらを向く */
  private fun startTurn(monster: PlacedMonster, now: Float) {
    val (targetX, targetZ) = nextTarget(monster)
    monster.toX = targetX
    monster.toZ = targetZ
    monster.fromYaw = monster.yaw
    // モデルは +Z 方向が正面なので、+Z が進む方向を向くように Y 軸で回す
    monster.toYaw = atan2(targetX - monster.x, targetZ - monster.z)
    startPhase(monster, Phase.TURN, TURN_DURATION, now)
  }

  private fun startWalk(monster: PlacedMonster, now: Float) {
    monster.fromX = monster.x
    monster.fromY = monster.y
    monster.fromZ = monster.z
    // 歩くたびに、行き先の床の今の高さに合わせる（ARCore が床の高さを直していくため）
    monster.toY = floorHeight(monster.toX, monster.toZ) ?: monster.y
    val distance = hypot(monster.toX - monster.x, monster.toZ - monster.z)
    monster.animator.loop(MonsterAnimation.WALK)
    startPhase(monster, Phase.WALK, distance / monster.speed, now)
  }

  private fun startRest(monster: PlacedMonster, now: Float) {
    monster.animator.loop(MonsterAnimation.IDLE)
    startPhase(monster, Phase.REST, REST_RANGE.random(), now)
  }

  private fun startPhase(monster: PlacedMonster, phase: Phase, duration: Float, now: Float) {
    monster.phase = phase
    monster.phaseStart = now
    monster.phaseDuration = duration
  }

  /** 今の位置と向きを、ノードに反映する */
  private fun place(monster: PlacedMonster) {
    monster.holder.position = Position(monster.x, monster.y, monster.z)
    monster.holder.rotation = Rotation(y = Math.toDegrees(monster.yaw.toDouble()).toFloat())
  }

  /** 捕まえられたか逃げたモンスターを縮めていき、縮み終わったら取り除く */
  private fun updateDisappearing(now: Float) {
    val iterator = monsters.iterator()
    while (iterator.hasNext()) {
      val monster = iterator.next()
      val start = monster.disappearStart ?: continue
      val shrink = 1 - (now - start) / DISAPPEAR_DURATION
      if (shrink > 0) {
        monster.holder.scale = Scale(shrink)
      } else {
        anchorNode?.removeChildNode(monster.holder)
        monster.holder.destroy()
        iterator.remove()
      }
    }
  }

  /**
   * 床の上で自分が WALK_STEP 以上動いたら、その距離を ViewModel に伝える
   * 高さは無視する（しゃがんだり端末を上下に動かしたりしても、歩いたことにしない）
   */
  private fun measureWalk(camera: Camera) {
    val point = camera.pose.tx() to camera.pose.tz()
    val last = lastWalkPoint
    if (last == null) {
      lastWalkPoint = point
      return
    }
    val distance = hypot(point.first - last.first, point.second - last.second)
    if (distance < WALK_STEP) return
    lastWalkPoint = point
    viewModel.walked(distance)
  }

  /** タップしたときに呼ぶ。タップした所にモンスターがいたら、ViewModel に伝える */
  fun onTap(event: MotionEvent) {
    // 位置を見失っている間は、カメラの行列が古いので判定しない
    if (!isTracking) return
    // タップした点から画面の奥へ伸びる線（ワールド座標の、手前の点と奥の点）
    val viewProjection = FloatArray(16)
    Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)
    val inverse = FloatArray(16)
    if (!Matrix.invertM(inverse, 0, viewProjection, 0)) return
    val x = 2 * event.x / viewportWidth - 1
    val y = 1 - 2 * event.y / viewportHeight
    val near = unproject(inverse, x, y, -1f)
    val far = unproject(inverse, x, y, 1f)

    val id = monsters.firstOrNull { it.disappearStart == null && hits(it.model, near, far) }?.wild?.id ?: return
    viewModel.monsterTapped(id)
  }

  private fun unproject(inverseViewProjection: FloatArray, x: Float, y: Float, z: Float): Float3 {
    val point = FloatArray(4)
    Matrix.multiplyMV(point, 0, inverseViewProjection, 0, floatArrayOf(x, y, z, 1f), 0)
    return Float3(point[0] / point[3], point[1] / point[3], point[2] / point[3])
  }

  /**
   * near から far への線が、モンスターを囲む箱に当たるか
   * 細かい形に合わせると脚の隙間などで外れやすいので、iOS と同じくモデル全体を囲む箱で判定する
   * 線をモデルの座標に移してから、箱の各面との交わりを調べる
   */
  private fun hits(model: ModelNode, near: Float3, far: Float3): Boolean {
    val toModel = inverse(model.worldTransform)
    val origin = (toModel * Float4(near, 1f)).xyz
    val end = (toModel * Float4(far, 1f)).xyz
    val min = model.center - model.halfExtent
    val max = model.center + model.halfExtent
    var enter = 0f
    var exit = 1f
    for (axis in 0 until 3) {
      val direction = end[axis] - origin[axis]
      if (abs(direction) < 1e-6f) {
        if (origin[axis] < min[axis] || origin[axis] > max[axis]) return false
      } else {
        val t1 = (min[axis] - origin[axis]) / direction
        val t2 = (max[axis] - origin[axis]) / direction
        enter = maxOf(enter, minOf(t1, t2))
        exit = minOf(exit, maxOf(t1, t2))
        if (enter > exit) return false
      }
    }
    return true
  }

  /** モンスターが画面の外にいたら、その方向を arrowAngle に入れる */
  private fun updateArrow() {
    val monster = monsters.firstOrNull { it.disappearStart == null }
    if (monster == null) {
      arrowAngle = null
      return
    }
    val target = monster.holder.worldPosition + Float3(0f, ARROW_TARGET_HEIGHT, 0f)
    val view = cameraNode.worldToView(target)
    if (view != null && view.x in 0f..1f && view.y in 0f..1f) {
      arrowAngle = null
      return
    }
    // カメラから見たモンスターの場所（x が右、y が上）。カメラの後ろにいても、右後ろなら右、左後ろなら左を指す
    val local = cameraPose.inverse().transformPoint(floatArrayOf(target.x, target.y, target.z))
    arrowAngle = atan2(-local[1], local[0])
  }

  /**
   * モンスターが出現するランダムな点（床のアンカーから見た x, z）。自分の前の方の、少し離れた場所にする
   * 床のアンカーは最初に床が見つかった場所にあるので、歩いたあとは自分の位置から求める必要がある
   */
  private fun randomSpawnPoint(anchorPose: Pose): Pair<Float, Float> {
    val inverse = anchorPose.inverse()
    val position = inverse.transformPoint(cameraPose.translation)
    // カメラは -Z 方向を向いている
    val forward = inverse.rotateVector(cameraPose.zAxis.map { -it }.toFloatArray())
    val facing = atan2(forward[2], forward[0])
    val angle = facing + (-SPAWN_ANGLE_RANGE..SPAWN_ANGLE_RANGE).random()
    val distance = SPAWN_DISTANCE_RANGE.random()
    return (position[0] + distance * cos(angle)) to (position[2] + distance * sin(angle))
  }

  /** 今いる場所から少し離れた、次に歩いていく点 */
  private fun nextTarget(monster: PlacedMonster): Pair<Float, Float> {
    val step = STEP_RANGE.random()
    val angle = (0f..(2 * PI).toFloat()).random()
    val targetX = monster.x + step * cos(angle)
    val targetZ = monster.z + step * sin(angle)
    // 歩き回る範囲の外に出てしまうときは、出現した場所に向かって歩かせる
    if (hypot(targetX - monster.homeX, targetZ - monster.homeZ) > WANDER_RADIUS) {
      val toHome = hypot(monster.homeX - monster.x, monster.homeZ - monster.z)
      return (monster.x + (monster.homeX - monster.x) / toHome * step) to
        (monster.z + (monster.homeZ - monster.z) / toHome * step)
    }
    return targetX to targetZ
  }

  private fun lerp(from: Float, to: Float, ratio: Float) = from + (to - from) * ratio

  /** 角度を、近い向きに回って補間する（350° から 10° へは 20° だけ回す） */
  private fun lerpAngle(from: Float, to: Float, ratio: Float): Float {
    val fullTurn = (2 * PI).toFloat()
    val delta = ((to - from) % fullTurn + fullTurn * 1.5f) % fullTurn - fullTurn / 2
    return from + delta * ratio
  }

  private companion object {
    /** モンスターが出現する、自分からの距離（m）。近すぎると見つけにくいので、少し離れた場所に出す */
    val SPAWN_DISTANCE_RANGE = 1.5f..3f
    /** モンスターが出現する方向の、カメラの正面からのずれの最大（ラジアン）。出たことに気づけるよう、前の方に出す */
    const val SPAWN_ANGLE_RANGE = (PI / 4).toFloat()
    /**
     * これだけ動いたら歩いたとみなす距離（m）
     * その場で端末を動かしただけのぶれや、位置の追跡の小さな揺れを、歩いた距離に数えないようにする
     */
    const val WALK_STEP = 0.3f
    /** 出現した場所を中心に歩き回る範囲の半径（m） */
    const val WANDER_RADIUS = 1.5f
    /** 1 回に歩く距離（m） */
    val STEP_RANGE = 0.6f..1.4f
    /**
     * 歩く速さ。モンスターの高さ 1m あたりの速さ（m/秒）
     * 大きいモンスターほど歩幅が広いので、同じ歩くアニメーションでも速く進ませる（高さ 1.5m なら 0.6m/秒）
     */
    const val WALK_SPEED_PER_HEIGHT = 0.4f
    /** 向きを変えるのにかける時間（秒） */
    const val TURN_DURATION = 0.4f
    /** 歩き終わってから次に歩き出すまで止まっている時間（秒） */
    val REST_RANGE = 0.5f..2f
    /** 捕まえたときに縮んで消えるまでの時間（秒） */
    const val DISAPPEAR_DURATION = 0.3f
    /** 矢印が指す、モンスターの足元からの高さ（m）。体の真ん中あたりを指す */
    const val ARROW_TARGET_HEIGHT = 0.5f
    /** モンスターを置ける平面の、縦・横それぞれの最小の大きさ（m） */
    const val MINIMUM_PLANE_EXTENT = 0.2f
    /**
     * カメラよりこれだけ（m）低い平面だけを床とみなす
     * ARCore は平面の種類（床・机など）を判定しないので、高さで机や棚の上を外す
     * スマホを胸の高さ（床から 1.3〜1.5m）で持つ前提。机（約 0.7m）や椅子（約 0.45m）の面はこれより高いので外れる
     * しゃがんで使うと床を見逃しやすくなるので、そのときはこの値を小さくする
     */
    const val MINIMUM_FLOOR_DEPTH_BELOW_CAMERA = 1.0f
    /** タップの判定に使うカメラの、映す範囲の手前と奥（m） */
    const val Z_NEAR = 0.1f
    const val Z_FAR = 100f
  }
}
