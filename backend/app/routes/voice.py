"""WebSocket relay: app audio in, Meta transcripts out. Nothing is persisted.

Protocol mirrors the app's existing transcriber: binary PCM frames up,
JSON transcript frames down, {"type":"endStream"} to finish input while
finals stream back, normal 1000 close at the end.
"""

import asyncio
import json
import logging
import time
import uuid

import jwt
from fastapi import APIRouter, WebSocket

from ..config import get_settings
from ..sessions import decode_access

logger = logging.getLogger("smartcess.voice")
router = APIRouter()

WS_AUTH_CLOSE = 4401
WS_BUSY_CLOSE = 4429
WS_UNAVAILABLE_CLOSE = 4502

active: set[str] = set()


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
                        await client.send_text(msg)
                        forwarded_any = True
                    # Binary Meta frames are unexpected; ignored.
                    meta_task = asyncio.ensure_future(_next_meta(meta))
                else:
                    if input_open:
                        if msg.get("bytes") is not None:
                            try:
                                await meta.send(msg["bytes"])
                            except Exception:
                                return
                        elif msg.get("text") is not None and _is_end_stream(
                            msg["text"]
                        ):
                            try:
                                await meta.send(msg["text"])
                            except Exception:
                                return
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
        logger.info("stt relay closed sub=%s", sub[:8])
