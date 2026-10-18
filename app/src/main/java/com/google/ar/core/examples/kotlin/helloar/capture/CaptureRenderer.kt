package com.google.ar.core.examples.kotlin.helloar.capture

import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TapHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.kotlin.helloar.model.WildMonster
import com.google.ar.core.examples.kotlin.helloar.monster.MonsterModel
import com.google.ar.core.examples.kotlin.helloar.random
import com.google.ar.core.examples.kotlin.helloar.viewmodel.CaptureViewModel
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.IOException
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 捕獲画面の AR を描き、シーンを操作する（iOS の ARSceneController）
 * 担当：床を選んでアンカーを置くこと、モンスターの配置、モンスターを歩かせること、
 * モンスターが画面の外にいるときに、その方向を矢印で示すこと、歩いた距離やタップなどシーンのイベントを ViewModel に伝えること
 *
 * onDrawFrame は GL スレッドで毎フレーム呼ばれる。モンスターの動きもここで毎フレーム進める
 * ViewModel を呼ぶときは、runOnUiThread でメインスレッドに戻してから呼ぶ
 */
class CaptureRenderer(
  private val activity: CaptureActivity,
  private val viewModel: CaptureViewModel,
  private val tapHelper: TapHelper,
  private val arrowView: MonsterArrowView,
) : SampleRender.Renderer, DefaultLifecycleObserver {
  companion object {
    private const val TAG = "CaptureRenderer"

    /** モンスターが出現する、自分からの距離（m）。近すぎると見つけにくいので、少し離れた場所に出す */
    private val SPAWN_DISTANCE_RANGE = 1.5f..3f
    /** モンスターが出現する方向の、カメラの正面からのずれの最大（ラジアン）。出たことに気づけるよう、前の方に出す */
    private const val SPAWN_ANGLE_RANGE = (PI / 4).toFloat()
    /**
     * これだけ動いたら歩いたとみなす距離（m）
     * その場で端末を動かしただけのぶれや、位置の追跡の小さな揺れを、歩いた距離に数えないようにする
     */
    private const val WALK_STEP = 0.3f
    /** 出現した場所を中心に歩き回る範囲の半径（m） */
    private const val WANDER_RADIUS = 1.5f
    /** 1 回に歩く距離（m） */
    private val STEP_RANGE = 0.6f..1.4f
    /**
     * 歩く速さ。モンスターの高さ 1m あたりの速さ（m/秒）
     * 大きいモンスターほど歩幅が広いので、同じ歩くアニメーションでも速く進ませる（高さ 1.5m なら 0.6m/秒）
     */
    private const val WALK_SPEED_PER_HEIGHT = 0.4f
    /** 向きを変えるのにかける時間（秒） */
    private const val TURN_DURATION = 0.4f
    /** 歩き終わってから次に歩き出すまで止まっている時間（秒） */
    private val REST_RANGE = 0.5f..2f
    /** 捕まえたときに縮んで消えるまでの時間（秒） */
    private const val DISAPPEAR_DURATION = 0.3f
    /** 矢印が指す、モンスターの足元からの高さ（m）。体の真ん中あたりを指す */
    private const val ARROW_TARGET_HEIGHT = 0.5f
    /** モンスターを置ける平面の、縦・横それぞれの最小の大きさ（m） */
    private const val MINIMUM_PLANE_EXTENT = 0.2f
    /**
     * カメラよりこれだけ（m）低い平面だけを床とみなす
     * ARCore は平面の種類（床・机など）を判定しないので、高さで机や棚の上を外す
     * スマホを胸の高さ（床から 1.3〜1.5m）で持つ前提。机（約 0.7m）や椅子（約 0.45m）の面はこれより高いので外れる
     * しゃがんで使うと床を見逃しやすくなるので、そのときはこの値を小さくする
     */
    private const val MINIMUM_FLOOR_DEPTH_BELOW_CAMERA = 1.0f

    private const val Z_NEAR = 0.1f
    private const val Z_FAR = 100f

    /** 明るさを推定できないときに使う、ふつうの部屋の色味と明るさ（monster.frag を参照） */
    private val DEFAULT_COLOR_CORRECTION = floatArrayOf(1f, 1f, 1f, 0.466f)
  }

  /** 床に置いたモンスター 1 体ぶんの、位置と動き。位置は床のアンカーから見た座標 */
  private class PlacedMonster(
    val wild: WildMonster,
    val model: MonsterModel,
    /** 出現した場所（x, z）。この周りだけを歩く */
    val homeX: Float,
    val homeZ: Float,
    var x: Float,
    var y: Float,
    var z: Float,
    /** 向き（Y 軸まわりの角度、ラジアン）。0 で +Z を向く */
    var yaw: Float,
  ) {
    /** 高さが Monster.size になるようにする拡大率 */
    val scale = wild.monster.size / (model.boundsMax[1] - model.boundsMin[1])
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

    var animation = MonsterModel.IDLE
    var animationStart = 0f
    /** 縮んで消え始めた時刻。消え始めていなければ null */
    var disappearStart: Float? = null
  }

  /** 「向きを変える → 歩く → 少し止まる」をくり返す */
  private enum class Phase { TURN, WALK, REST }

  private lateinit var render: SampleRender
  private lateinit var backgroundRenderer: BackgroundRenderer
  private lateinit var monsterShader: Shader
  private var hasSetTextureNames = false
  private var viewportWidth = 1
  private var viewportHeight = 1

  private val displayRotationHelper = DisplayRotationHelper(activity)
  private val trackingStateHelper = TrackingStateHelper(activity)

  /** 読み込んだ 3D モデル。キーは Monster.modelName。同じ種類がまた出たら使い回す */
  private val models = mutableMapOf<String, MonsterModel>()
  /** モンスターを置く床のアンカー。モンスターの位置はすべてこのアンカーから見た座標で持つ */
  private var floorAnchor: Anchor? = null
  /** 床に置いたモンスター（縮んで消えている途中のものも含む） */
  private val monsters = mutableListOf<PlacedMonster>()
  /** 最後に歩いたとみなした、床の上での自分の位置（ワールド座標の x, z） */
  private var lastWalkPoint: Pair<Float, Float>? = null
  /** 今のフレームのカメラの位置と向き */
  private var cameraPose = Pose.IDENTITY
  /**
   * 画面に戻ってきたので、床とモンスターを置き直すか（メインスレッドで書き、GL スレッドで読む）
   * 画面を離れている間に ARCore が位置を見失っていることがあるので、床を探すところからやり直す
   */
  @Volatile private var needsFloorReset = false

  private val anchorMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val projectionMatrix = FloatArray(16)
  private val viewProjectionMatrix = FloatArray(16)
  private val colorCorrection = DEFAULT_COLOR_CORRECTION.copyOf()

  /** モンスターの動きに使う時刻（秒）。Float でも精度が落ちないよう、この Renderer を作ったときから数える */
  private val clockStart = System.nanoTime()
  private fun now() = (System.nanoTime() - clockStart) / 1e9f

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
    needsFloorReset = true
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
    // 画面を離れている間は床が見えていない扱いにして、逃げるまでの時間を数えないようにする
    viewModel.floorStateChanged(isFound = false)
  }

  override fun onSurfaceCreated(render: SampleRender) {
    this.render = render
    try {
      backgroundRenderer = BackgroundRenderer(render)
      // カメラの映像を描くシェーダーを用意する（深度の表示は使わない）
      backgroundRenderer.setUseDepthVisualization(render, false)
      monsterShader = Shader.createFromAssets(render, "shaders/monster.vert", "shaders/monster.frag", null)
        // モデルは両面を描く前提で作られている
        .setCullFace(false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      activity.showError("アプリのファイルを読み込めませんでした：$e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    viewportWidth = width
    viewportHeight = height
  }

  override fun onDrawFrame(render: SampleRender) {
    val session = activity.arCoreSessionHelper.session ?: return

    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }
    displayRotationHelper.updateSessionIfNeeded(session)

    val frame = try {
      session.update()
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during onDrawFrame", e)
      activity.showError("カメラを使えません。アプリを開き直してください")
      return
    }
    val camera = frame.camera
    backgroundRenderer.updateDisplayGeometry(frame)
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)
    if (frame.timestamp != 0L) {
      backgroundRenderer.drawBackground(render)
    }

    // タップは毎フレーム取り出す。位置を見失っている間や床がない間のタップを残しておくと、あとでモンスターへのタップとして扱われてしまう
    val tap = tapHelper.poll()
    if (needsFloorReset) {
      needsFloorReset = false
      removeFloor()
    }
    if (camera.trackingState != TrackingState.TRACKING) {
      arrowView.update(null, isBehind = false)
      return
    }

    val now = now()
    cameraPose = camera.pose
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
    camera.getViewMatrix(viewMatrix, 0)
    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

    updateFloor(session)
    sync(now)
    measureWalk(camera)
    val anchor = floorAnchor
    if (anchor == null || anchor.trackingState != TrackingState.TRACKING) {
      arrowView.update(null, isBehind = false)
      return
    }
    anchor.pose.toMatrix(anchorMatrix, 0)
    monsters.filter { it.disappearStart == null }.forEach { updateWander(it, now) }
    tap?.let(::handleTap)
    updateArrow()
    drawMonsters(frame.lightEstimate, now)
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
    // 出現しているモンスターがいれば、次のフレームからの sync で置かれる
    reportFloor(isFound = true)
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
    val session = activity.arCoreSessionHelper.session ?: return null
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
    monsters.clear()
    floorAnchor?.detach()
    floorAnchor = null
    // 位置の基準が変わることがあるので、歩いた距離もそこから測り直す
    lastWalkPoint = null
    reportFloor(isFound = false)
  }

  /**
   * 床が見つかった・なくなったことを ViewModel に伝える
   * 画面を離れたあとに届いた「見つかった」は捨てる（onPause で見えていない扱いにして、逃げるまでの時間を止めたのを上書きしないため）
   */
  private fun reportFloor(isFound: Boolean) = activity.runOnUiThread {
    if (!isFound || activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
      viewModel.floorStateChanged(isFound)
    }
  }

  /**
   * ViewModel の出現しているモンスターに合わせて、床のモンスターを置いたり消したりする（iOS の sync）
   * 床があるかは ViewModel の isFloorFound ではなく、自分の床のアンカーで決める
   * isFloorFound はメインスレッドで遅れて変わるので、床を見つけ直した直後に古い値を見て置くと、置き直しが 2 回起きる
   */
  private fun sync(now: Float) {
    if (floorAnchor == null) return
    val shown = listOfNotNull(viewModel.state.value.wildMonster)
    val ids = shown.map { it.id }.toSet()

    // 一覧からいなくなったのは、捕まえられたか逃げたモンスター
    for (monster in monsters) {
      if (monster.disappearStart == null && monster.wild.id !in ids) monster.disappearStart = now
    }
    for (wild in shown) {
      if (monsters.none { it.wild.id == wild.id }) appear(wild)
    }
  }

  private fun appear(wild: WildMonster) {
    val anchorPose = floorAnchor?.pose ?: return
    // glb は小さい（200KB 以下）ので、GL スレッドでそのまま読み込んでも、止まって見えるほどはかからない
    val model = try {
      models.getOrPut(wild.monster.modelName) { MonsterModel.load(render, activity.assets, wild.monster.modelAssetPath) }
    } catch (e: IOException) {
      Log.e(TAG, "Failed to load ${wild.monster.modelAssetPath}", e)
      return
    }
    val (homeX, homeZ) = randomSpawnPoint(anchorPose)
    monsters += PlacedMonster(
      wild = wild,
      model = model,
      homeX = homeX,
      homeZ = homeZ,
      x = homeX,
      // モデルは足元を原点にして描くので、y を床の高さにすると床の上に立つ
      y = floorHeight(homeX, homeZ) ?: 0f,
      z = homeZ,
      yaw = (0f..(2 * PI).toFloat()).random(),
    )
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
  }

  /** 次に歩いていく点を決め、そちらを向く */
  private fun startTurn(monster: PlacedMonster, now: Float) {
    val (targetX, targetZ) = nextTarget(monster)
    monster.toX = targetX
    monster.toZ = targetZ
    monster.fromYaw = monster.yaw
    // モデルは +Z 方向が正面なので、+Z が進む方向を向くように Y 軸で回す
    // 実機で後ろ向きに歩いていたら、角度に PI を足す
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
    playAnimation(monster, MonsterModel.WALK, now)
    startPhase(monster, Phase.WALK, distance / monster.speed, now)
  }

  private fun startRest(monster: PlacedMonster, now: Float) {
    playAnimation(monster, MonsterModel.IDLE, now)
    startPhase(monster, Phase.REST, REST_RANGE.random(), now)
  }

  private fun startPhase(monster: PlacedMonster, phase: Phase, duration: Float, now: Float) {
    monster.phase = phase
    monster.phaseStart = now
    monster.phaseDuration = duration
  }

  /** くり返すアニメーションを切り替える。同じアニメーションのままなら、最初からやり直さない */
  private fun playAnimation(monster: PlacedMonster, animation: String, now: Float) {
    if (monster.animation == animation) return
    monster.animation = animation
    monster.animationStart = now
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
    activity.runOnUiThread { viewModel.walked(distance) }
  }

  /** タップした所にモンスターがいたら、ViewModel に伝える */
  private fun handleTap(tap: MotionEvent) {
    // タップした点から画面の奥へ伸びる線（ワールド座標の、手前の点と奥の点）
    val inverse = FloatArray(16)
    if (!Matrix.invertM(inverse, 0, viewProjectionMatrix, 0)) return
    val x = 2 * tap.x / viewportWidth - 1
    val y = 1 - 2 * tap.y / viewportHeight
    val near = unproject(inverse, x, y, -1f)
    val far = unproject(inverse, x, y, 1f)

    val id: UUID = monsters.firstOrNull { it.disappearStart == null && hits(it, near, far) }?.wild?.id ?: return
    activity.runOnUiThread { viewModel.monsterTapped(id) }
  }

  private fun unproject(inverseViewProjection: FloatArray, x: Float, y: Float, z: Float): FloatArray {
    val point = FloatArray(4)
    Matrix.multiplyMV(point, 0, inverseViewProjection, 0, floatArrayOf(x, y, z, 1f), 0)
    return floatArrayOf(point[0] / point[3], point[1] / point[3], point[2] / point[3], 1f)
  }

  /**
   * near から far への線が、モンスターを囲む箱に当たるか
   * 細かい形に合わせると脚の隙間などで外れやすいので、iOS と同じくモデル全体を囲む箱で判定する
   * 線をモデルの座標に移してから、箱の各面との交わりを調べる
   */
  private fun hits(monster: PlacedMonster, near: FloatArray, far: FloatArray): Boolean {
    val inverse = FloatArray(16)
    if (!Matrix.invertM(inverse, 0, modelMatrix(monster, shrink = 1f), 0)) return false
    val origin = FloatArray(4).also { Matrix.multiplyMV(it, 0, inverse, 0, near, 0) }
    val end = FloatArray(4).also { Matrix.multiplyMV(it, 0, inverse, 0, far, 0) }
    var enter = 0f
    var exit = 1f
    for (axis in 0 until 3) {
      val direction = end[axis] - origin[axis]
      val min = monster.model.boundsMin[axis]
      val max = monster.model.boundsMax[axis]
      if (abs(direction) < 1e-6f) {
        if (origin[axis] < min || origin[axis] > max) return false
      } else {
        val t1 = (min - origin[axis]) / direction
        val t2 = (max - origin[axis]) / direction
        enter = maxOf(enter, minOf(t1, t2))
        exit = minOf(exit, maxOf(t1, t2))
        if (enter > exit) return false
      }
    }
    return true
  }

  /**
   * モンスターが画面の外にいたら、画面の端に、その方向を指す矢印を出す
   * カメラから見た位置にし、カメラの後ろにいるときは前に折り返してから画面の点を求める
   * 上下左右の向きは変わらないので、右後ろなら右、左後ろなら左を指す
   */
  private fun updateArrow() {
    val monster = monsters.firstOrNull { it.disappearStart == null }
    val anchor = floorAnchor
    if (monster == null || anchor == null) {
      arrowView.update(null, isBehind = false)
      return
    }
    val target = anchor.pose.transformPoint(floatArrayOf(monster.x, monster.y + ARROW_TARGET_HEIGHT, monster.z))
    val local = FloatArray(4)
    Matrix.multiplyMV(local, 0, viewMatrix, 0, floatArrayOf(target[0], target[1], target[2], 1f), 0)
    // カメラは -Z 方向を向いているので、z が正ならカメラの後ろ
    val isBehind = local[2] > 0
    if (isBehind) local[2] = -local[2]
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, projectionMatrix, 0, local, 0)
    if (clip[3] <= 0) {
      arrowView.update(null, isBehind = false)
      return
    }
    val screenX = (clip[0] / clip[3] + 1) / 2 * viewportWidth
    val screenY = (1 - clip[1] / clip[3]) / 2 * viewportHeight
    arrowView.update(screenX to screenY, isBehind)
  }

  private fun drawMonsters(lightEstimate: LightEstimate, now: Float) {
    // 部屋の明るさと色味に合わせて、モンスターを暗くしたり色を寄せたりする
    if (lightEstimate.state == LightEstimate.State.VALID) {
      lightEstimate.getColorCorrection(colorCorrection, 0)
    } else {
      DEFAULT_COLOR_CORRECTION.copyInto(colorCorrection)
    }
    monsterShader.setVec4("u_ColorCorrection", colorCorrection)

    // 縮み終わったモンスターは取り除く
    monsters.removeAll { monster -> monster.disappearStart?.let { now - it >= DISAPPEAR_DURATION } ?: false }
    for (monster in monsters) {
      val shrink = monster.disappearStart?.let { 1 - (now - it) / DISAPPEAR_DURATION } ?: 1f
      monster.model.draw(
        render,
        monsterShader,
        modelMatrix(monster, shrink),
        viewProjectionMatrix,
        monster.animation,
        now - monster.animationStart,
      )
    }
  }

  /** モデルの座標から、ワールド座標への行列。shrink は消えるときに縮める割合（1 でそのまま） */
  private fun modelMatrix(monster: PlacedMonster, shrink: Float): FloatArray {
    val matrix = anchorMatrix.copyOf()
    Matrix.translateM(matrix, 0, monster.x, monster.y, monster.z)
    Matrix.rotateM(matrix, 0, Math.toDegrees(monster.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
    // 0 にすると逆行列が求められなくなるので、見えないくらい小さくする
    val scale = monster.scale * shrink.coerceAtLeast(0.001f)
    Matrix.scaleM(matrix, 0, scale, scale, scale)
    // モデルの一番下を床の高さにそろえる
    Matrix.translateM(matrix, 0, 0f, -monster.model.boundsMin[1], 0f)
    return matrix
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
}
