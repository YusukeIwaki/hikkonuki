# Hikkonuki

Osmo Pocket 4P / Insta360 Ace Pro 2 を **USB** でAndroidにつなぎ、写真と動画をスマートフォンへ取り込むアプリです。
動画は2K（長辺2560）のHEVCに変換し、写真はそのままコピーして、`DCIM/Hikkonuki` に保存します。Googleフォトが見える場所なので、アップロードはGoogleフォトに任せます。

- パッケージ: `app.hikkonuki.android`
- 画面と操作は [OsmoPocketUsbShare](../OsmoPocketUsbShare) を引き継いでいます（一覧 / 詳細 / エクスポート状況）。

## 動画の変換

`osmopocketexample/slim_videos.py` と `insta360example/slim_videos.py` の方針を端末向けに移植しています。

| 項目 | 内容 |
| --- | --- |
| 解像度 | 長辺2560まで。縦横比を保ち、拡大しない（Insta360の1080pは1080pのまま） |
| コーデック | HEVC。10bit素材はMain10のまま（端末のエンコーダが対応している場合） |
| 縮小 | GPU上のLanczos-3（横→縦の2パス） |
| 色 | デコーダのY'CbCrをシェーダーで直接RGBに変換（`GL_EXT_YUV_target` 対応端末）。Insta360のフルレンジ素材のレンジを取り違えないため |
| ビットレート | 1080p30で8Mbps、1440p30で12Mbps。Osmo Pocketは元動画のビットレートが高い（＝絵が細かい）ほど最大4倍まで上げる |
| 音声 | 再エンコードせずコピー |
| 撮影日時 | コンテナの日時とファイル名の時刻を突き合わせて決定し、MP4の `mvhd` / `tkhd` / `mdhd` に書き込む。メーカー・機種名も記録 |
| 変換しない場合 | HDR/Log素材、圧縮しても20%以上減らない素材、変換に失敗した素材は元動画をそのまま取り込む |

変換は `direct-10bit` → `direct-8bit` → Media3 Transformer の順に試し、どれも失敗したら元動画を取り込みます。

### 撮影日時の決め方

Insta360で日時が正しく付かない問題への対策です。コンテナの日時（UTC）とファイル名の時刻（カメラの現地時刻）を比べます。

- 差がタイムゾーンとしてありえる値（15分単位） → コンテナの日時を採用。差はカメラのタイムゾーンとして記録
- 同じ数字 → DJIはコンテナ、それ以外はファイル名を端末のタイムゾーンで解釈
- それ以外（未設定の1904年/1970年など） → ファイル名を採用

Androidのメディアスキャナ（＝Googleフォトの並び順）は動画ファイル内の日時を読むため、公開前にファイルへ書き込んでいます。

## 構成

画面とエクスポート処理は互いを知りません。画面は `ExportController` の一覧（`jobs` / `batches`）を表示して、コマンドを送るだけです。

```
core/      Androidに依存しない純Kotlin。JVMの単体テストで網羅
  ExportQueue    エクスポートの一覧とルール（バッチ、重複排除、再開）
  ExportEngine   1件ずつ取り込む。中断・USB抜け・キャンセル・フォールバック
  SlimPlanner    解像度とビットレートの決定
  CaptureDate    撮影日時の決定
  Mp4            MP4メタデータの読み書き
platform/  Android実装（USBフォルダ、MediaStore、SQLite、MediaCodec+OpenGL、サービス）
ui/        Compose画面とViewModel（MediaLibrary / ExportController のみに依存）
```

### エクスポートの状態

元アプリと同じバッチ構造です。

- 実行中のバッチ（ACTIVE）は1件ずつの行を持ち、エクスポート状況に一覧表示されます。
- すべて終わるとバッチはDONEになり、行は削除されて「エクスポート結果」（N件中M件成功）だけが残ります。保持するバッチは最大2つです。
- キャンセルした行は削除されます。「履歴を消す」ですべて消えます。

中断への強さ:

- 状態はその都度SQLiteに保存。プロセスが落ちても、次回起動時に実行中だった行を待機に戻して続きから処理します。
- 出力はMediaStoreの保留（pending）ファイルに直接書くので、書きかけのファイルは他のアプリから見えません。残骸は次回起動時に削除します。
- USBの切断は元アプリと同じく、システムのブロードキャスト（`USB_DEVICE_DETACHED`、`MEDIA_EJECT` / `UNMOUNTED` / `REMOVED` / `BAD_REMOVAL`）と、元ファイルを1秒ごとに確認する監視（2回連続で開けなければ切断）の両方で検知します。
- 転送中にUSBが抜けると、処理中のファイルと待機中のファイルをまとめて失敗にしてバッチを終わらせます（4件中3件目で切れたら「4件中 2件成功、2件失敗」）。書きかけのファイルは残りません。つなぎ直して同じものをエクスポートし直すと、取り込み済みのファイルは再変換せずに済ませます。

## USBの読み取り

システムのフォルダ選択で一度だけカメラの `DCIM` を許可します。許可はSDカードごとに記憶するので、2台目のカメラも最初の1回だけです。ストレージ権限は使いません。

## ビルドとテスト

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug

# 単体テスト（JVM / Robolectric）: エクスポートのロジック、日時、MP4、ViewModel、画面
./gradlew :app:testDebugUnitTest

# 端末テスト: 実コーデックでの変換、MediaStore保存、日時、色、アプリ全体の流れ
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

端末テストはカメラなしで動きます。エミュレータではHEVCエンコーダが512×512まで・10bitデコード不可のため、次の3件はスキップされ、実機（Pixel）でだけ走ります。

- `directPipelineTenBitTwoK`（4K 10bit → 2K HEVC Main10）
- `tenBitColorsSurvive`
- `fullRangeColorsSurvive`（Insta360のフルレンジ。エミュレータはMedia3でも同じくレンジを取り違える）

実機で流す場合は、スマートフォンだけをつないで `ANDROID_SERIAL=<シリアル> ./gradlew :app:connectedDebugAndroidTest` を実行します。

### カメラなしで画面を触る

デバッグビルドは、アプリの `usb_inbox` フォルダに置いたファイルを接続中のカメラとして扱います。

```bash
adb push clip.mp4 /sdcard/Android/data/app.hikkonuki.android/files/usb_inbox/
```

### テスト用ファイルの作り直し

```bash
python3 tools/make_fixtures.py   # 実サンプルからヘッダだけ抜いたMP4（単体テスト用）
tools/make_test_videos.sh        # カメラの形式に似せた合成動画（端末テスト用）
python3 design/generate_icon.py  # アイコン
```
