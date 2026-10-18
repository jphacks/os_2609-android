package com.google.ar.core.examples.kotlin.helloar.monster

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.ar.core.examples.java.common.samplerender.IndexBuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * モンスターの 3D モデル（glb）を読み込み、アニメーションの好きな時刻の姿勢で描く
 * iOS の MonsterEntity（読み込み）と MonsterAnimator（アニメーション）にあたる
 * 配置する場所やタイミングは決めない（CaptureRenderer が決める）
 *
 * glTF のライブラリは使わず、Kenney「Cube Pets」の glb に入っているものだけを読む
 * （スキンなし。体・脚・しっぽなどの部品ごとのノードを、位置・回転・大きさのアニメーションで動かす）
 * GL のオブジェクトを作るので、GL スレッドで読み込み、GL スレッドで描く
 */
class MonsterModel private constructor(
  private val nodes: List<Node>,
  private val rootNodes: List<Int>,
  private val animations: Map<String, Animation>,
  private val texture: Texture,
  /** アニメーションしていない姿勢での、モデル全体を囲む箱（モデルの座標） */
  val boundsMin: FloatArray,
  val boundsMax: FloatArray,
) {
  /** モデルの部品 1 つ。親から見た位置・回転・大きさを持つ */
  private class Node(
    val children: List<Int>,
    val meshes: List<Mesh>,
    val translation: FloatArray,
    /** クォータニオン（x, y, z, w） */
    val rotation: FloatArray,
    val scale: FloatArray,
  )

  /** アニメーション 1 本。部品ごとの、時刻と値の並び（キーフレーム） */
  private class Animation(val duration: Float, val channels: List<Channel>)

  private class Channel(
    val node: Int,
    /** "translation" / "rotation" / "scale" */
    val path: String,
    val times: FloatArray,
    val values: FloatArray,
  )

  companion object {
    /** 立っているときのアニメーション（くり返す） */
    const val IDLE = "idle"
    /** 歩くアニメーション（くり返す） */
    const val WALK = "walk"

    private const val GLB_JSON_START = 20

    /** assetPath（例："models/animal-cat.glb"）の glb を読み込む。GL スレッドで呼ぶ */
    fun load(render: SampleRender, assets: AssetManager, assetPath: String): MonsterModel {
      val bytes = assets.open(assetPath).use { it.readBytes() }
      // glb は「12 バイトのヘッダー → JSON のチャンク → バイナリのチャンク」の順に並んでいる
      // チャンクは「長さ（4 バイト）・種類（4 バイト）・中身」
      val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val jsonLength = data.getInt(12)
      val json = JSONObject(String(bytes, GLB_JSON_START, jsonLength, Charsets.UTF_8))
      val reader = AccessorReader(json, data, binaryStart = GLB_JSON_START + jsonLength + 8)

      val texture = loadTexture(render, json, data, reader)
      val meshes = json.getJSONArray("meshes").objects().map { mesh ->
        mesh.getJSONArray("primitives").objects().map { makeMesh(render, it, reader) }
      }
      val nodes = json.getJSONArray("nodes").objects().map { node ->
        Node(
          children = node.optJSONArray("children")?.ints().orEmpty(),
          meshes = if (node.has("mesh")) meshes[node.getInt("mesh")] else emptyList(),
          translation = node.optJSONArray("translation")?.floats() ?: floatArrayOf(0f, 0f, 0f),
          rotation = node.optJSONArray("rotation")?.floats() ?: floatArrayOf(0f, 0f, 0f, 1f),
          scale = node.optJSONArray("scale")?.floats() ?: floatArrayOf(1f, 1f, 1f),
        )
      }
      val scene = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0))
      val rootNodes = scene.getJSONArray("nodes").ints()

      val animations = json.optJSONArray("animations")?.objects().orEmpty().associate { animation ->
        val samplers = animation.getJSONArray("samplers").objects()
        val channels = animation.getJSONArray("channels").objects().map { channel ->
          val sampler = samplers[channel.getInt("sampler")]
          val target = channel.getJSONObject("target")
          Channel(
            node = target.getInt("node"),
            path = target.getString("path"),
            times = reader.floats(sampler.getInt("input")),
            values = reader.floats(sampler.getInt("output")),
          )
        }
        animation.getString("name") to Animation(channels.maxOf { it.times.last() }, channels)
      }

      val (boundsMin, boundsMax) = bounds(json, nodes, rootNodes)
      return MonsterModel(nodes, rootNodes, animations, texture, boundsMin, boundsMax)
    }

    /** 部品の色はすべて 1 枚の小さなテクスチャ（色見本）から取っている */
    private fun loadTexture(
      render: SampleRender,
      json: JSONObject,
      data: ByteBuffer,
      reader: AccessorReader,
    ): Texture {
      val image = json.getJSONArray("images").getJSONObject(0)
      val (offset, length) = reader.bufferViewRange(image.getInt("bufferView"))
      val bitmap = BitmapFactory.decodeByteArray(data.array(), offset, length)
      val texture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.textureId)
      // 色見本のマス目の境目で隣の色が混ざらないよう、補間せずにそのままの色を使う
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
      GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
      bitmap.recycle()
      return texture
    }

    /** 頂点の並びは monster.vert の location と同じ（0：位置、1：テクスチャの座標、2：法線） */
    private fun makeMesh(render: SampleRender, primitive: JSONObject, reader: AccessorReader): Mesh {
      val attributes = primitive.getJSONObject("attributes")
      val vertexBuffers = arrayOf(
        VertexBuffer(render, 3, directFloats(reader.floats(attributes.getInt("POSITION")))),
        VertexBuffer(render, 2, directFloats(reader.floats(attributes.getInt("TEXCOORD_0")))),
        VertexBuffer(render, 3, directFloats(reader.floats(attributes.getInt("NORMAL")))),
      )
      val indices = reader.ints(primitive.getInt("indices"))
      val indexBuffer = IndexBuffer(
        render,
        ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(indices).apply { rewind() },
      )
      return Mesh(render, Mesh.PrimitiveMode.TRIANGLES, indexBuffer, vertexBuffers)
    }

    /** GL に渡すバッファは、ネイティブのバイト順の direct バッファでないといけない */
    private fun directFloats(values: FloatArray) =
      ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(values).apply { rewind() }

    /**
     * アニメーションしていない姿勢での、モデル全体を囲む箱
     * 部品ごとの頂点の範囲（glTF の min / max）の 8 つの角を、部品の位置に動かして合わせる
     */
    private fun bounds(json: JSONObject, nodes: List<Node>, rootNodes: List<Int>): Pair<FloatArray, FloatArray> {
      val meshes = json.getJSONArray("meshes").objects()
      val accessors = json.getJSONArray("accessors")
      val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
      val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
      val nodeJsons = json.getJSONArray("nodes").objects()

      fun visit(index: Int, parent: FloatArray) {
        val node = nodes[index]
        val world = FloatArray(16)
        Matrix.multiplyMM(world, 0, parent, 0, composeTrs(node.translation, node.rotation, node.scale), 0)
        if (nodeJsons[index].has("mesh")) {
          for (primitive in meshes[nodeJsons[index].getInt("mesh")].getJSONArray("primitives").objects()) {
            val position = accessors.getJSONObject(primitive.getJSONObject("attributes").getInt("POSITION"))
            val lower = position.getJSONArray("min").floats()
            val upper = position.getJSONArray("max").floats()
            for (corner in 0 until 8) {
              val local = floatArrayOf(
                if (corner and 1 == 0) lower[0] else upper[0],
                if (corner and 2 == 0) lower[1] else upper[1],
                if (corner and 4 == 0) lower[2] else upper[2],
                1f,
              )
              val point = FloatArray(4)
              Matrix.multiplyMV(point, 0, world, 0, local, 0)
              for (axis in 0 until 3) {
                min[axis] = minOf(min[axis], point[axis])
                max[axis] = maxOf(max[axis], point[axis])
              }
            }
          }
        }
        node.children.forEach { visit(it, world) }
      }

      val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
      rootNodes.forEach { visit(it, identity) }
      return min to max
    }

    /** 位置・回転（クォータニオン）・大きさから、OpenGL の列優先の 4×4 行列を作る */
    private fun composeTrs(t: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
      val (x, y, z, w) = q
      return floatArrayOf(
        (1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y + z * w) * s[0], 2 * (x * z - y * w) * s[0], 0f,
        2 * (x * y - z * w) * s[1], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z + x * w) * s[1], 0f,
        2 * (x * z + y * w) * s[2], 2 * (y * z - x * w) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0f,
        t[0], t[1], t[2], 1f,
      )
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.ints() = (0 until length()).map { getInt(it) }
    private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
  }

  /** アニメーションの長さ（秒）。そのアニメーションがなければ 0 */
  fun duration(animation: String): Float = animations[animation]?.duration ?: 0f

  /**
   * animation の time 秒目の姿勢で描く。time は長さを超えたら最初に戻る（くり返す）
   * model はモデルの座標からワールド座標への行列、viewProjection はカメラの行列
   */
  fun draw(
    render: SampleRender,
    shader: Shader,
    model: FloatArray,
    viewProjection: FloatArray,
    animation: String,
    time: Float,
  ) {
    val pose = pose(animation, time)
    shader.setTexture("u_Texture", texture)
    val modelViewProjection = FloatArray(16)

    fun visit(index: Int, parent: FloatArray) {
      val (translation, rotation, scale) = pose[index]
      val world = FloatArray(16)
      Matrix.multiplyMM(world, 0, parent, 0, composeTrs(translation, rotation, scale), 0)
      if (nodes[index].meshes.isNotEmpty()) {
        Matrix.multiplyMM(modelViewProjection, 0, viewProjection, 0, world, 0)
        shader.setMat4("u_Model", world)
        shader.setMat4("u_ModelViewProjection", modelViewProjection)
        nodes[index].meshes.forEach { render.draw(it, shader) }
      }
      nodes[index].children.forEach { visit(it, world) }
    }
    rootNodes.forEach { visit(it, model) }
  }

  /** 部品ごとの、animation の time 秒目の位置・回転・大きさ。アニメーションしない部品は元の値のまま */
  private fun pose(animation: String, time: Float): List<Triple<FloatArray, FloatArray, FloatArray>> {
    val pose = nodes.map { Triple(it.translation.copyOf(), it.rotation.copyOf(), it.scale.copyOf()) }
    val clip = animations[animation] ?: return pose
    val t = if (clip.duration > 0) time.mod(clip.duration) else 0f
    for (channel in clip.channels) {
      val (translation, rotation, scale) = pose[channel.node]
      when (channel.path) {
        "translation" -> sample(channel, t, 3, translation)
        "rotation" -> sample(channel, t, 4, rotation)
        "scale" -> sample(channel, t, 3, scale)
      }
    }
    return pose
  }

  /**
   * キーフレームの間を直線で補間して、out に書き込む（glb のアニメーションはすべて LINEAR）
   * 回転も成分ごとに補間してから長さを 1 に戻す。キーフレームの間隔が短いので、球面補間との差は見えない
   */
  private fun sample(channel: Channel, time: Float, size: Int, out: FloatArray) {
    val times = channel.times
    val next = times.indexOfFirst { it > time }
    val (from, to, ratio) = when {
      next == -1 -> Triple(times.lastIndex, times.lastIndex, 0f)
      next == 0 -> Triple(0, 0, 0f)
      else -> Triple(next - 1, next, (time - times[next - 1]) / (times[next] - times[next - 1]))
    }
    // 回転は、q と -q が同じ向きを表す。遠回りしないよう、近い方どうしで補間する
    var sign = 1f
    if (size == 4) {
      val dot = (0 until 4).sumOf { (channel.values[from * 4 + it] * channel.values[to * 4 + it]).toDouble() }
      if (dot < 0) sign = -1f
    }
    for (i in 0 until size) {
      val a = channel.values[from * size + i]
      val b = channel.values[to * size + i] * sign
      out[i] = a + (b - a) * ratio
    }
    if (size == 4) {
      val length = kotlin.math.sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
      for (i in 0 until 4) out[i] /= length
    }
  }
}

/** glTF の accessor（バイナリのどこに、どんな型の値が何個あるか）のとおりに値を読む */
private class AccessorReader(json: JSONObject, private val data: ByteBuffer, private val binaryStart: Int) {
  private val accessors = json.getJSONArray("accessors")
  private val bufferViews = json.getJSONArray("bufferViews")

  /** bufferView の、glb 全体の中での開始位置と長さ */
  fun bufferViewRange(index: Int): Pair<Int, Int> {
    val view = bufferViews.getJSONObject(index)
    return (binaryStart + view.optInt("byteOffset", 0)) to view.getInt("byteLength")
  }

  fun floats(index: Int): FloatArray {
    val (start, stride, components) = layout(index, componentSize = 4)
    val count = accessors.getJSONObject(index).getInt("count")
    return FloatArray(count * components) { data.getFloat(start + (it / components) * stride + (it % components) * 4) }
  }

  /** 頂点の番号。1 バイト・2 バイト・4 バイトのどれでも、Int にして返す */
  fun ints(index: Int): IntArray {
    val accessor = accessors.getJSONObject(index)
    val count = accessor.getInt("count")
    return when (accessor.getInt("componentType")) {
      GLES30.GL_UNSIGNED_BYTE -> layout(index, 1).let { (start, stride) -> IntArray(count) { data.get(start + it * stride).toInt() and 0xff } }
      GLES30.GL_UNSIGNED_SHORT -> layout(index, 2).let { (start, stride) -> IntArray(count) { data.getShort(start + it * stride).toInt() and 0xffff } }
      else -> layout(index, 4).let { (start, stride) -> IntArray(count) { data.getInt(start + it * stride) } }
    }
  }

  /** 最初の値の位置、1 つ目から 2 つ目までのバイト数、1 つあたりの成分の数 */
  private fun layout(index: Int, componentSize: Int): Triple<Int, Int, Int> {
    val accessor = accessors.getJSONObject(index)
    val view = bufferViews.getJSONObject(accessor.getInt("bufferView"))
    val components = when (accessor.getString("type")) {
      "SCALAR" -> 1
      "VEC2" -> 2
      "VEC3" -> 3
      "VEC4" -> 4
      else -> error("この型の accessor は読めません：${accessor.getString("type")}")
    }
    val start = binaryStart + view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
    val stride = view.optInt("byteStride", components * componentSize)
    return Triple(start, stride, components)
  }
}
