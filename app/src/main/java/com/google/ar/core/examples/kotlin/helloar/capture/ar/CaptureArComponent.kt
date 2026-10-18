package com.google.ar.core.examples.kotlin.helloar.capture.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.Config
import com.google.ar.core.examples.kotlin.helloar.battle.ar.ArHandoff
import com.google.ar.core.examples.kotlin.helloar.monster.MonsterArrowComponent
import com.google.ar.core.examples.kotlin.helloar.viewmodel.CaptureViewModel
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * 捕獲画面の AR（iOS の ARViewContainer にあたる）。カメラの映像を映し、床の上をモンスターが歩く
 * シーンの操作は CaptureArSceneController が担当する
 * AR セッションの開始・停止は ARSceneView が画面のライフサイクルに合わせて行う（この部品が画面から外れると止まる）
 * カメラの権限や、ARCore を使えない端末の案内も ARSceneView が出す
 *
 * 野生のモンスターとのバトルの間も、この ARSceneView は外さない。外すと AR セッションが閉じて、位置の基準と見つけた床がなくなるため
 * バトルの画面（battleContent）は AR の上に重ね、バトルのモンスターはこの ARSceneView の中に描く（ArHandoff）
 *
 * @param battleContent バトル中に AR の上に重ねる画面。引き継ぐ AR を受け取る
 */
@Composable
fun CaptureArComponent(
  viewModel: CaptureViewModel,
  modifier: Modifier = Modifier,
  battleContent: @Composable (ArHandoff) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val engine = rememberEngine()
  val modelLoader = rememberModelLoader(engine)
  // バトルでダメージの数字を出すのに使う（捕獲画面だけなら要らない）
  val materialLoader = rememberMaterialLoader(engine)
  val cameraNode = rememberARCameraNode(engine)
  val scope = rememberCoroutineScope()
  val controller = remember { CaptureArSceneController(viewModel, engine, modelLoader, cameraNode, scope) }
  val battleMonsterId = state.battleMonster?.id
  val battlePosition = state.battleMonsterPosition
  /** バトル中なら、バトルに引き継ぐ AR。バトルが変わるたびに作り直す */
  val handoff = remember(battleMonsterId) {
    battlePosition?.let { ArHandoff(engine, materialLoader, cameraNode, it) }
  }
  // ARSceneView に渡す処理の中からは、いつも今の handoff を見る（渡した処理を ARSceneView が作り直さずに使い続けても食い違わない）
  val currentHandoff by rememberUpdatedState(handoff)
  DisposableEffect(controller) {
    onDispose { controller.stop() }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.resume() }
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.pause() }

  Box(modifier) {
    ARSceneView(
      modifier = Modifier
        .fillMaxSize()
        .onSizeChanged {
          controller.viewportWidth = it.width
          controller.viewportHeight = it.height
        },
      engine = engine,
      modelLoader = modelLoader,
      materialLoader = materialLoader,
      cameraNode = cameraNode,
      // 床にモンスターを置くため、水平面だけを探す。床を見つけたら、見つけた面の模様を消す（バトル中も出さない）
      planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
      planeRenderer = controller.floorAnchor == null && handoff == null,
      onSessionUpdated = { session, frame ->
        controller.onSessionUpdated(session, frame)
        currentHandoff?.battleScene?.onSessionUpdated(session, frame)
      },
      onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { event, _ -> controller.onTap(event) },
      ),
    ) {
      controller.floorAnchor?.let { anchor ->
        // 床を見つけ直したら、新しいアンカーで AnchorNode を作り直す
        key(anchor) {
          AnchorNode(anchor = anchor, apply = { controller.attach(this) })
        }
      }
      // バトルのモンスターは、バトル用のアンカーの下に置く（捕獲画面の床のアンカーとは別にする）
      currentHandoff?.battleScene?.let { battleScene ->
        battleScene.floorAnchor?.let { anchor ->
          key(anchor) {
            AnchorNode(anchor = anchor, apply = { battleScene.attach(this) })
          }
        }
      }
    }
    // 上の案内に重ならないよう、端から少し内側に出す
    controller.arrowAngle?.let { MonsterArrowComponent(it, inset = 90.dp) }
    handoff?.let { battleContent(it) }
  }
}
