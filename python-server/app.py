import asyncio
import base64
from collections import deque
import json
import logging
import time

import cv2
import numpy as np
import websockets
from websockets.exceptions import ConnectionClosed

HOST = "0.0.0.0"
WS_PORT = 8770

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("vision_ws")


def now_ms() -> int:
    return int(time.time() * 1000)


def envelope(msg_type: str, source: str, seq: int, payload: dict) -> str:
    return json.dumps(
        {
            "type": msg_type,
            "source": source,
            "timestamp_ms": now_ms(),
            "seq": seq,
            "payload": payload,
        },
        ensure_ascii=False,
    )


def process_frame_bytes(raw: bytes) -> bytes | None:
    try:
        arr = np.frombuffer(raw, dtype=np.uint8)
        frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if frame is None:
            return None

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        out = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        cv2.putText(
            out,
            time.strftime("%H:%M:%S"),
            (12, 32),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.8,
            (255, 255, 255),
            2,
        )

        ok, encoded = cv2.imencode(".jpg", out, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
        if not ok:
            logger.warning("cv2.imencode returned false")
            return None
        return encoded.tobytes()
    except Exception:
        logger.exception("process_frame failed")
        return None


def process_frame(jpeg_b64: str) -> bytes | None:
    try:
        raw = base64.b64decode(jpeg_b64)
    except Exception:
        logger.exception("base64 decode failed")
        return None
    return process_frame_bytes(raw)


async def send_result_binary(
    websocket,
    seq: int,
    processed_jpeg: bytes,
    process_latency_ms: int,
    capture_ts_ms: int | None,
):
    payload = {
        "process_latency_ms": process_latency_ms,
    }
    if capture_ts_ms is not None:
        payload["capture_ts_ms"] = capture_ts_ms

    await websocket.send(envelope("video_result_meta", "python-server", seq, payload))
    await websocket.send(processed_jpeg)


async def ws_handler(websocket):
    frame_count = 0
    pending_meta: deque[tuple[int, int | None]] = deque()
    client = getattr(websocket, "remote_address", None)
    logger.info("client connected: %s", client)
    try:
        async for raw in websocket:
            if isinstance(raw, bytes):
                if not pending_meta:
                    logger.warning("received binary frame without metadata from %s", client)
                    continue

                seq, capture_ts_ms = pending_meta.popleft()
                t0 = now_ms()
                result_jpeg = process_frame_bytes(raw)
                if result_jpeg is None:
                    logger.warning("binary frame decode/process failed from %s (seq=%s)", client, seq)
                    continue

                frame_count += 1
                if frame_count % 30 == 0:
                    logger.info(
                        "frames received=%d latest_seq=%d latest_latency_ms=%d",
                        frame_count,
                        seq,
                        now_ms() - t0,
                    )

                await send_result_binary(
                    websocket=websocket,
                    seq=seq,
                    processed_jpeg=result_jpeg,
                    process_latency_ms=now_ms() - t0,
                    capture_ts_ms=capture_ts_ms,
                )
                continue

            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                logger.warning("invalid JSON from %s", client)
                continue

            if msg.get("type") == "video_frame_meta":
                seq = int(msg.get("seq", 0))
                payload = msg.get("payload", {})
                capture_ts_ms = payload.get("capture_ts_ms")
                if isinstance(capture_ts_ms, (int, float)):
                    capture_ts_ms = int(capture_ts_ms)
                else:
                    capture_ts_ms = None

                pending_meta.append((seq, capture_ts_ms))
                if len(pending_meta) > 8:
                    logger.warning("metadata queue is growing: size=%d", len(pending_meta))
                continue

            if msg.get("type") != "video_frame":
                continue

            seq = int(msg.get("seq", 0))
            payload = msg.get("payload", {})
            jpeg_b64 = payload.get("jpeg_base64")
            if not isinstance(jpeg_b64, str):
                logger.warning("missing jpeg_base64 from %s (seq=%s)", client, seq)
                continue

            t0 = now_ms()
            result_jpeg = process_frame(jpeg_b64)
            if result_jpeg is None:
                logger.warning("frame decode/process failed from %s (seq=%s)", client, seq)
                continue

            frame_count += 1
            if frame_count % 30 == 0:
                logger.info(
                    "frames received=%d latest_seq=%d latest_latency_ms=%d",
                    frame_count,
                    seq,
                    now_ms() - t0,
                )

            capture_ts_ms = payload.get("capture_ts_ms")
            if isinstance(capture_ts_ms, (int, float)):
                capture_ts_ms = int(capture_ts_ms)
            else:
                capture_ts_ms = None

            await send_result_binary(
                websocket=websocket,
                seq=seq,
                processed_jpeg=result_jpeg,
                process_latency_ms=now_ms() - t0,
                capture_ts_ms=capture_ts_ms,
            )
    except ConnectionClosed:
        logger.info("client disconnected: %s", client)
        return
    except Exception:
        logger.exception("ws_handler crashed for client=%s", client)
        return


async def main():
    logger.info("VisionCameraX WS: ws://%s:%d/ws/video", HOST, WS_PORT)
    async with websockets.serve(ws_handler, HOST, WS_PORT):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
