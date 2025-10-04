OasizJapaneseKeyboard
=====================

Android 向けの日本語フルキーボード（IME）を目指すプロジェクトです。現在は JIS 配列ベースのローマ字キーボードを実装し、最小限の入力操作が可能です。

![Screenshot_20250921-134241_Finder](https://github.com/user-attachments/assets/d79e811d-0766-488c-ad82-9e4874b3100e)


特長（現状）
- JIS ローマ字配列のフルキーボード UI（XML レイアウト）
- IME 登録・切替に対応（システム設定から有効化）
- Shift トグル（ON/OFF 表示、英字の大/小と記号のシフトに対応）
- Ctrl トグル（英字には `META_CTRL_ON` の KeyEvent を送出。対応アプリでショートカット動作）
- キーリピート（長押しで連続入力。Ctrl/Shift 以外のキーに適用）
- キー押下フィードバック切替（スペース左の「FX」ボタンで、押下時の色変化のON/OFFを切替）
- ローマ字かな合成と変換候補バー（「A/あ」ボタンで切替、Spaceで候補展開/循環、Enter/タップで確定）
- Mozc 辞書由来の変換（Pure Kotlin、TSV 辞書を assets から読み込み）
 - 学習による並び替え（確定履歴を端末内DBに保存し、頻度の高い候補を優先）

注意
- Ctrl 付き KeyEvent を受け取らないアプリもあります。
- 記号のシフト結果は暫定マップです（JIS 厳密化は今後対応）。

対応環境
- minSdk 30 / targetSdk 36
- Android Studio (Electric Eel 以降推奨)

セットアップとビルド
1. このリポジトリを Android Studio で開く
2. 実機またはエミュレータを用意
3. Run/Debug から `app` を起動

コマンドライン（任意）
```
./gradlew assembleDebug
```

辞書生成（実運用向け）
- 入力: UTF-8 TSV（`読み(ひらがな)\t表記\tコスト(任意)`）ファイル群
- 置き場所: 既定 `tools/dict-src/`（変更は `-PdictSrc=/path/to/src`）
- 実行:
```
./gradlew :app:generateDictionary -PdictSrc=tools/dict-src -PmaxPerKey=50
```
- 出力: `app/src/main/assets/dictionary/words.tsv`
- 仕様: 全 TSV をマージし (読み,表記) で重複排除。コスト最小を採用。読みはカタカナをひらがなへ正規化。読みごとにコスト昇順で最大 `maxPerKey` 件まで出力。

SQLite 辞書の利用について
- アプリは優先的に SQLite 辞書（`assets/dictionary/words.db`）を使用します。存在しない場合は TSV（`assets/dictionary/words.tsv`）から初回起動時に DB を端末内に構築します（大規模辞書では時間がかかるため、事前に DB を用意することを推奨）。
- 既存の TSV 運用から移行する場合は、まず TSV を生成し、必要なら端末初回起動時に自動構築させるか、下記の「DB パック」手順で prebuilt DB を同梱してください。

SQLite 辞書（prebuilt）を assets へ同梱（推奨）
```
# 既存のDBをコピーする場合
./gradlew :app:packSqliteDictionary -Pdb=/absolute/or/relative/path/to/words.db

# TSV からDBを生成して同梱する場合（単一ファイル）
./gradlew :app:packSqliteDictionary -Ptsv=tools/dict-src/mozcdict.tsv

# TSV ディレクトリ（複数ファイル）から生成して同梱する場合
./gradlew :app:packSqliteDictionary -PdictSrc=tools/dict-src
```
- 入力のDBはスキーマ: `entries(reading TEXT, word TEXT, cost INTEGER, PRIMARY KEY(reading, word))`、インデックス: `CREATE INDEX idx_entries_reading ON entries(reading)` を想定しています。
 - `-Ptsv` または `-PdictSrc` の場合、このスキーマで DB を生成して `app/src/main/assets/dictionary/words.db` に出力します。

Mozc テキスト辞書 → 指定TSVへの変換
- 入力: Mozc系のテキスト辞書（.txt/.tsv/.csv）。行区切りは改行、区切りはタブ/カンマ（自動判定）。
- 列の想定（自動判定）:
  - よくある順: `表記, 読み, 品詞, コスト` または `読み, 表記, コスト` または `読み, 表記`
  - かな判定で「読み」列を推定（ひらがな/カタカナ/ー・・等のみ）。コストは数値列があれば使用、なければ既定 1000。
- 実行:
```
./gradlew :app:convertMozcDict -PmozcSrc=/path/to/mozc_text_dir -Pout=tools/dict-src/mozcdict.tsv
./gradlew :app:generateDictionary -PdictSrc=tools/dict-src -PmaxPerKey=50
```
- 備考: `-PmozcSrc` はファイル/ディレクトリいずれも可。複数ファイルはマージされ、(読み, 表記) で重複排除（最小コスト採用）。

有効化と切替
- 設定 → システム → 言語と入力 → 画面上のキーボード → キーボードの管理 → OasizJapaneseKeyboard をオン
- エディタでフォーカスし、地球儀/キーボードアイコンから OasizJapaneseKeyboard を選択

使い方（現状の挙動）
- 文字入力: 英字は `commitText`、記号は Shift 状態に応じた文字を入力
- Shift: クリックで ON/OFF。ラベルに「Shift / Shift ON」を表示
- Ctrl: クリックで ON/OFF。ラベルに「Ctrl / Ctrl ON」を表示（英字に対し `META_CTRL_ON` を送出）
- 長押しリピート: Ctrl/Shift 以外のキー（英字/記号/Space/Enter/Backspace）で有効
- 言語切替（A/あ）: デフォルトは英数（A）。「あ」にするとローマ字入力をかなに合成し、編集中テキスト（preedit）として表示。
  - Space: 合成中なら候補バー展開（Mozc 連携前は簡易辞書）。候補表示中は Space で選択位置を循環。
  - Enter: 候補表示中は選択候補を確定。表示していなければ合成を確定。
  - タップ: 候補をタップで確定。
  - Backspace: 候補表示中は候補を閉じて読み（かな）に戻る。候補未表示なら読みから1文字削除。
  - 記号/矢印/ESC/TAB/Fキー: 候補表示中/合成中はいずれも先に確定してからキーイベント送出。
  - かなモード中は Shift/Ctrl は無効（将来的にカタカナ切替など拡張検討）。
  - 前方一致サジェスト: 読みの完全一致候補に加え、読みで前方一致する語も提示（例: 読み「とう」→「東京」など）。
- セグメント変換（部分変換）: 読みを文節に自動分割し、候補バー上部に文節チップを表示。←/→で文節移動、Spaceで当該文節の候補を巡回、タップで候補選択、Enterで全体確定。
  - 文節境界の調整: 文節行の左右ボタン（‹ / ›）で、フォーカス中文節と次の文節の境界を1文字ずつ移動できます（双方とも最低1文字は保持）。調整後は自動で候補/編集中テキストを更新します。

学習（頻度）について
- 候補確定時に「読み・候補」を端末内の学習テーブルに記録し、次回から頻度の高い候補を優先表示します。
- 完全一致・前方一致いずれの候補並びにも学習を反映します（頻度降順→辞書コスト昇順）。
- 学習データは端末内（アプリ内のSQLite DB）に保存され、配布する辞書DB（assets/dictionary/words.db）には変更を加えません。
- 現時点ではリセットUIは未提供です（必要であれば今後追加）。

主なファイル
- IME サービス: `app/src/main/java/dev/mikoto2000/oasizjapanesekeyboard/ime/JapaneseKeyboardService.kt`
- JIS 配列レイアウト: `app/src/main/res/layout/keyboard_jis_qwerty.xml`
- IME 設定 XML: `app/src/main/res/xml/ime_config.xml`
- マニフェスト: `app/src/main/AndroidManifest.xml`

今後の予定
- JIS 記号のシフトマップの厳密化 / 配列の微調整
- かな配列（JISかな）やフリック入力 UI の追加
- 候補バー、予測変換、辞書連携
- 視覚スタイル（選択状態の色、角丸、サイズ調整）の改善
  
Mozc 辞書を用いた変換（導入済み）
- かな読み（preedit）を Space で候補展開、候補バーから選択確定
- 実装は Pure Kotlin。Mozc の辞書素データをビルド時に加工し、アプリは assets の TSV（`app/src/main/assets/dictionary/words.tsv`）を読み込み

辞書ファイル（暫定）
- 位置: `app/src/main/assets/dictionary/words.tsv`
- 形式: UTF-8, タブ区切り（`読み(ひらがな)\t表記\tコスト(任意)`）
- 例:
  - `わたし\t私\t100`
  - `にほん\t日本\t120`

将来の改善
- 大規模辞書では SQLite/メモリマップなどで常時全件ロードを避ける
- 連接/文節変換（Viterbi）による精度向上、学習の導入

ライセンス

このプロジェクトは MIT License のもとで公開されています。詳細は `LICENSE` を参照してください。

Mozc 辞書のライセンスについて
- 本アプリは Mozc の辞書データをフォーマット変換（TSV/SQLite）して利用しています。Mozc は BSD-3-Clause ライセンスです。
- リポジトリには Mozc のライセンス文書と第三者帰属（NOTICE）を同梱しています。
  - `third_party/mozclib/LICENSE_mozc.txt`
  - `third_party/mozclib/NOTICE_mozc.txt`（現在はプレースホルダ。実配布時は Mozc 公式の NOTICE を配置してください）
- アプリ配布物にも同梱するため、`app/src/main/assets/licenses/` に同等のファイルを配置しています。
- アプリ内の「Licenses」画面から表示できます（起動画面の Licenses ボタン）。

注意
- 実運用配布時には、使用する Mozc バージョンに対応した公式の NOTICE（第三者ライセンス一覧）を `third_party/mozclib/NOTICE_mozc.txt` と `app/src/main/assets/licenses/NOTICE_mozc.txt` に差し替えてください。
- 「Google 日本語入力」等の商標は使用せず、Mozc/Google の承認を示唆しない表現を避けてください。

貢献

Issue/PR 歓迎です。提案や改善点があればお気軽にお知らせください。
