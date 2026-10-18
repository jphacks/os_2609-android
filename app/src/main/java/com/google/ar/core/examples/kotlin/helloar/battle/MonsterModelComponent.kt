package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster
import com.google.ar.core.examples.kotlin.helloar.model.battle.MonsterAnimation
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * カメラ映像なしで 3D のモンスターを 1 体出す（対戦前の画面で使う）。立ったまま体を揺らし、指で回せる
 * 対戦中の AR（BattleArComponent）とは同時に出さない（対戦が始まると、この部品は画面から外れる）
 * facingDegrees：最初に向ける向き（Y 軸まわりの角度）
 */
@Composable
fun MonsterModelComponent(monster: BattleMonster, facingDegrees: Float, modifier: Modifier = Modifier) {
  // モンスターが変わったら、読み込むところから作り直す
  key(monster.modelName) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val instance = rememberModelInstance(modelLoader, monster.modelAssetPath)
    // 後ろの画面の色が見えるよう、透明にできる TextureSurface で描く
    SceneView(
      modifier = modifier,
      surfaceType = SurfaceType.Surface,
      engine = engine,
      modelLoader = modelLoader,
      isOpaque = false,
      autoFitContent = true,
    ) {
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
