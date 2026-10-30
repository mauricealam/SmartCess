"""Voice routes: push-to-talk HTTPS upload (live) + realtime relay (DEFERRED).

DEFERRED, not removed: the WebSocket relay below stays for the future
live-interim-transcription milestone. Talk currently uses POST /transcribe
because Meta 404s realtime/chat serving from this host's egress.

WebSocket relay: app audio in, Meta transcripts out. Nothing is persisted.

Protocol mirrors the app's existing transcriber: binary PCM frames up,
JSON transcript frames down, {"type":"endStream"} to finish input while
finals stream back, normal 1000 close at the end.
"""

import asyncio
import collections
import json
import logging
import time
import uuid

import httpx
import jwt
from fastapi import APIRouter, Depends, File, UploadFile, WebSocket

from ..config import get_settings
from ..deps import current_sub
from ..errors import AppError, ai_busy, ai_timeout, ai_unclear, ai_unavailable, map_meta_http, rate_limited
from ..meta import build_meta_client
from ..ratelimit import limits
from ..sessions import decode_access

logger = logging.getLogger("smartcess.voice")
router = APIRouter()

WS_AUTH_CLOSE = 4401
WS_BUSY_CLOSE = 4429
WS_UNAVAILABLE_CLOSE = 4502

active: set[str] = set()

STT_META_PATH = "/asr/transcribe"
# Meta's model resolution flaps the same way as chat (404 joins 5xx as
# retryable). Two tries: failed transcriptions are not billed.
STT_RETRYABLE = frozenset({404, 500, 502, 503, 504})
STT_RETRY_SLEEPS = (1.0,)


def _is_wav(data: bytes) -> bool:
    """RIFF/WAVE magic only; Meta validates the PCM details (mono 16-bit)."""
    return len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE"


def _meta_error_snippet(response: httpx.Response, limit: int = 200) -> str:
    try:
        text = response.text
    except Exception:
        return "<unreadable>"
    text = " ".join(text.split())
    return text[:limit] if text else "<empty>"


async def _meta_transcribe(wav: bytes, settings) -> httpx.Response:
    payload = {
        "mode": "PUSH_TO_TALK",
        "model": settings.stt_model,
        "audioEncoding": "WAV",
    }
    files = {
        "request": (None, json.dumps(payload), "application/json"),
        "audio": ("talk.wav", wav, "audio/wav"),
    }
    try:
        async with build_meta_client(settings.stt_timeout_s) as client:
            last: httpx.Response | None = None
            for sleep in (0.0, *STT_RETRY_SLEEPS):
                if sleep:
                    await asyncio.sleep(sleep)
                response = await client.post(
                    STT_META_PATH,
                    files=files,
                    headers={"Authorization": f"Bearer {settings.meta_api_key}"},
                )
                if response.status_code not in STT_RETRYABLE:
                    return response
                last = response
                logger.warning("meta stt status=%s", response.status_code)
            assert last is not None
            return last
    except httpx.TimeoutException:
        raise ai_timeout()
    except httpx.HTTPError as e:
        logger.warning("meta unreachable: %s", type(e).__name__)
        raise ai_unavailable()


@router.post("/transcribe")
async def transcribe(
    audio: UploadFile = File(...), sub: str = Depends(current_sub)
) -> dict:
    """Push-to-talk: WAV bytes in, transcript out. Nothing is persisted;
    audio and transcripts are never logged (sizes/counts only)."""
    settings = get_settings()
    if not limits.allow(f"stt:{sub}", settings.stt_per_minute, 60.0):
        raise rate_limited()
    if sub in active:
        raise ai_busy()
    if not settings.meta_api_key:
        logger.error("META_API_KEY is not configured")
        raise ai_unavailable()
    data = await audio.read(settings.max_audio_bytes + 1)
    if len(data) > settings.max_audio_bytes:
        raise AppError(
            "too_large",
            "That recording is too long. Please try a shorter one.",
            413,
        )
    if not _is_wav(data):
        raise AppError("bad_request", "Something went wrong. Please try again.", 400)
    active.add(sub)
    started = time.monotonic()
    try:
        response = await _meta_transcribe(data, settings)
    finally:
        active.discard(sub)
    if response.status_code == 429:
        raise ai_busy()
    if response.status_code != 200:
        logger.warning(
            "meta stt status=%s meta_error=%s",
            response.status_code,
            _meta_error_snippet(response),
        )
        raise map_meta_http(response.status_code)
    try:
        body = response.json()
        transcript = body.get("transcript", "")
        turns = body.get("turns", [])
        duration_ms = int(body.get("audioDurationMs", 0))
    except (ValueError, TypeError, AttributeError):
        raise ai_unclear()
    if not isinstance(transcript, str) or not transcript.strip():
        raise ai_unclear()
    turn_count = len(turns) if isinstance(turns, list) else 0
    logger.info(
        "stt file sub=%s bytes=%d ms=%d turns=%d %.0fms",
        sub[:8],
        len(data),
        duration_ms,
        turn_count,
        (time.monotonic() - started) * 1000,
    )
    return {
        "transcript": transcript.strip(),
        "audio_duration_ms": duration_ms,
        "turns": turn_count,
    }


class _Closed(Exception):
    pass


async def connect_meta(url: str):
    """Opens the Meta realtime socket. Seam for tests (fake implements send/recv/close)."""
    import websockets

    return await websockets.connect(url, max_size=8 * 1024 * 1024)


def _meta_url() -> str:
    base = get_settings().meta_stt_url
    sep = "&" if "?" in base else "?"
    return f"{base}{sep}sessionId=relay-{uuid.uuid4().hex}"


def _handshake(key: str) -> str:
    settings = get_settings()
    return json.dumps(
        {
            "authorization": {"accessToken": f"Bearer {key}"},
            "audioEncoding": "PCM_24KHZ",
            "model": settings.stt_model,
            "mode": "PUSH_TO_TALK",
        }
    )


def _is_end_stream(text: str) -> bool:
    try:
        return json.loads(text).get("type") == "endStream"
    except (ValueError, AttributeError):
        return False


def _transcript_kind(text: str) -> str | None:
    """Classifies a Meta frame for counting only; content never logged."""
    try:
        frame = json.loads(text)
    except ValueError:
        return None
    if not isinstance(frame, dict) or frame.get("type") != "transcript":
        return None
    return "final" if frame.get("final") else "partial"


async def _next_client(client: WebSocket):
    try:
        msg = await client.receive()
    except Exception as e:
        raise _Closed() from e
    if msg.get("type") == "websocket.disconnect":
        raise _Closed()
    return msg


async def _next_meta(meta):
    try:
        return await meta.recv()
    except Exception as e:
        raise _Closed() from e


async def _close_quietly(sock) -> None:
    try:
        await sock.close()
    except Exception:
        pass


@router.websocket("/realtime")
async def realtime(client: WebSocket):
    await client.accept()
    token = client.query_params.get("token")
    if not token:
        auth = client.headers.get("authorization", "")
        if auth.lower().startswith("bearer "):
            token = auth[7:].strip()
    try:
        sub = decode_access(token or "", get_settings().jwt_secret)
    except jwt.PyJWTError:
        sub = None
    if not sub:
        await client.close(code=WS_AUTH_CLOSE)
        return
    if sub in active:
        await client.close(code=WS_BUSY_CLOSE)
        return
    if not get_settings().meta_api_key:
        logger.error("META_API_KEY is not configured")
        await client.close(code=WS_UNAVAILABLE_CLOSE)
        return
    active.add(sub)
    try:
        await _relay(client, sub)
    finally:
        active.discard(sub)


async def _relay(client: WebSocket, sub: str) -> None:
    settings = get_settings()
    try:
        meta = await connect_meta(_meta_url())
    except Exception:
        logger.warning("meta stt connect failed")
        await client.close(code=WS_UNAVAILABLE_CLOSE)
        return
    try:
        await meta.send(_handshake(settings.meta_api_key))
    except Exception:
        logger.warning("meta stt handshake failed")
        await _close_quietly(meta)
        await client.close(code=WS_UNAVAILABLE_CLOSE)
        return
    logger.info("stt relay open sub=%s", sub[:8])
    deadline = time.monotonic() + settings.ws_max_s
    input_open = True
    forwarded_any = False
    # Docs: wait for Meta's handshake ack before sending audio, or early
    # frames are dropped. Hold client audio/endStream until the first Meta
    # text frame, then flush in order. Bounded: the ack lands in ms, and
    # the idle timeout bounds the wait even if it never comes.
    meta_acked = False
    pending: collections.deque = collections.deque(maxlen=128)
    partials = 0
    finals = 0
    # Persistent one-message tasks per side: a side's task is recreated only
    # after its message is consumed, so the loser's arrival is never dropped.
    meta_task = asyncio.ensure_future(_next_meta(meta))
    client_task = asyncio.ensure_future(_next_client(client))
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            done, _ = await asyncio.wait(
                [meta_task, client_task],
                timeout=min(settings.ws_idle_s, remaining),
                return_when=asyncio.FIRST_COMPLETED,
            )
            if not done:
                break  # idle timeout
            for task in done:
                try:
                    msg = task.result()
                except _Closed:
                    if task is meta_task and not forwarded_any:
                        # Meta dropped the stream before any transcript: tell the
                        # app so Talk shows an error instead of going silent.
                        logger.warning(
                            "meta stt closed before any transcript sub=%s", sub[:8]
                        )
                        try:
                            await client.send_text(
                                '{"type":"error","message":"stt_unavailable"}'
                            )
                        except Exception:
                            pass
                    return
                if task is meta_task:
                    if isinstance(msg, str):
                        if not meta_acked:
                            meta_acked = True
                            while pending:
                                try:
                                    await meta.send(pending.popleft())
                                except Exception:
                                    return
                        frame_kind = _transcript_kind(msg)
                        if frame_kind == "final":
                            finals += 1
                        elif frame_kind == "partial":
                            partials += 1
                        await client.send_text(msg)
                        forwarded_any = True
                    # Binary Meta frames are unexpected; ignored.
                    meta_task = asyncio.ensure_future(_next_meta(meta))
                else:
                    if input_open:
                        if msg.get("bytes") is not None:
                            if meta_acked:
                                try:
                                    await meta.send(msg["bytes"])
                                except Exception:
                                    return
                            else:
                                pending.append(msg["bytes"])
                        elif msg.get("text") is not None and _is_end_stream(
                            msg["text"]
                        ):
                            if meta_acked:
                                try:
                                    await meta.send(msg["text"])
                                except Exception:
                                    return
                            else:
                                pending.append(msg["text"])
                            input_open = False
                        # Other client text frames are ignored.
                    client_task = asyncio.ensure_future(_next_client(client))
    finally:
        for task in (meta_task, client_task):
            task.cancel()
        await asyncio.gather(meta_task, client_task, return_exceptions=True)
        await _close_quietly(meta)
        try:
            await client.close(code=1000)
        except Exception:
            pass
        logger.info(
            "stt relay closed sub=%s partials=%d finals=%d",
            sub[:8],
            partials,
            finals,
        )
