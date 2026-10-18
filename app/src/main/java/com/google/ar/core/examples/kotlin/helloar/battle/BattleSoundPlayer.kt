package com.google.ar.core.examples.kotlin.helloar.battle

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.ar.core.examples.kotlin.helloar.logic.FeverLottery
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * バトルの効果音を鳴らす（iOS の BattleSoundPlayer と同じ音）
 * 音のファイルは使わず、最初に鳴らすときに、音をその場で作る
 * 音量はメディアの音量に合わせる。ほかのアプリの音楽は止めずに重ねて鳴らす
 */
object BattleSoundPlayer {
  sealed interface Sound {
    /** 通常攻撃が当たった */
    data object Hit : Sound
    /** フィーバー中の通常攻撃が当たった */
    data object FeverHit : Sound
    /** 必殺技が当たった */
    data object SpecialHit : Sound
    /** とどめを刺した */
    data object FinishHit : Sound
    /** フィーバーのチャンスの帯が出た・色が上がった。上の色ほど高い音 */
    data class ChanceStep(val level: FeverLottery.Level) : Sound
    /** チャンスの帯が、金まで上がらずに砕けた */
    data object ChanceBreak : Sound
    /** フィーバーが始まった */
    data object FeverStart : Sound
    /** 相手の必殺技の予告 */
    data object Danger : Sound
    /** 勝った */
    data object Victory : Sound
  }

  private const val SAMPLE_RATE = 44_100

  /** 鳴らす音。1 つの音を 1 つの AudioTrack に入れておき、鳴らすたびに頭から流す */
  private class Clip(samples: FloatArray) {
    val track: AudioTrack = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setTransferMode(AudioTrack.MODE_STATIC)
      .setBufferSizeInBytes(samples.size * 2)
      .build()
      .apply {
        write(ShortArray(samples.size) { (samples[it] * Short.MAX_VALUE).toInt().toShort() }, 0, samples.size)
      }

    fun play() {
      track.stop()
      track.reloadStaticData()
      track.play()
    }

    fun stop() = track.stop()
  }

  /** 通常攻撃の音。毎回同じ音だと単調なので、高さを少しずつ変えたものを用意して、順に鳴らす */
  private val normalHits by lazy { listOf(1.0, 1.12, 0.92).map { makeHit(pitch = it, length = 0.12, decay = 30.0) } }
  /** フィーバー中は、高く明るい音にキラッとした音を重ねる */
  private val feverHits by lazy { listOf(1.4, 1.55, 1.3).map { makeHit(it, 0.18, 25.0, sparkle = 1800.0) } }
  /** 必殺技の音。低く、長く響かせる */
  private val specialHit by lazy { makeHit(0.7, 0.35, 10.0) }
  /** とどめの音。さらに低く、長く響かせる */
  private val finishHit by lazy { makeHit(0.5, 0.8, 5.0, sparkle = 1200.0) }
  /** フィーバー開始：ドミソドと駆け上がる */
  private val feverStart by lazy { makeMelody(listOf(523.0 to 0.07, 659.0 to 0.07, 784.0 to 0.07, 1047.0 to 0.35)) }
  /** チャンスの帯の色が上がる音：色ごとに 1 段ずつ高くなる「ピロン」。金は長く響かせる */
  private val chanceSteps by lazy {
    mapOf(
      FeverLottery.Level.BLUE to makeMelody(listOf(523.0 to 0.05, 784.0 to 0.15)),
      FeverLottery.Level.GREEN to makeMelody(listOf(659.0 to 0.05, 988.0 to 0.15)),
      FeverLottery.Level.RED to makeMelody(listOf(784.0 to 0.05, 1175.0 to 0.2), square = true),
      FeverLottery.Level.GOLD to makeMelody(listOf(1047.0 to 0.05, 1568.0 to 0.05, 2093.0 to 0.4), square = true),
    )
  }
  /** 帯が砕ける音：低く「ガシャン」 */
  private val chanceBreak by lazy { makeHit(0.4, 0.3, 12.0) }
  /** 予告：高い音と低い音を 2 回くり返す警報 */
  private val danger by lazy { makeMelody(listOf(880.0 to 0.12, 660.0 to 0.12, 880.0 to 0.12, 660.0 to 0.2), square = true) }
  /** 勝利：ファンファーレ */
  private val victory by lazy {
    makeMelody(listOf(523.0 to 0.12, 523.0 to 0.12, 523.0 to 0.12, 659.0 to 0.3, 587.0 to 0.15, 784.0 to 0.7))
  }
  private var nextHit = 0
  /**
   * 最後に鳴らした攻撃の音と、それ以外（予告や勝利）の音
   * 分けておくと、連打していても予告や勝利の音が途中で切れない。同じ種類の音は、前の音を止めてすぐに鳴らす
   */
  private var lastHitClip: Clip? = null
  private var lastJingleClip: Clip? = null

  /** 音を作っておく。作るのに少し時間がかかるので、対戦の画面を開いたときに裏で呼ぶ（呼ばなくても、最初に鳴らすときに作る） */
  fun prepare() {
    normalHits; feverHits; specialHit; finishHit; feverStart; chanceSteps; chanceBreak; danger; victory
  }

  @Synchronized
  fun play(sound: Sound) {
    val isHit = sound is Sound.Hit || sound is Sound.FeverHit || sound is Sound.SpecialHit || sound is Sound.FinishHit
    val clip = when (sound) {
      Sound.Hit -> next(normalHits)
      Sound.FeverHit -> next(feverHits)
      Sound.SpecialHit -> specialHit
      Sound.FinishHit -> finishHit
      is Sound.ChanceStep -> chanceSteps.getValue(sound.level)
      Sound.ChanceBreak -> chanceBreak
      Sound.FeverStart -> feverStart
      Sound.Danger -> danger
      Sound.Victory -> victory
    }
    // 前の音が残っていたら止めて、すぐに鳴らす
    if (isHit) {
      lastHitClip?.stop()
      lastHitClip = clip
    } else {
      lastJingleClip?.stop()
      lastJingleClip = clip
    }
    clip.play()
  }

  private fun next(clips: List<Clip>): Clip {
    nextHit = (nextHit + 1) % clips.size
    return clips[nextHit]
  }

  /**
   * 打撃音を作る。低い「ドッ」と短い「パシッ」を重ねる
   * pitch を大きくすると高い音、decay を大きくすると早く消える音になる。sparkle を渡すと、その高さのキラッとした音を重ねる
   */
  private fun makeHit(pitch: Double, length: Double, decay: Double, sparkle: Double? = null) = makeClip(length) { t ->
    // 低い「ドッ」：高い音から低い音へ一気に下げる。音の高さが変わるので、高さを足し合わせて位相にする
    val phase = 2 * PI * pitch * (70 * t + 160 * (1 - exp(-t * 40)) / 40)
    val thump = sin(phase) * exp(-t * decay)
    // 短い「パシッ」：ノイズをすぐに消す
    val crack = Random.nextDouble(-1.0, 1.0) * exp(-t * 90)
    val shine = sparkle?.let { sin(2 * PI * it * t) * 0.3 * exp(-t * 15) } ?: 0.0
    (0.7 * thump + 0.45 * crack + shine) * 0.8
  }

  /** 音の高さ（Hz）と長さ（秒）の並びから、短いメロディを作る。square なら矩形波（ピコピコした音）にする */
  private fun makeMelody(notes: List<Pair<Double, Double>>, square: Boolean = false): Clip {
    val starts = notes.runningFold(0.0) { start, note -> start + note.second }
    return makeClip(starts.last()) { t ->
      val index = starts.indexOfLast { it <= t }
      if (index !in notes.indices) return@makeClip 0.0
      val (frequency, length) = notes[index]
      val local = t - starts[index]
      val tone = sin(2 * PI * frequency * local)
      // サイン波には倍音を足して明るくする
      val shaped = if (square) (if (tone >= 0) 0.5 else -0.5) else tone + 0.3 * sin(4 * PI * frequency * local)
      // 音ごとに、鳴り始めと終わりで音を小さくして、つなぎ目でプツッと鳴らないようにする
      val envelope = minOf(local / 0.005, 1.0) * minOf((length - local) / 0.02, 1.0)
      shaped * envelope * 0.35
    }
  }

  /** 時刻（秒）ごとの音の大きさ（-1〜1）から、音を作る */
  private fun makeClip(length: Double, sample: (Double) -> Double): Clip {
    val samples = FloatArray((length * SAMPLE_RATE).toInt()) { i ->
      val t = i.toDouble() / SAMPLE_RATE
      // 鳴り始めにプツッと音が出ないよう、最初の 2ms で音を大きくする
      val attack = minOf(t / 0.002, 1.0)
      (sample(t) * attack).coerceIn(-1.0, 1.0).toFloat()
    }
    return Clip(samples)
  }
}
