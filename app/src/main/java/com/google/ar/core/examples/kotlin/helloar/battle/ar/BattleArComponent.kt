package com.google.ar.core.examples.kotlin.helloar.battle.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.Engine
import com.google.ar.core.Config
import com.google.ar.core.examples.kotlin.helloar.battle.BattleHaptic
import com.google.ar.core.examples.kotlin.helloar.battle.BattleStyle
import com.google.ar.core.examples.kotlin.helloar.battle.battlePanel
import com.google.ar.core.examples.kotlin.helloar.battle.perform
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.model.MonsterAnimation
import com.google.ar.core.examples.kotlin.helloar.model.MonsterAnimationRequest
import com.google.ar.core.examples.kotlin.helloar.monster.MonsterArrowComponent
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader

/**
 * バトル画面の AR（iOS の BattleArenaComponent と BattleARViewContainer にあたる）
 * カメラの映像を映し、床が見つかったら、その上でモンスター同士を戦わせる。シーンの操作は BattleArSceneController が担当する
 * AR セッションの開始・停止は ARSceneView が画面のライフサイクルに合わせて行う（この部品が画面から外れると止まる）
 * ARCore を使えない端末では ARSceneView が案内を出す。対戦（HP やタップ）はそのまま続けられる
 *
 * 野生のモンスターとのバトルでは、自分では ARSceneView を置かず、捕獲画面の ARSceneView の中で戦う（ArHandoff）
 */
@Composable
fun BattleArComponent(
  myMonster: Monster,
  opponentMonster: Monster,
  /** 立っている間にくり返すアニメーション（HP が少ないと弱った動きになる） */
  myIdle: MonsterAnimation,
  opponentIdle: MonsterAnimation,
  myAnimation: MonsterAnimationRequest?,
  opponentAnimation: MonsterAnimationRequest?,
  /** 攻撃が当たった出来事。変わるたびに、攻撃した側のモンスターが踏み込み、受けた側の頭の上にダメージの数字が出る */
  hitOnOpponent: BattleViewModel.Hit?,
  hitOnMe: BattleViewModel.Hit?,
  /** 相手の必殺技の予告。変わるたびに、相手のモンスターが赤く点滅する */
  danger: BattleViewModel.Danger?,
  /** よけるためにスワイプした出来事。変わるたびに、自分のモンスターが横にかわす */
  myDodge: BattleViewModel.Dodge?,
  /** フィーバー中は、自分のモンスターが金色に光る */
  isFeverActive: Boolean,
  /** 野生のモンスターとのバトルで、捕獲画面から引き継ぐ AR。null なら自分で ARSceneView を置き、床を探す */
  arHandoff: ArHandoff?,
  modifier: Modifier = Modifier,
) {
  // 引き継ぐときは、捕獲画面の ARSceneView のものを使う（arHandoff はこの部品が画面にある間は変わらない）
  val engine = arHandoff?.engine ?: rememberEngine()
  // 捕獲画面と読み込み役を分け、対戦が終わったらモデルの元データも解放する
  val context = LocalContext.current
  val modelLoader = remember(engine) { ModelLoader(engine, context) }
  val materialLoader = arHandoff?.materialLoader ?: rememberMaterialLoader(engine)
  val cameraNode = arHandoff?.cameraNode ?: rememberARCameraNode(engine)
  val scope = rememberCoroutineScope()
  val controller = remember {
    BattleArSceneController(
      myMonster, opponentMonster, engine, modelLoader, materialLoader, cameraNode, scope, arHandoff?.opponentPosition,
    )
  }
  SideEffect {
    controller.update(
      myIdle, opponentIdle, myAnimation, opponentAnimation, hitOnOpponent, hitOnMe, danger, myDodge, isFeverActive,
    )
  }
  DisposableEffect(controller) {
    // 捕獲画面の ARSceneView に、バトルのモンスターを描いてもらう。外れたら、捕獲画面だけに戻す
    arHandoff?.battleScene = controller
    onDispose {
      arHandoff?.battleScene = null
      controller.stop()
      modelLoader.destroy()
    }
  }
  // モンスターが現れたら振動させ、画面を見ていなくても気づけるようにする
  val view = LocalView.current
  LaunchedEffect(controller.isPlaced) {
    if (controller.isPlaced) view.perform(BattleHaptic.WARNING)
  }

  Box(modifier, contentAlignment = Alignment.Center) {
    if (arHandoff == null) ARSceneView(
      modifier = Modifier.fillMaxSize(),
      engine = engine,
      modelLoader = modelLoader,
      materialLoader = materialLoader,
      cameraNode = cameraNode,
      // 床にモンスターを置くため、水平面だけを探す。置いたあとは、見つけた面の模様を消す
      planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
      planeRenderer = !controller.isPlaced,
      onSessionUpdated = controller::onSessionUpdated,
      // 画面のタップは攻撃に使うので、AR の中のものを触る操作は受け付けない
      onGestureListener = null,
    ) {
      controller.floorAnchor?.let { anchor ->
        AnchorNode(anchor = anchor, apply = { controller.attach(this) })
      }
    }
    if (!controller.isPlaced) {
      Text(
        "床を映すと、モンスターが現れます",
        style = BattleStyle.display(20.sp),
        color = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier.battlePanel(BattleStyle.cyan),
      )
    }
    // 上と下の HP の表示に重ならないよう、端から少し内側に出す
    controller.opponentArrowAngle?.let { MonsterArrowComponent(it, inset = 120.dp) }
  }
}

/**
 * 捕獲画面から引き継ぐ AR（iOS の ARHandoff）。野生のモンスターとのバトルで、捕獲画面の AR をそのまま使う
 * 位置の基準と見つけた床が残っているので、床を探し直さずに、すぐにモンスターを置ける
 *
 * iOS は捕獲画面の AR セッションをバトルの ARView に渡すが、SceneView の ARSceneView は外から AR セッションを受け取れず、
 * 画面から外すとセッションも閉じてしまう。そのため捕獲画面の ARSceneView をバトルの間も外さず、その中にバトルのモンスターを描く
 * ここに入っているのは、捕獲画面の ARSceneView が使っているエンジンや読み込み役
 */
class ArHandoff(
  val engine: Engine,
  val materialLoader: MaterialLoader,
  val cameraNode: ARCameraNode,
  /** 相手のモンスターを置く場所（ワールド座標、足元）。捕獲画面でそのモンスターがいた場所 */
  val opponentPosition: Position,
) {
  /**
   * 引き継いだ AR で戦っているバトルのシーン。対戦が始まると BattleArComponent が入れ、対戦が終わると null に戻す
   * 捕獲画面の ARSceneView は、これがあればバトルのモンスターも描き、AR のフレームごとに onSessionUpdated を呼ぶ
   */
  var battleScene by mutableStateOf<BattleArSceneController?>(null)
}
