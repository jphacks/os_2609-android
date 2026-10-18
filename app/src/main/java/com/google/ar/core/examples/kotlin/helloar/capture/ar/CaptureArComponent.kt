package com.google.ar.core.examples.kotlin.helloar.capture.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.ar.core.Config
import com.google.ar.core.examples.kotlin.helloar.monster.MonsterArrowComponent
import com.google.ar.core.examples.kotlin.helloar.viewmodel.CaptureViewModel
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * 捕獲画面の AR（iOS の ARViewContainer にあたる）。カメラの映像を映し、床の上をモンスターが歩く
 * シーンの操作は CaptureArSceneController が担当する
 * AR セッションの開始・停止は ARSceneView が画面のライフサイクルに合わせて行う（この部品が画面から外れると止まる）
 * カメラの権限や、ARCore を使えない端末の案内も ARSceneView が出す
 */
@Composable
fun CaptureArComponent(viewModel: CaptureViewModel, modifier: Modifier = Modifier) {
  val engine = rememberEngine()
  val modelLoader = rememberModelLoader(engine)
  val cameraNode = rememberARCameraNode(engine)
  val scope = rememberCoroutineScope()
  val controller = remember { CaptureArSceneController(viewModel, engine, modelLoader, cameraNode, scope) }
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
      cameraNode = cameraNode,
      // 床にモンスターを置くため、水平面だけを探す。床を見つけたら、見つけた面の模様を消す
      planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
      planeRenderer = controller.floorAnchor == null,
      onSessionUpdated = controller::onSessionUpdated,
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
    }
    // 上の案内に重ならないよう、端から少し内側に出す
    controller.arrowAngle?.let { MonsterArrowComponent(it, inset = 90.dp) }
  }
}
