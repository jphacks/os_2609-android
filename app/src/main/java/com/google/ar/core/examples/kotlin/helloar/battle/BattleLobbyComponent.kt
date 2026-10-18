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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster

/** 対戦前の画面。自分のモンスターを見せ、「バトルする」で対戦相手を探す */
@Composable
fun BattleLobbyComponent(
  myMonster: BattleMonster,
  /** 対戦相手を探している */
  isSearching: Boolean,
  /** 相手探しをやめて、片付けが終わるのを待っている */
  isCancelling: Boolean,
  /** 対戦できなかったとき（通信の失敗、相手が抜けた）のメッセージ */
  errorMessage: String?,
  onFind: () -> Unit,
  onCancel: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp),
    modifier = Modifier
      .fillMaxSize()
      .background(BattleStyle.ink)
      .padding(24.dp),
  ) {
    Text(
      "MONSTER BATTLE",
      style = BattleStyle.display(44.sp),
      color = BattleStyle.yellow,
      modifier = Modifier.padding(top = 24.dp),
    )
    MonsterModelComponent(myMonster, facingDegrees = -25f, modifier = Modifier.size(240.dp))
    Text(
      myMonster.name,
      style = BattleStyle.display(28.sp),
      color = Color.Black,
      modifier = Modifier.background(BattleStyle.cyan, slantedRectangle()).padding(horizontal = 16.dp),
    )
    Text(
      "「バトルする」を押した人どうしで対戦できます。画面をタップして攻撃しよう",
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
    } else {
      BattleButtonComponent("バトルする", onClick = onFind)
    }
  }
}
