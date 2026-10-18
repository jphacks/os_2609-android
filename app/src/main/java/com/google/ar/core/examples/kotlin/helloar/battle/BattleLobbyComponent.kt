package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.monster.MonsterModelComponent

/**
 * 対戦前の画面。自分のモンスターを見せ、「バトルする」で対戦相手を探す
 * 野生のモンスターとのバトルでは、相手のモンスターと「VS」で並べ、「にげる」も置く
 */
@Composable
fun BattleLobbyComponent(
  myMonster: Monster,
  /** 対戦相手を探している */
  isSearching: Boolean,
  /** 相手探しをやめて、片付けが終わるのを待っている */
  isCancelling: Boolean,
  /** 対戦できなかったとき（通信の失敗、相手が抜けた）のメッセージ */
  errorMessage: String?,
  onFind: (() -> Unit)?,
  onCancel: () -> Unit,
  /** 野生のモンスターとのバトルなら、相手のモンスター。ほかのプレイヤーとの対戦なら null（相手は探すまでわからない） */
  wildMonster: Monster? = null,
  /** 「にげる」が押されたときに呼ぶ。野生のモンスターとのバトルのときだけ渡す（null ならボタンを出さない） */
  onFlee: (() -> Unit)? = null,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp),
    modifier = Modifier
      .fillMaxSize()
      // 野生のモンスターとのバトルでは、後ろで捕獲画面の AR が動いているので、カメラの映像が少し透けて見えるようにする
      .background(if (wildMonster != null) BattleStyle.ink.copy(alpha = 0.85f) else BattleStyle.ink)
      // 後ろの捕獲画面の AR に、タップを通さない（ボタン以外の所をタップしても、AR の中のものを触らない）
      .pointerInput(Unit) {}
      .padding(24.dp),
  ) {
    Text(
      "MONSTER BATTLE",
      style = BattleStyle.display(44.sp),
      color = BattleStyle.yellow,
      modifier = Modifier.padding(top = 24.dp),
    )
    if (wildMonster != null) {
      // iOS は自分と相手の 3D を並べるが、ここでは名前だけにする
      // 後ろで捕獲画面の AR（Filament の画面）が動いているので、3D（MonsterModelComponent、これも Filament）を重ねると前後が入れ替わることがある
      // AR を外すと AR セッションが閉じ、野生のモンスターがいた場所でバトルできなくなる
      Spacer(Modifier.weight(1f))
      LobbyNameComponent(myMonster.name, BattleStyle.cyan)
      Text("VS", style = BattleStyle.display(40.sp), color = BattleStyle.yellow)
      LobbyNameComponent(wildMonster.name, BattleStyle.pink)
    } else {
      MonsterModelComponent(myMonster, facingDegrees = -25f, modifier = Modifier.size(240.dp))
      LobbyNameComponent(myMonster.name, BattleStyle.cyan)
    }
    Text(
      wildMonster?.let { "野生の${it.name}が勝負をしかけてきた！勝つと捕まえられます。画面をタップして攻撃しよう" }
        ?: "マップ画面で対戦受付をオンにし、近くの相手と確認してから対戦します。",
      fontSize = 15.sp,
      color = Color.White.copy(alpha = 0.7f),
      textAlign = TextAlign.Center,
    )
    errorMessage?.let {
      Text(it, fontSize = 13.sp, color = BattleStyle.red)
    }

    Spacer(Modifier.weight(1f))

    if (isSearching) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = BattleStyle.pink)
        if (isCancelling) {
          Text("相手探しをやめています…", style = BattleStyle.display(20.sp), color = Color.White)
        } else {
          Text("対戦相手を待っています…", style = BattleStyle.display(20.sp), color = Color.White)
          BattleButtonComponent("やめる", color = Color.White, onClick = onCancel)
        }
      }
    } else if (onFind != null) {
      BattleButtonComponent("バトルする", onClick = onFind)
      onFlee?.let { BattleButtonComponent("にげる", color = Color.White, onClick = it) }
    } else {
      Text("次の対戦はマップ画面に戻って受付をオンにしてください", color = Color.White, textAlign = TextAlign.Center)
    }
  }
}

/** 対戦前の画面に出す、モンスターの名前の帯 */
@Composable
private fun LobbyNameComponent(name: String, color: Color) {
  Text(
    name,
    style = BattleStyle.display(28.sp),
    color = Color.Black,
    maxLines = 1,
    modifier = Modifier.background(color, slantedRectangle()).padding(horizontal = 16.dp),
  )
}
