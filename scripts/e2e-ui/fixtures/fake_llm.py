"""E2E 用的假 LLM 端点（OpenAI 兼容）。

用途：让"系统设置里配置端点 → 试跑页真正调用 → 输出渲染"这条链路可以在本地跑通，
不需要真实模型与密钥。所有回复都在正文里带 fake-llm-ok 前缀，便于页面断言。

运行（与 E2E 应用同网络）：
  docker run -d --name fake-llm --network emonlens-uie2e \
    -v "$PWD/fixtures/fake_llm.py:/fake.py:ro" python:3.11-alpine python /fake.py
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            body = json.loads(raw) if raw.strip() else {}
        except Exception:
            body = {}

        if self.path.rstrip("/").endswith("/chat/completions"):
            content = "fake-llm-ok: 这是假端点的回复（用于页面 E2E）"
            payload = {
                "id": "chatcmpl-fake",
                "model": body.get("model", "fake-model"),
                "choices": [{"message": {"role": "assistant", "content": content}}],
                "usage": {"prompt_tokens": 11, "completion_tokens": 7},
            }
        else:
            payload = {"ok": True}

        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):  # 静音
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
