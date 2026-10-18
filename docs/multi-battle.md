# マルチバトルを Android に実装するためのプロンプト

iOS（[jphacks/os_2609-iOS](https://github.com/jphacks/os_2609-iOS)）の「マルチバトル」を Android に移すときに、AI（Claude Code など）に渡した指示です。
このリポジトリのマルチバトルは、この指示のとおりに作っています。直すときや、ほかの機能を同じように移すときの参考にしてください。

---

## 指示

iOS アプリのマルチバトル（Supabase で、iOS・Android のどちらの相手とも対戦できるリアルタイムバトル）を、この Android アプリに実装してください。
対戦中は、iOS と同じく AR の床の上でモンスター同士を戦わせてください。

### 最初に読むもの（iOS のリポジトリ、main ブランチ）

決まりはすべて iOS のコードにあります。迷ったら iOS に合わせてください。

| 何 | iOS のファイル |
|---|---|
| テーブルと関数 | `supabase/schema.sql` |
| ルール（数値・勝敗） | `ARApp/Logic/BattleEngine.swift`、`ARApp/Logic/FeverLottery.swift`、`ARApp/Models/Battle/*.swift` |
| 通信 | `ARApp/Services/BattleService.swift`、`SupabaseBattleService.swift`、`CPUBattleService.swift` |
| 進行 | `ARApp/ViewModels/BattleViewModel.swift` |
| 画面と演出 | `ARApp/Views/Battle/*.swift`、`ARApp/Views/Components/BattleStyle.swift` |
| AR | `ARApp/Views/Battle/AR/*.swift`、`ARApp/Views/Monster/*.swift` |
| モンスター | `ARApp/Models/Monster.swift`、`MonsterAnimation.swift`、`MonsterAnimationRequest.swift` |

### iOS と必ずそろえること（違うと対戦できない・HP が食い違う）

- データベース（テーブル・関数・RLS）は変えない。正は iOS の `supabase/schema.sql`。
- マッチングは場所を問わない。`join_battle(p_player_id, p_monster)` で入り、部屋を作った側は 3 秒ごとに `keep_waiting(p_battle_id)` を呼ぶ。待つのはキャンセルされるまで（時間切れはない）。
- 行動の文字列：`normalAttack` / `specialAttack` / `fever` / `feverAttack` / `takeSpecial` / `dodgeSpecial`。
- ルールの数値：HP 200、通常攻撃 10、ゲージ +10（100 で必殺技を自動で撃つ）、必殺技 60、よけたら 15、フィーバーは通常攻撃 5 回ごとに抽選・5 秒・2 倍、クールダウン 0.3 秒、予告 1 秒。
- スタミナ（攻撃・よけるで 20 減り、1 秒に 25 回復、使い切ると 2.5 秒休憩）とクールダウン、フィーバーの時間は入力した端末だけで数える。
- 必殺技の結果は撃たれた側が送る（予告の 1 秒の間に左右にスワイプしたら `dodgeSpecial`、しなければ `takeSpecial`）。
- モンスターは iOS と同じ 16 体・同じ `modelName`（`monster-alien` など）。相手にはこの名前を送る。
- 決着は `finish_battle` で先に記録した方に合わせる。

### Android での作り方

- 画面は Jetpack Compose、AR と 3D は SceneView（`io.github.sceneview:arsceneview`）を使う。今ある HelloAR の画面には「マルチバトル」ボタンだけ置き、`BattleActivity` を開く。
- モンスターの 3D モデルは、iOS と同じ Quaternius「Ultimate Monsters」の glTF を glb にまとめて `assets/monsters/` に置く（`scripts/convert_monster_models.py`）。
  捕獲画面の `model.Monster`（Kenney の動物）とは別に、バトル用の `BattleMonster` を作る（`modelName` が iOS と違うと対戦できないため）。
- アニメーションは glb の中の名前（`Idle`、`Punch` など）で選ぶ。切り替えるときは 0.2 秒混ぜる（iOS の `MonsterAnimator` と同じ使い方にする）。
- 床が見つかったら、カメラの前 1.1m に自分（左に 0.35m）、2.5m に相手（右に 0.35m）を置いて向かい合わせる。
- iOS の火花・金色の光の粒は SceneView では出せないので、モンスターの材質を光らせて代わりにする（白：攻撃が当たった、赤の点滅：相手の必殺技の予告、金：フィーバー中）。
- 効果音はまだ作らない（振動だけ）。
- Supabase の URL と anon key は `local.properties`（コミットしない）に書く。書いていなければ CPU が相手になる（`CPUBattleService`、⚠️ 開発用）。

### 進め方

- iOS と同じ分け方にし、型名・関数名・定数名もそろえる（見比べて直せるように）。コメントは日本語で、iOS と同じくらい「なぜそうするか」を書く。
- ユニットテストや UI テストは書かない。画面に動作確認用のボタンも置かない。
- 開発中だけ使うコードには「⚠️ 開発用」と書き、消すときに一緒に消す場所も書く。
- 使われていないコードは残さない。コミットメッセージは日本語。
- 終わったら、変更したファイルとその役割、実機で確かめる手順を説明する。
