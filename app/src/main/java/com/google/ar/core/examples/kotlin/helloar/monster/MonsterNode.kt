package com.google.ar.core.examples.kotlin.helloar.monster

import com.google.android.filament.Engine
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node

/**
 * モンスターの 3D モデル（glb）を読み込み、高さを height（m）に合わせる（iOS の MonsterEntity）。読み込めなければ null
 * 返すのは、場所と向きを持つノードと、その中のモデル。置く場所やアニメーションは呼ぶ側が決める（捕獲画面とバトル画面で共通）
 * アニメーションは MonsterAnimator で流すので、SceneView の自動のアニメーションは止めておく
 */
suspend fun loadMonsterNode(engine: Engine, modelLoader: ModelLoader, monster: Monster, height: Float): Pair<Node, ModelNode>? {
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
