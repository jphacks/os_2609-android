package com.google.ar.core.examples.kotlin.helloar.battle

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.google.ar.core.examples.kotlin.helloar.logic.FeverLottery
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * バトルの効果音を鳴らす（iOS の BattleSoundPlayer）
 * 音のファイルは使わず、起動してから最初に鳴らすときに、音をその場で作る
 * マナーモード（バイブ・サイレント）のときは鳴らない。音の大きさはメディアの音量に従う
 * 音声フォーカスを取らないので、ほかのアプリの音楽は止めずに重ねて鳴らす
 * 対戦ごとに作り直さないよう、アプリ全体で 1 つだけ使う
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

  /** 通常攻撃の音。毎回同じ音だと単調なので、高さを少しずつ変えたものを用意して、順に鳴らす */
  private val normalHits by lazy { listOf(1.0, 1.12, 0.92).map { Clip(makeHit(it, length = 0.12, decay = 30.0)) } }
  /** フィーバー中は、高く明るい音にキラッとした音を重ねる */
  private val feverHits by lazy { listOf(1.4, 1.55, 1.3).map { Clip(makeHit(it, length = 0.18, decay = 25.0, sparkle = 1800.0)) } }
  /** 必殺技の音。低く、長く響かせる */
  private val specialHit by lazy { Clip(makeHit(0.7, length = 0.35, decay = 10.0)) }
  /** とどめの音。さらに低く、長く響かせる */
  private val finishHit by lazy { Clip(makeHit(0.5, length = 0.8, decay = 5.0, sparkle = 1200.0)) }
  /** フィーバー開始：ドミソドと駆け上がる */
  private val feverStart by lazy { Clip(makeMelody(listOf(523.0 to 0.07, 659.0 to 0.07, 784.0 to 0.07, 1047.0 to 0.35))) }
  /** チャンスの帯の色が上がる音：色ごとに 1 段ずつ高くなる「ピロン」。金は長く響かせる */
  private val chanceSteps by lazy {
    mapOf(
      FeverLottery.Level.BLUE to Clip(makeMelody(listOf(523.0 to 0.05, 784.0 to 0.15))),
      FeverLottery.Level.GREEN to Clip(makeMelody(listOf(659.0 to 0.05, 988.0 to 0.15))),
      FeverLottery.Level.RED to Clip(makeMelody(listOf(784.0 to 0.05, 1175.0 to 0.2), Wave.SQUARE)),
      FeverLottery.Level.GOLD to Clip(makeMelody(listOf(1047.0 to 0.05, 1568.0 to 0.05, 2093.0 to 0.4), Wave.SQUARE)),
    )
  }
  /** 帯が砕ける音：低く「ガシャン」 */
  private val chanceBreak by lazy { Clip(makeHit(0.4, length = 0.3, decay = 12.0)) }
  /** 予告：高い音と低い音を 2 回くり返す警報 */
  private val danger by lazy { Clip(makeMelody(listOf(880.0 to 0.12, 660.0 to 0.12, 880.0 to 0.12, 660.0 to 0.2), Wave.SQUARE)) }
  /** 勝利：ファンファーレ */
  private val victory by lazy {
    Clip(makeMelody(listOf(523.0 to 0.12, 523.0 to 0.12, 523.0 to 0.12, 659.0 to 0.3, 587.0 to 0.15, 784.0 to 0.7)))
  }
  private var nextHit = 0

  /**
   * 攻撃の音と、それ以外（予告や勝利）の音で、今鳴っている音。分けておくと、連打していても予告や勝利の音が途中で切れない
   * 同じ組の次の音を鳴らすときは、前の音を止めてすぐに鳴らす
   */
  private var playingHit: Clip? = null
  private var playingJingle: Clip? = null

  fun play(context: Context, sound: Sound) {
    val audioManager = context.getSystemService(AudioManager::class.java)
    if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

    val isHit: Boolean
    val clip: Clip
    when (sound) {
      Sound.Hit -> { isHit = true; clip = nextClip(normalHits) }
      Sound.FeverHit -> { isHit = true; clip = nextClip(feverHits) }
      Sound.SpecialHit -> { isHit = true; clip = specialHit }
      Sound.FinishHit -> { isHit = true; clip = finishHit }
      is Sound.ChanceStep -> { isHit = false; clip = chanceSteps.getValue(sound.level) }
      Sound.ChanceBreak -> { isHit = false; clip = chanceBreak }
      Sound.FeverStart -> { isHit = false; clip = feverStart }
      Sound.Danger -> { isHit = false; clip = danger }
      Sound.Victory -> { isHit = false; clip = victory }
    }
    if (isHit) {
      playingHit?.stop()
      playingHit = clip
    } else {
      playingJingle?.stop()
      playingJingle = clip
    }
    clip.play()
  }

  private fun nextClip(clips: List<Clip>): Clip {
    nextHit = (nextHit + 1) % clips.size
    return clips[nextHit]
  }

  /**
   * 1 つの音。最初に鳴らすときに AudioTrack を作り、あとは同じものを頭から鳴らし直す
   * 全部の音を一度に作ると、最初に当てたときに少し引っかかるので、音ごとに必要になってから作る
   */
  private class Clip(private val samples: ShortArray) {
    private var track: AudioTrack? = null

    fun play() {
      try {
        val current = track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: run {
          track?.release()
          makeTrack().also { track = it }
        }
        current.stop()
        current.reloadStaticData()
        current.play()
      } catch (_: RuntimeException) {
        // 音の出力が使えなくなったときなど。次に鳴らすときに作り直す（効果音が鳴らなくても対戦は続けられる）
        track?.release()
        track = null
      }
    }

    fun stop() {
      try {
        track?.stop()
      } catch (_: IllegalStateException) {
      }
    }

    private fun makeTrack(): AudioTrack {
      val track = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples.size * 2)
        .build()
      track.write(samples, 0, samples.size)
      return track
    }
  }

  /**
   * 打撃音を作る。低い「ドッ」と短い「パシッ」を重ねる
   * pitch を大きくすると高い音、decay を大きくすると早く消える音になる。sparkle を渡すと、その高さのキラッとした音を重ねる
   */
  private fun makeHit(pitch: Double, length: Double, decay: Double, sparkle: Double? = null) = makeSamples(length) { t ->
    // 低い「ドッ」：高い音から低い音へ一気に下げる。音の高さが変わるので、高さを足し合わせて位相にする
    val phase = 2 * PI * pitch * (70 * t + 160 * (1 - exp(-t * 40)) / 40)
    val thump = sin(phase) * exp(-t * decay)
    // 短い「パシッ」：ノイズをすぐに消す
    val crack = Random.nextDouble(-1.0, 1.0) * exp(-t * 90)
    val shine = sparkle?.let { sin(2 * PI * it * t) * 0.3 * exp(-t * 15) } ?: 0.0
    (0.7 * thump + 0.45 * crack + shine) * 0.8
  }

  private enum class Wave { SINE, SQUARE }

  /** 音の高さ（Hz）と長さ（秒）の並びから、短いメロディを作る */
  private fun makeMelody(notes: List<Pair<Double, Double>>, wave: Wave = Wave.SINE): ShortArray {
    val starts = notes.runningFold(0.0) { start, (_, length) -> start + length }
    return makeSamples(starts.last()) { t ->
      val index = starts.indexOfLast { it <= t }
      if (index !in notes.indices) return@makeSamples 0.0
      val (frequency, length) = notes[index]
      val local = t - starts[index]
      val tone = sin(2 * PI * frequency * local)
      val shaped = when (wave) {
        Wave.SINE -> tone + 0.3 * sin(4 * PI * frequency * local) // 倍音を足して明るくする
        Wave.SQUARE -> if (tone >= 0) 0.5 else -0.5
      }
      // 音ごとに、鳴り始めと終わりで音を小さくして、つなぎ目でプツッと鳴らないようにする
      val envelope = min(local / 0.005, 1.0) * min((length - local) / 0.02, 1.0)
      shaped * envelope * 0.35
    }
  }

  /** 時刻（秒）ごとの音の大きさ（-1〜1）から、音のデータを作る */
  private fun makeSamples(length: Double, sample: (Double) -> Double): ShortArray =
    ShortArray((length * SAMPLE_RATE).toInt()) { i ->
      val t = i.toDouble() / SAMPLE_RATE
      // 鳴り始めにプツッと音が出ないよう、最初の 2ms で音を大きくする
      val attack = min(t / 0.002, 1.0)
      ((sample(t) * attack).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
    }
}
