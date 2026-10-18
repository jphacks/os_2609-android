package com.google.ar.core.examples.kotlin.helloar.model

import java.util.UUID

/**
 * 捕獲画面で出現している野生のモンスター 1 体
 * 同じ種類が出ても区別できるように、出現するたびに別の id が付く
 */
data class WildMonster(
  val monster: Monster,
  val id: UUID = UUID.randomUUID(),
)
