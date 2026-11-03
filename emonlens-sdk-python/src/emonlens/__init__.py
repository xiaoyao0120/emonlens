"""EmonLens Agent Observability SDK（零依赖）。

快速开始：
    import os
    os.environ["EMONLENS_HOST"] = "http://emonlens:8300"
    os.environ["EMONLENS_INGEST_KEY"] = "tk-xxxx"
    os.environ["EMONLENS_AGENT"] = "my-agent"

    from emonlens import Trace
    with Trace(session_id="chat-1", input="问题") as tr:
        tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
               input_tokens=100, output_tokens=50)
    # 退出时自动批量上报（失败不影响业务）
"""
from .client import Client, flush, get_client, get_prompt, init, Prompt  # noqa: F401
from .config import Config  # noqa: F401
from .evaluate import (EmonLensAssertionError, EmonLensError,  # noqa: F401
                       EmonLensTimeoutError, assert_test, baseline_run, evaluate,
                       gate_check, get_run, run_diff, wait_for_run)
from .sdk_eval import (SdkSample, ScorerResult,  # noqa: F401
                       fetch_sdk_tasks, push_sdk_results, run_sdk_scorers, scorer)
from . import integrations  # noqa: F401
from .observe import (current_trace_id, observe, propagate,  # noqa: F401
                      set_session, trace_context, update_current)
from .trace import Trace  # noqa: F401

__version__ = "0.2.0"
