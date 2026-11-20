#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Listen Reader 本地 E2E mock 服务。

一个进程替身三件事，让真机/模拟器上的真实应用在不 root、不改源码、不注入自签 CA
的前提下跑完整链路：

* ``POST /v1/audio/speech``：StepFun TTS 替身，返回真实 MP3 字节；并发与耗时可控，
  便于观察预取窗口是否真的并行。
* ``POST /v1/chat/completions``：OpenAI 兼容 LLM 替身，返回确定性的朗读分段文本。
* ``GET /fixtures/<name>`` 与 ``GET /mp.weixin.qq.com/s/<name>``：把
  ``app/src/androidTest/assets/fixtures/*.html`` 当网页提供，覆盖通用网页与公众号模板。

用法::

    python3 scripts/mock-server.py --port 18080 --tts-delay-ms 300
    adb reverse tcp:18080 tcp:18080
    adb shell am broadcast -a com.cnife.listenreader.DEBUG_CONTROL \\
        --es cmd configure --es stepfun_endpoint http://127.0.0.1:18080

``GET /healthz`` 返回当前并发与历史峰值，供 Agent 用一条 curl 取断言证据。
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FIXTURE = REPO_ROOT / "app/src/androidTest/assets/audio-fixture.mp3"
FIXTURE_DIR = REPO_ROOT / "app/src/androidTest/assets/fixtures"


class Metrics:
    """线程安全的并发与请求计数。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.active_tts = 0
        self.max_active_tts = 0
        self.tts_requests = 0
        self.llm_requests = 0

    def tts_start(self) -> tuple[int, int]:
        with self._lock:
            self.tts_requests += 1
            self.active_tts += 1
            self.max_active_tts = max(self.max_active_tts, self.active_tts)
            return self.active_tts, self.max_active_tts

    def tts_end(self) -> int:
        with self._lock:
            self.active_tts -= 1
            return self.active_tts

    def llm_hit(self) -> int:
        with self._lock:
            self.llm_requests += 1
            return self.llm_requests

    def snapshot(self) -> dict[str, object]:
        with self._lock:
            return {
                "ok": True,
                "active_tts": self.active_tts,
                "max_active_tts": self.max_active_tts,
                "tts_requests": self.tts_requests,
                "llm_requests": self.llm_requests,
            }


METRICS = Metrics()


def log_line(line: str) -> None:
    print(f"{time.time():.3f} {line}", flush=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "ListenReaderMock/1.0"
    protocol_version = "HTTP/1.1"

    # --- 配置 ---
    tts_delay_ms = 0
    llm_delay_ms = 0
    tts_fixture: Path = DEFAULT_FIXTURE

    def log_message(self, fmt: str, *args: object) -> None:
        # 默认把每条请求打到 stderr；这里压掉，只保留我们自己打的 START/END 行。
        return

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path == "/healthz":
            self._send_json(200, METRICS.snapshot())
            return
        fixture = self._fixture_for(path)
        if fixture is None:
            self._send_json(404, {"ok": False, "error": f"no fixture for {path}"})
            return
        self._send_bytes(200, "text/html; charset=utf-8", fixture.read_bytes())

    def do_POST(self) -> None:
        path = self.path.split("?", 1)[0]
        body = self._read_body()
        if path.endswith("/v1/audio/speech"):
            self._handle_tts(body)
        elif path.endswith("/v1/chat/completions"):
            self._handle_llm(body)
        else:
            self._send_json(404, {"ok": False, "error": f"no route for {path}"})

    # --- 路由实现 ---

    def _handle_tts(self, body: bytes) -> None:
        payload = self._json_body(body)
        text = str(payload.get("input", ""))
        voice = str(payload.get("voice", ""))
        active, peak = METRICS.tts_start()
        log_line(f"START active={active} max={peak} voice={voice} text='{text[:50]}'")
        time.sleep(self.tts_delay_ms / 1000.0)
        audio = self.tts_fixture.read_bytes()
        active = METRICS.tts_end()
        log_line(f"END active={active} text='{text[:50]}'")
        self._send_bytes(200, "audio/mpeg", audio)

    def _handle_llm(self, body: bytes) -> None:
        payload = self._json_body(body)
        METRICS.llm_hit()
        text = self._llm_input_text(payload)
        log_line(f"LLM text='{text[:50]}'")
        time.sleep(self.llm_delay_ms / 1000.0)
        response = {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": str(payload.get("model", "mock-model")),
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": mock_script(text)},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }
        self._send_json(200, response)

    # --- 工具 ---

    def _fixture_for(self, path: str) -> Path | None:
        name = path.rsplit("/", 1)[-1]
        if not name:
            return None
        if not name.endswith(".html"):
            name += ".html"
        candidate = FIXTURE_DIR / name
        return candidate if candidate.is_file() else None

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def _json_body(self, body: bytes) -> dict[str, object]:
        try:
            parsed = json.loads(body or b"{}")
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}

    @staticmethod
    def _llm_input_text(payload: dict[str, object]) -> str:
        messages = payload.get("messages")
        if isinstance(messages, list):
            for message in reversed(messages):
                if isinstance(message, dict) and message.get("role") == "user":
                    return str(message.get("content", ""))
        return ""

    def _send_json(self, status: int, value: dict[str, object]) -> None:
        self._send_bytes(
            status,
            "application/json; charset=utf-8",
            json.dumps(value, ensure_ascii=False).encode("utf-8"),
        )

    def _send_bytes(self, status: int, content_type: str, payload: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def mock_script(text: str) -> str:
    """把输入正文切成确定性朗读分段，模拟 L2 返回的 JSON。"""
    sentences = [
        part.strip() for part in text.replace("\n", "。").split("。") if part.strip()
    ]
    if not sentences:
        sentences = ["模拟朗读片段。"]
    segments = [
        {"text": sentence + "。", "kind": "paragraph"} for sentence in sentences
    ]
    return json.dumps(
        {"title": "模拟清洗标题", "segments": segments}, ensure_ascii=False
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Listen Reader 本地 E2E mock 服务")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--tts-delay-ms", type=int, default=0)
    parser.add_argument("--llm-delay-ms", type=int, default=0)
    parser.add_argument("--tts-fixture", type=Path, default=DEFAULT_FIXTURE)
    args = parser.parse_args()

    if not args.tts_fixture.is_file():
        raise SystemExit(f"找不到 TTS 音频 fixture：{args.tts_fixture}")

    Handler.tts_delay_ms = args.tts_delay_ms
    Handler.llm_delay_ms = args.llm_delay_ms
    Handler.tts_fixture = args.tts_fixture

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    log_line(
        f"listening on http://{args.host}:{args.port} (tts-delay={args.tts_delay_ms}ms)"
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log_line("stopping")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
