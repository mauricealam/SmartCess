"""AI proxy: transparent chat completions + server-composed bill extraction.

/chat returns an OpenAI-compatible subset so the Android client keeps its
existing response parsing unchanged; only the base URL and auth change.
"""

import asyncio
import json
import logging
import time

import httpx
from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field

from ..config import get_settings
from ..deps import current_sub
from ..errors import (
    AppError,
    ai_busy,
    ai_unavailable,
    ai_unclear,
    ai_timeout,
    map_meta_http,
    rate_limited,
)
from ..meta import build_meta_client
from ..ratelimit import limits

logger = logging.getLogger("smartcess.ai")
router = APIRouter()

BILL_PROMPT = (
    "You read a photographed bill for a blind user. Extract exactly this JSON "
    "object and nothing else: {provider: string, amount: number, currency: "
    "3-letter code, due_date: YYYY-MM-DD or empty string, account_reference: "
    "string or null, billing_period_start: YYYY-MM-DD or null, "
    "billing_period_end: YYYY-MM-DD or null, required_action: one of "
    "pay_before_due_date|review_bill|contact_provider|no_action_required, "
    "confidence: 0..1, missing_fields: string list, summary: one sentence}. "
    "Never invent values; leave unknown fields empty and list them."
)


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str | None = None
    messages: list[dict]
    max_tokens: int | None = Field(default=None, gt=0, le=8192)
    temperature: float | None = Field(default=None, ge=0.0, le=2.0)
    response_format: dict | None = None
    tools: list[dict] | None = None
    tool_choice: str | None = None


class BillExtractRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    image_base64: str
    ocr_hint: str | None = None


def _headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {get_settings().meta_api_key}"}


def _require_key() -> None:
    if not get_settings().meta_api_key:
        logger.error("META_API_KEY is not configured")
        raise ai_unavailable()


def _check_rate(sub: str, bucket: str, limit: int) -> None:
    if not limits.allow(f"{bucket}:{sub}", limit, 60.0):
        raise rate_limited()


# Meta's model resolution flaps: a model can 404 model_not_found for
# minutes and then 200 again, so 404 joins 5xx as retryable (3 tries).
RETRYABLE_META = frozenset({404, 500, 502, 503, 504})
META_RETRY_SLEEPS = (1.0, 2.0)


async def _post_chat(payload: dict) -> httpx.Response:
    try:
        async with build_meta_client() as client:
            last: httpx.Response | None = None
            for attempt, sleep in enumerate((0.0, *META_RETRY_SLEEPS)):
                if sleep:
                    await asyncio.sleep(sleep)
                response = await client.post(
                    "/chat/completions", json=payload, headers=_headers()
                )
                if response.status_code not in RETRYABLE_META:
                    return response
                last = response
                logger.warning(
                    "meta chat status=%s attempt=%d",
                    response.status_code,
                    attempt + 1,
                )
            assert last is not None
            return last
    except httpx.TimeoutException:
        raise ai_timeout()
    except httpx.HTTPError as e:
        logger.warning("meta unreachable: %s", type(e).__name__)
        raise ai_unavailable()


def _first_content(body: dict) -> tuple[str, str | None, dict]:
    try:
        choice = body["choices"][0]
        message = choice["message"]
        content = message["content"]
        if isinstance(content, list):
            content = "".join(
                part.get("text", "") for part in content if isinstance(part, dict)
            )
        usage = body.get("usage") or {}
        return (
            content,
            choice.get("finish_reason"),
            {
                "prompt_tokens": int(usage.get("prompt_tokens", 0)),
                "completion_tokens": int(usage.get("completion_tokens", 0)),
            },
        )
    except (KeyError, IndexError, TypeError, ValueError):
        raise ai_unclear()


@router.post("/chat")
async def chat(body: ChatRequest, sub: str = Depends(current_sub)) -> dict:
    settings = get_settings()
    _check_rate(sub, "chat", settings.chat_per_minute)
    if not body.messages or not all(
        isinstance(m, dict) and "role" in m and "content" in m for m in body.messages
    ):
        raise AppError("bad_request", "Something went wrong. Please try again.", 400)
    model = body.model or settings.default_model
    if model not in settings.model_allowlist:
        raise AppError("bad_request", "Something went wrong. Please try again.", 400)
    _require_key()
    payload = body.model_dump(exclude_none=True, mode="json")
    payload["model"] = model
    started = time.monotonic()
    response = await _post_chat(payload)
    if response.status_code == 429:
        raise ai_busy()
    if response.status_code != 200:
        logger.warning("meta chat status=%s", response.status_code)
        raise map_meta_http(response.status_code)
    try:
        content, finish_reason, usage = _first_content(response.json())
    except ValueError:
        raise ai_unclear()
    logger.info(
        "chat sub=%s model=%s %.0fms in=%d out=%d",
        sub[:8],
        model,
        (time.monotonic() - started) * 1000,
        usage["prompt_tokens"],
        usage["completion_tokens"],
    )
    return {
        "choices": [
            {
                "message": {"role": "assistant", "content": content},
                "finish_reason": finish_reason,
            }
        ],
        "usage": usage,
    }


def _bill_messages(image_b64: str, ocr_hint: str | None, retry: bool) -> list[dict]:
    prompt = BILL_PROMPT
    if ocr_hint:
        prompt += f"\nOCR hint: {ocr_hint[:2000]}"
    if retry:
        prompt += "\nReturn valid JSON only."
    return [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"},
                },
            ],
        }
    ]


@router.post("/bill-extract")
async def bill_extract(
    body: BillExtractRequest, sub: str = Depends(current_sub)
) -> dict:
    settings = get_settings()
    _check_rate(sub, "bill", settings.bill_per_minute)
    if len(body.image_base64) > settings.max_image_b64_chars:
        raise AppError(
            "too_large", "That image is too large. Please retake the photo.", 413
        )
    _require_key()
    started = time.monotonic()
    last_error: AppError = ai_unclear()
    for attempt in range(2):
        # No response_format: Meta rejects schemaless json_schema with 400.
        # The prompt already demands JSON-only; bad JSON retries below.
        response = await _post_chat(
            {
                "model": settings.default_model,
                "messages": _bill_messages(
                    body.image_base64, body.ocr_hint, retry=attempt > 0
                ),
            }
        )
        if response.status_code == 429:
            raise ai_busy()
        if response.status_code != 200:
            logger.warning("meta bill status=%s", response.status_code)
            raise map_meta_http(response.status_code)
        try:
            content, _, _ = _first_content(response.json())
            parsed = json.loads(content)
        except (ValueError, json.JSONDecodeError):
            last_error = ai_unclear()
            continue
        if not isinstance(parsed, dict):
            last_error = ai_unclear()
            continue
        logger.info(
            "bill sub=%s %.0fms attempt=%d",
            sub[:8],
            (time.monotonic() - started) * 1000,
            attempt + 1,
        )
        return parsed
    raise last_error
