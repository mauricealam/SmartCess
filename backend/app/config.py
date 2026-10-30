"""Process configuration. Secrets come from the environment only."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "smartcess-backend"
    app_version: str = "0.1.0"

    meta_api_key: str = ""
    meta_base_url: str = "https://api.meta.ai/v1"
    meta_stt_url: str = "wss://api.meta.ai/v1/asr/realtime"
    stt_model: str = "muse-voice-transcribe-1.0"
    allowed_models: str = "muse-spark-1.3,muse-spark-1.2"
    default_model: str = "muse-spark-1.3"

    jwt_secret: str = "dev-only-change-me"
    jwt_access_minutes: int = 15
    jwt_refresh_days: int = 7

    google_server_client_id: str = ""

    chat_timeout_s: float = 25.0
    chat_per_minute: int = 20
    bill_per_minute: int = 10
    max_image_b64_chars: int = 3_000_000
    ws_idle_s: float = 60.0
    ws_max_s: float = 300.0

    @property
    def model_allowlist(self) -> set[str]:
        return {m.strip() for m in self.allowed_models.split(",") if m.strip()}


@lru_cache
def get_settings() -> Settings:
    return Settings()
