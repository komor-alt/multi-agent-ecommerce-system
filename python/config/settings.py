from pydantic_settings import BaseSettings
from functools import lru_cache


class Settings(BaseSettings):
    app_name: str = "Multi-Agent E-Commerce System"
    debug: bool = False

    # LLM
    llm_api_key: str = ""
    llm_base_url: str = "https://api.minimax.chat/v1"
    llm_model: str = "MiniMax-M1"
    llm_temperature: float = 0.7
    llm_max_tokens: int = 2048
    # Live calls are opt-in and require a positive per-request budget.
    llm_mode: str = "OFFLINE"
    llm_live_enabled: bool = False
    llm_max_calls: int = 0

    # Redis
    redis_url: str = "redis://localhost:6379/0"
    feature_ttl_seconds: int = 86400

    # Milvus
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    milvus_collection: str = "product_embeddings"

    # Database
    database_url: str = "sqlite:///./ecommerce.db"

    # A/B Testing
    ab_test_enabled: bool = True
    ab_test_default_bucket_count: int = 100

    # Agent timeouts (seconds)
    agent_timeout_user_profile: float = 5.0
    agent_timeout_product_rec: float = 8.0
    agent_timeout_marketing_copy: float = 10.0
    agent_timeout_inventory: float = 5.0

    def llm_enabled(self) -> bool:
        return (
            self.llm_mode.strip().upper() == "LLM"
            and self.llm_live_enabled
            and bool(self.llm_api_key.strip())
            and self.llm_max_calls > 0
        )

    # Do not auto-load .env: tests must not ingest live credentials implicitly.
    # Container/deployment environments inject ECOM_* variables explicitly.
    model_config = {"env_prefix": "ECOM_"}


@lru_cache()
def get_settings() -> Settings:
    return Settings()
