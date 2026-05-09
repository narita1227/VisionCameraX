# VisionCameraX

Android CameraX の映像を Python で受信・処理して戻すための分離プロジェクト。

## 現在の実装
- Python WebSocket サーバー実装済み
- Android 側 CameraX クライアント実装済み (`app/`)

## Android ビルド

```powershell
cd VisionCameraX
.\gradlew.bat :app:assembleDebug
```

## Python 起動

```powershell
cd VisionCameraX\python-server
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

WS endpoint:
- `ws://0.0.0.0:8770/ws/video`
