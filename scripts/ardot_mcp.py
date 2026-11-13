#!/usr/bin/env python3
"""Minimal Ardot MCP (streamable HTTP) client for the icon-补全 workflow.

Reads the bearer token from ~/.zcode/cli/config.json so no credential is stored here.
Usage:
    python scripts/ardot_mcp.py tools
    python scripts/ardot_mcp.py call <tool_name> '<json-arguments>'
"""
import json
import pathlib
import sys
import urllib.request

URL = "https://ardot.tencent.com/mcp"
FILE_URL = "https://ardot.tencent.com/file/737849992357132?node_id=0%3A1"


def token() -> str:
    cfg = json.loads((pathlib.Path.home() / ".zcode/cli/config.json").read_text(encoding="utf-8"))
    return cfg["mcp"]["servers"]["ardot-remote"]["headers"]["Authorization"]


class Client:
    def __init__(self) -> None:
        self.auth = token()
        self.session = None
        self.next_id = 0

    def rpc(self, method: str, params=None):
        self.next_id += 1
        payload = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            payload["params"] = params
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": self.auth,
        }
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        req = urllib.request.Request(URL, data=json.dumps(payload).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=120) as resp:
            if not self.session:
                self.session = resp.headers.get("Mcp-Session-Id")
            raw = resp.read().decode()
        return self._parse(raw)

    @staticmethod
    def _parse(raw: str):
        if raw.lstrip().startswith("{"):
            return json.loads(raw)
        out = None
        for line in raw.splitlines():
            if line.startswith("data:"):
                out = json.loads(line[5:].strip())
        return out

    def open(self):
        r = self.rpc("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "zcode-icon-sync", "version": "1.0"},
        })
        # notifications/initialized is optional for stateless servers; send and ignore failures.
        try:
            self.rpc("notifications/initialized", {})
        except Exception:
            pass
        return r

    def tools(self):
        return self.rpc("tools/list", {})

    def call(self, name: str, args: dict):
        # Almost every Ardot tool validates `fileUrl`; default it to the journal file.
        args.setdefault("fileUrl", FILE_URL)
        return self.rpc("tools/call", {"name": name, "arguments": args})


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    c = Client()
    c.open()
    if cmd == "tools":
        data = c.tools()
        for t in data.get("result", {}).get("tools", []):
            print(f"{t['name']}\n    {(t.get('description') or '')[:300]}")
        return 0
    if cmd == "call":
        name = sys.argv[2]
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        res = c.call(name, args)
        text = json.dumps(res, ensure_ascii=False, indent=2)
        # The Windows console is GBK here, which mangles the Chinese node names. Write to a
        # file instead so callers can read UTF-8 back with the normal file tools.
        out = pathlib.Path(".local/ardot/last.json")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        sys.stdout.write(f"wrote {out} ({len(text)} chars)\n")
        return 0
    print(f"unknown command: {cmd}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
