# vision-camerax app

Android client for CameraX -> Python -> Android round-trip.

## What it does

- Captures camera frames with CameraX
- Sends frames to `python-server` over WS
- Receives processed frames over WS and overlays them

## Build

```powershell
.\gradlew :app:assembleDebug
```

## Optional local.properties keys

```properties
visioncamerax.server.realDeviceHost=192.168.x.x
visioncamerax.server.emulatorHost=10.0.2.2
visioncamerax.server.wsPort=8770
visioncamerax.server.host.override=
```
