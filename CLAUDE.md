# Sunshine（Android）

iOS アプリ（[jphacks/os_2609-iOS](https://github.com/jphacks/os_2609-iOS)）の Android 版（ハッカソン用）。
HelloAR（ARCore のサンプル）をもとにしている。AR と 3D はすべて SceneView（Filament）+ Compose で描く。マルチバトルは iOS と同じ Supabase のデータベースで、iOS とも対戦できる。

## マルチバトル

- 作ったときの指示（iOS とそろえること、作り方）：[docs/multi-battle.md](docs/multi-battle.md)
- **iOS とそろえないと対戦できない**：行動の文字列、ルールの数値、モンスターの `modelName`、データベースの使い方。正は iOS のコードと `supabase/schema.sql`。iOS を変えたら、こちらも同じように変える。
- 入口：マップ画面（`map/MapActivity`）の「マルチバトル」ボタン → `battle/BattleActivity`

```
app/src/main/java/com/google/ar/core/examples/kotlin/helloar/
├── model/          データ。Monster（iOS と同じ 16 体。捕獲とバトルで共通）、MonsterAnimation、WildMonster など
│   └── battle/     バトルの中だけで使うデータ（BattleAction、Fighter、BattleState、Stamina）
├── logic/          ルール（BattleEngine、FeverLottery）。状態を受け取って計算するだけ
├── service/        通信と保存（BattleService、SupabaseBattleService、⚠️ 開発用の CPUBattleService、SupabaseConfig、MonsterCollection）
├── viewmodel/      CaptureViewModel、BattleViewModel など（androidx の ViewModel）
├── map/            マップ画面（View）。MapActivity
├── starter/        最初にモンスターを選ぶ画面（Compose）。StarterMonsterActivity → StarterMonsterScreen
├── capture/        捕獲画面（Compose）。CaptureScreen → AR か、捕獲成功の画面（MonsterDetailComponent）
│   └── ar/         AR（SceneView）。CaptureArComponent（Compose との橋渡し）、CaptureArSceneController（床・配置・歩き回る・タップ）
├── battle/         バトル画面（Compose）。BattleScreen → 対戦前は BattleLobbyComponent、対戦中は BattleFightComponent
│   └── ar/         AR（SceneView）。BattleArComponent（Compose との橋渡し）、BattleArSceneController（配置と演出）
└── monster/        3D モンスター（捕獲とバトルで共通）。loadMonsterNode（読み込みと高さ合わせ）、MonsterAnimator、
                    MonsterModelComponent（カメラ映像なしで 1 体出す）、MonsterArrowComponent（画面の外のモンスターを指す矢印）
```

- モンスターの 3D モデルは `assets/monsters/*.glb`（Quaternius「Ultimate Monsters」、CC0）。`scripts/convert_monster_models.py` で glTF から作る。
- 入口（捕獲）：マップ画面の「AR で捕獲する」→ `capture/CaptureActivity`。戻るボタンでマップ画面に戻る。
- AR（`ARSceneView`）と `MonsterModelComponent` は同時に出さない。Filament の画面が 2 つ重なると、前後が入れ替わったりカメラを取り合ったりする。捕獲画面は前の画面を外してから少し待って次を出す（`CaptureScreen`）。

## 最初のモンスター選び

- バトルに出すモンスターがまだ決まっていなければ、マップ画面（`map/MapActivity`）の代わりに `starter/StarterMonsterActivity` を開く。決めたら捕獲画面へ進み、捕獲画面から戻るとマップ画面になる
- 選んだモンスターは捕まえたものとして保存し、バトルに出すモンスターにもする（`MonsterCollection.setBattleMonster`）。iOS と同じく、バトルの自分のモンスターはまだランダムで、`battleMonster` は使っていない
- 保存先（`MonsterCollection`）はアプリで 1 つだけ作る（`ARAppApplication.monsterCollection`）

## プロジェクト設定

- AGP 9.2（Kotlin は AGP に組み込み。Kotlin のバージョンは `build.gradle` の `kotlin_version`）、Gradle 9.4、Java 21（SceneView が使う kotlin-math が Java 21 向けのため）。
- Supabase の接続先は `local.properties` に書く（コミットしない）。書いていなければ CPU が相手になる。
  ```properties
  supabase.url=https://xxxx.supabase.co
  supabase.anonKey=<anon（public）key>
  ```
  service_role（secret）の鍵は使わない。
- エミュレータには ARCore がないので、AR とバトルは実機で確かめる。

## ビルド確認

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

## 進め方

- ハッカソン用。ユニットテストや UI テストは書かない。画面に動作確認用のボタンも置かない。
- 開発中だけ使うコードは、ファイル先頭に「⚠️ 開発用」と書き、削除するときに一緒に消す場所も書く。
- 使われていないコードは残さず削除する。不要な抽象化はしない。
- コメントは日本語で「なぜそうするか」を書く。コミットメッセージは日本語で書く。
