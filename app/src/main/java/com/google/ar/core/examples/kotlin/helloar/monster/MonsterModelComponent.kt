package com.google.ar.core.examples.kotlin.helloar.monster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.model.MonsterAnimation
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * カメラ映像なしで 3D のモンスターを 1 体出す（バトルの対戦前の画面、最初にモンスターを選ぶ画面、捕獲成功の画面で使う）。立ったまま体を揺らし、指で回せる
 * AR（BattleArComponent、CaptureArComponent）とは同時に出さない（Filament の画面が 2 つ重なると、前後が入れ替わることがある）
 * facingDegrees：最初に向ける向き（Y 軸まわりの角度）
 */
@Composable
fun MonsterModelComponent(monster: Monster, facingDegrees: Float, modifier: Modifier = Modifier) {
  // モンスターが変わっても SceneView とエンジンは作り直さず、モデルだけを読み直す
  // エンジンごと作り直すと、新しい SceneView に何も映らないことがある（最初にモンスターを選ぶ画面で切り替えたとき）
  val engine = rememberEngine()
  val modelLoader = rememberModelLoader(engine)
  // 後ろの画面の色が見えるよう、透明にできる TextureSurface で描く
  SceneView(
    modifier = modifier,
    surfaceType = SurfaceType.Surface,
    engine = engine,
    modelLoader = modelLoader,
    isOpaque = false,
    autoFitContent = true,
  ) {
    key(monster.modelName) {
      val instance = rememberModelInstance(modelLoader, monster.modelAssetPath)
      instance?.let {
        // 小さいモンスターも大きく見えるよう、どれも同じ大きさにそろえる
        ModelNode(
          modelInstance = it,
          animationName = MonsterAnimation.IDLE.clipName,
          scaleToUnits = 2.2f,
          centerOrigin = Position(0f, 0f, 0f),
          rotation = Rotation(y = facingDegrees),
        )
      }
    }
  }
}
