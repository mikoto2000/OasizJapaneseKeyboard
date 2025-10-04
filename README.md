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
  
フェーズ2（予告）: Mozc 辞書を用いた変換
- かな読み（preedit）を Space で候補展開、候補バーから選択確定
- 実装は Pure Kotlin。Mozc の辞書素データをビルド時に加工し、アプリは assets の TSV/独自バイナリ/SQLite を読み込む方式

辞書ファイル（暫定）
- 位置: `app/src/main/assets/dictionary/words.tsv`
- 形式: UTF-8, タブ区切り（`読み(ひらがな)\t表記\tコスト(任意)`）
- 例:
  - `わたし\t私\t100`
  - `にほん\t日本\t120`

将来の改善
- Mozc のテキスト辞書からビルド時に自動生成（Gradle タスク化）
- 大規模辞書では SQLite/メモリマップなどで常時全件ロードを避ける

ライセンス

このプロジェクトは MIT License のもとで公開されています。詳細は `LICENSE` を参照してください。

貢献

Issue/PR 歓迎です。提案や改善点があればお気軽にお知らせください。
