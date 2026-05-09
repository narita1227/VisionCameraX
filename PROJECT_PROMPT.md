# VisionCameraX Prompt

あなたは VisionCameraX の実装担当です。以下の要件を実装してください。

## 目的
- Android CameraX で映像取得
- Python で受信して処理
- Android に戻して表示

## 通信
- 映像とメタデータは WebSocket を使用
- メッセージ形式は envelope を統一

```json
{
  "type": "video_frame|video_result|status",
  "source": "android|python-server",
  "timestamp_ms": 1710000000000,
  "seq": 1,
  "payload": {}
}
```

## 受け入れ条件
1. 往復パイプラインが成立する
2. P95 遅延を計測できる
3. 再接続時に復帰できる
