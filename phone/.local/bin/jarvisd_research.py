#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_research — a research loop that produces a cited Persian report.

Google's `deep-research-*` models answer 429 on this phone's free tier, so research
here is **a loop, not a model**: plan → search → read → synthesise, with the evidence
kept and every claim forced to carry the URL it came from.

    POST /research               {question, depth?:1|2|3, persona?}  -> {ok, id}
    GET  /research/{id}          -> {id, question, state, steps[], report, sources[], ms}
    GET  /research/{id}/events   -> SSE: state · step.start · step.end · answer.delta · done
    POST /research/{id}/cancel
    GET  /research               -> recent runs

Budget (measured, see the module docstring of each stage for the numbers):
    depth 1 -> 3 sub-questions, 3 searches, 2 model calls
    depth 2 -> 5 sub-questions, 5 searches, 2 model calls          (default)
    depth 3 -> 6 sub-questions, up to 10 searches, 4 model calls   (adds a gap pass)

Lanes, all measured on the phone on 2026-10-26:
    search : Hermes `tools.web_tools.web_search_tool` (keyless Exa, 3.1 s, 3 hits) and
             DuckDuckGo HTML (1.9 s, 10 hits) as a fallback.  Gemini Google-Search
             grounding answers 429 on the free tier and is deliberately not used.
    read   : Hermes `web_extract_tool` (6.5 s/page, clean markdown). A plain httpx GET
             is only the fallback — Wikipedia answers it 403.
    model  : the phone's own providers, in config order (river → configured fallbacks →
             avalai → the local antigravity bridge). A provider without a key is skipped
             and the attempt chain is reported, never hidden.

Honesty rules baked in: only fetched pages may be cited; a URL the model invented is
cut out of the body before the report is stored, and every dropped URL is listed in
``verification.invented``. No source, no claim.

Import-safe: no network, no subprocess and no file access at import time.
"""

from __future__ import annotations

import json
import os
import re
import threading
import time
import uuid
from collections import deque
from typing import Any, Callable, Dict, List, Optional, Tuple

# FastAPI resolves route annotations in the module namespace (postponed annotations),
# so these two must be module-level names or request bodies arrive as query params.
from fastapi import Request
from fastapi.responses import JSONResponse

APP = "/data/data/com.termux/files/usr/lib/hermes-agent/app"

DEPTHS = {
    1: {"subquestions": 3, "max_searches": 3, "max_pages": 3, "max_calls": 2},
    2: {"subquestions": 5, "max_searches": 6, "max_pages": 6, "max_calls": 3},
    3: {"subquestions": 6, "max_searches": 10, "max_pages": 8, "max_calls": 4},
}
PAGE_CHARS = 3500          # per page fed to the synthesis model
SNIPPET_CHARS = 400        # per search result handed to the planner's choice
TOTAL_CHARS = 16000        # whole evidence block cap (token budget)
RUN_DEADLINE = {1: 180, 2: 300, 3: 420}
EVENT_KEEP = 400

# Provider endpoints that Hermes resolves from its own tables (base_url is not in
# config.yaml for builtin providers). Only OpenAI-compatible chat surfaces are used.
BASE_URLS = {
    "river": "https://api.river.ai/v1",
    "avalai": "https://api.avalai.ir/v1",
    "openrouter": "https://openrouter.ai/api/v1",
    "google_ai_studio": "https://generativelanguage.googleapis.com/v1beta/openai",
    "cerebras": "https://api.cerebras.ai/v1",
    "groq": "https://api.groq.com/openai/v1",
    "mistral": "https://api.mistral.ai/v1",
    "antigravity": "http://127.0.0.1:8790/v1",
}
KEY_ENV = {
    "river": ("RIVER_API_KEY",), "avalai": ("AVALAI_API_KEY",),
    "openrouter": ("OPENROUTER_API_KEY",),
    "google_ai_studio": ("GEMINI_API_KEY", "GOOGLE_AI_STUDIO_API_KEY"),
    "groq": ("GROQ_API_KEY",), "cerebras": ("CEREBRAS_API_KEY",),
    "mistral": ("MISTRAL_API_KEY",), "custom": ("CUSTOM_API_KEY",),
    "antigravity": (),   # local bridge: needs a placeholder, not a secret
}
URL_RE = re.compile(r"https?://[^\s<>\"'\)\]،؛»]+")
SECRET_RE = re.compile(
    r"(sk-[A-Za-z0-9]{8,}|AIza[A-Za-z0-9_-]{10,}|ghp_[A-Za-z0-9]{10,}"
    r"|Bearer\s+[A-Za-z0-9._-]{8,}|eyJ[A-Za-z0-9._-]{20,})")

_LOCK = threading.Lock()
_RUNS: Dict[str, "Run"] = {}


class Cancelled(Exception):
    """Raised inside the pipeline once the console has cancelled the run."""


# --------------------------------------------------------------------------- helpers --
def _redact(text: Any) -> str:
    return SECRET_RE.sub("***", "" if text is None else str(text))


def _trim(text: str, limit: int) -> str:
    text = re.sub(r"\s+\n", "\n", (text or "")).strip()
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _iso(ts: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(ts))


def _dir(ctx) -> str:
    return os.path.join(ctx["JARVIS_DIR"], "research")


def _load_env(ctx) -> Dict[str, str]:
    """Key *names* are used to decide which provider is usable; values never leave."""
    out: Dict[str, str] = {}
    path = os.path.join(ctx["HERMES"], ".env")
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, val = line.split("=", 1)
                out[key.strip()] = val.strip().strip('"').strip("'")
    except OSError:
        pass
    return out


def _load_yaml(ctx) -> Dict[str, Any]:
    try:
        import yaml
        with open(os.path.join(ctx["HERMES"], "config.yaml"), encoding="utf-8") as fh:
            data = yaml.safe_load(fh) or {}
        return data if isinstance(data, dict) else {}
    except Exception:  # noqa: BLE001
        return {}


def _norm_url(url: str) -> str:
    url = (url or "").strip().rstrip(".,;:)]»").split("#")[0]
    if url.endswith("/") and url.count("/") > 3:
        url = url[:-1]
    return url.lower()


# ------------------------------------------------------------------------ model lane --
def _candidates(ctx) -> List[Dict[str, str]]:
    """Provider chain in the phone's own order: default, configured fallbacks, rest."""
    cfg = _load_yaml(ctx)
    env = _load_env(ctx)
    order: List[Tuple[str, str]] = []
    model_cfg = cfg.get("model") or {}
    default_provider = str(model_cfg.get("provider") or "")
    default_model = str(model_cfg.get("default") or "")
    if default_provider and default_model:
        order.append((default_provider, default_model))
    for item in cfg.get("fallback_providers") or []:
        if isinstance(item, dict) and item.get("provider") and item.get("model"):
            order.append((str(item["provider"]), str(item["model"])))
    providers = cfg.get("providers") or {}
    for pid in ("avalai", "google_ai_studio", "openrouter", "antigravity", "river"):
        conf = providers.get(pid) or {}
        models = conf.get("models")
        name = ""
        if isinstance(models, dict) and models:
            name = str(next(iter(models)))
        elif isinstance(models, list) and models:
            name = str(models[0])
        if name:
            order.append((pid, name))
    rows: List[Dict[str, str]] = []
    seen = set()
    for pid, model in order:
        if (pid, model) in seen or pid not in BASE_URLS:
            continue
        seen.add((pid, model))
        keys = [env[k] for k in KEY_ENV.get(pid, ()) if env.get(k)]
        if KEY_ENV.get(pid) and not keys:
            continue
        rows.append({"provider": pid, "model": model, "base_url": BASE_URLS[pid],
                     "key": keys[0] if keys else "antigravity-local"})
    return rows


def _chat(ctx, run: "Run", tag: str, messages: List[Dict[str, str]],
          max_tokens: int = 900, stream_to: Optional[Callable[[str], None]] = None) -> str:
    """One chat completion against the first provider that answers. Raises on total fail."""
    import httpx

    budget = DEPTHS[run.depth]["max_calls"]
    if run.model_calls >= budget:
        raise RuntimeError("model-call budget reached (%d)" % budget)
    cands = _candidates(ctx)
    if not cands:
        raise RuntimeError("no provider with a key is configured")
    errors: List[str] = []
    for cand in cands:
        if run.cancelled.is_set():
            raise RuntimeError("cancelled")
        started = ctx["now"]()
        headers = {"Authorization": "Bearer " + cand["key"],
                   "Content-Type": "application/json"}
        body = {"model": cand["model"], "messages": messages, "temperature": 0.2,
                "max_tokens": max_tokens}
        try:
            if stream_to is not None:
                text = _stream_chat(httpx, cand["base_url"], headers, body, run, stream_to)
            else:
                with httpx.Client(timeout=120) as client:
                    resp = client.post(cand["base_url"].rstrip("/") + "/chat/completions",
                                       headers=headers, json=body)
                if resp.status_code != 200:
                    raise RuntimeError("HTTP %s %s" % (resp.status_code, _trim(resp.text, 160)))
                data = resp.json()
                text = ((data.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
            run.model_calls += 1
            run.attempts.append({"stage": tag, "provider": cand["provider"],
                                 "model": cand["model"], "ok": True,
                                 "ms": int((ctx["now"]() - started) * 1000)})
            if not text.strip():
                raise RuntimeError("empty answer")
            return text
        except Exception as exc:  # noqa: BLE001  (try the next provider)
            errors.append("%s: %s: %s" % (cand["provider"], type(exc).__name__,
                                          _trim(str(exc), 120)))
            run.attempts.append({"stage": tag, "provider": cand["provider"],
                                 "model": cand["model"], "ok": False,
                                 "ms": int((ctx["now"]() - started) * 1000),
                                 "error": _trim(str(exc), 160)})
    raise RuntimeError("every provider failed: " + " | ".join(errors[:4]))


def _stream_chat(httpx, base_url: str, headers: Dict[str, str], body: Dict[str, Any],
                 run: "Run", stream_to: Callable[[str], None]) -> str:
    """Streaming call so the console can paint the report as it is written."""
    parts: List[str] = []
    payload = dict(body, stream=True)
    with httpx.Client(timeout=180) as client:
        with client.stream("POST", base_url.rstrip("/") + "/chat/completions",
                           headers=headers, json=payload) as resp:
            if resp.status_code != 200:
                raise RuntimeError("HTTP %s" % resp.status_code)
            for line in resp.iter_lines():
                if run.cancelled.is_set():
                    break
                if not line:
                    continue
                if line.startswith("data:"):
                    line = line[5:].strip()
                if line == "[DONE]":
                    break
                try:
                    chunk = json.loads(line)
                except ValueError:
                    continue
                delta = (((chunk.get("choices") or [{}])[0]).get("delta") or {})
                piece = delta.get("content") or ""
                if piece:
                    parts.append(piece)
                    stream_to(piece)
    return "".join(parts)


# ----------------------------------------------------------------------- search lane --
def _web_tools():
    """Hermes' own web tools (they already know the configured backend)."""
    import sys
    if APP not in sys.path:
        sys.path.insert(0, APP)
    from tools import web_tools  # noqa: PLC0415
    return web_tools


def _search_hermes(query: str, limit: int) -> List[Dict[str, str]]:
    web_tools = _web_tools()
    raw = web_tools.web_search_tool(query, limit)
    data = json.loads(raw)
    if not data.get("success"):
        raise RuntimeError(_trim(str(data.get("error") or "search failed"), 160))
    out = []
    for row in ((data.get("data") or {}).get("web") or []):
        url = str(row.get("url") or "").strip()
        if url.startswith("http"):
            out.append({"url": url, "title": str(row.get("title") or "")[:160],
                        "snippet": _trim(str(row.get("description") or ""), SNIPPET_CHARS),
                        "lane": "hermes-web"})
    return out


def _search_ddg(query: str, limit: int) -> List[Dict[str, str]]:
    import html as html_lib
    import urllib.parse
    import httpx

    with httpx.Client(timeout=25, follow_redirects=True) as client:
        resp = client.post("https://html.duckduckgo.com/html/", data={"q": query},
                           headers={"User-Agent": "Mozilla/5.0 (Linux; Android 16) "
                                                  "AppleWebKit/537.36 (KHTML, like Gecko) "
                                                  "Chrome/128.0 Mobile Safari/537.36"})
    if resp.status_code != 200:
        raise RuntimeError("duckduckgo HTTP %s" % resp.status_code)
    out: List[Dict[str, str]] = []
    for block in re.finditer(
            r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>'
            r'(?:.*?class="result__snippet"[^>]*>(.*?)</a>)?', resp.text, re.S):
        href = block.group(1)
        if "uddg=" in href:
            href = urllib.parse.unquote(href.split("uddg=")[1].split("&")[0])
        if not href.startswith("http"):
            continue
        title = html_lib.unescape(re.sub(r"<[^>]+>", "", block.group(2) or "")).strip()
        snippet = html_lib.unescape(re.sub(r"<[^>]+>", "", block.group(3) or "")).strip()
        out.append({"url": href, "title": title[:160],
                    "snippet": _trim(snippet, SNIPPET_CHARS), "lane": "duckduckgo"})
        if len(out) >= limit:
            break
    return out


def _search(lane_used: List[str], query: str, limit: int) -> Tuple[List[Dict[str, str]], str]:
    """Hermes' backend first; DuckDuckGo when it is down. Returns (rows, error)."""
    errors = []
    for name, fn in (("hermes-web", _search_hermes), ("duckduckgo", _search_ddg)):
        try:
            rows = fn(query, limit)
            if rows:
                if name not in lane_used:
                    lane_used.append(name)
                return rows, ""
            errors.append("%s: no results" % name)
        except Exception as exc:  # noqa: BLE001
            errors.append("%s: %s" % (name, _trim(str(exc), 120)))
    return [], " | ".join(errors)


def _quality(page: Dict[str, Any]) -> float:
    """0..1 — is this page prose, or is it a navigation menu?

    Measured headline: `developer.android.com/about/versions/` extracts to ~3.5 KB of
    menu labels with no version number in it. Those pages are useless as evidence, so a
    page has to look like prose before it may be cited.
    """
    text = page.get("content") or ""
    words = text.split()
    n = len(words)
    if n < 40:
        return 0.0
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    long_ratio = sum(1 for ln in lines if len(ln) > 60) / max(1, len(lines))
    sentences = len(re.findall(r"[.!?؟]", text))
    links = int(page.get("links") or 0)
    score = 0.0
    score += min(1.0, n / 300.0) * 0.45
    score += long_ratio * 0.30
    score += min(1.0, sentences / 8.0) * 0.25
    if n > 120 and links and links / n > 0.06:   # link farm / nav page
        score *= 0.45
    return round(score, 2)


CACHE_KEEP = 60          # pages kept in the shared page cache
PAGE_TTL_FRESH = 1800    # a new run may reuse a page this old (prices move)
PAGE_TTL_FOLLOWUP = 86400  # a follow-up is a conversation: reuse the run's own pages
_CACHE_LOCK = threading.Lock()


def _cache_path(ctx) -> str:
    return os.path.join(_dir(ctx), "_pages.json")


def _cache_load(ctx) -> Dict[str, Dict[str, Any]]:
    try:
        with open(_cache_path(ctx), encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _cache_get(ctx, url: str, max_age: float) -> Optional[Dict[str, Any]]:
    with _CACHE_LOCK:
        row = _cache_load(ctx).get(_norm_url(url))
    if not isinstance(row, dict) or not row.get("content"):
        return None
    if max_age > 0 and (_now() - float(row.get("ts") or 0)) > max_age:
        return None
    return {"url": row.get("url") or url, "title": row.get("title") or "",
            "content": row["content"], "lane": "cache", "links": int(row.get("links") or 0)}


def _cache_put(ctx, page: Dict[str, Any]) -> None:
    if not page.get("content"):
        return
    with _CACHE_LOCK:
        data = _cache_load(ctx)
        data[_norm_url(page["url"])] = {"url": page["url"], "title": page.get("title") or "",
                                        "content": page["content"],
                                        "links": page.get("links") or 0, "ts": _now()}
        if len(data) > CACHE_KEEP:
            keep = sorted(data.items(), key=lambda kv: float((kv[1] or {}).get("ts") or 0),
                          reverse=True)[:CACHE_KEEP]
            data = dict(keep)
        try:
            os.makedirs(_dir(ctx), exist_ok=True)
            tmp = _cache_path(ctx) + ".tmp"
            with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(data, fh, ensure_ascii=False)
            os.replace(tmp, _cache_path(ctx))
        except OSError:
            pass


def _now() -> float:
    return time.time()


def _extract(ctx, urls: List[str], run: Optional["Run"] = None,
             max_age: float = PAGE_TTL_FRESH) -> Dict[str, Dict[str, Any]]:
    """Fetch pages: page cache, Hermes' extractor in one batch, per URL, then a GET.

    A single bad URL used to take the whole batch down, which is how four pages ended up
    fetched by the weakest lane. Each stage now only handles what earlier stages missed.
    """
    import asyncio

    out: Dict[str, Dict[str, Any]] = {}
    pending = list(dict.fromkeys(urls))

    for url in list(pending):
        hit = _cache_get(ctx, url, max_age)
        if hit:
            out[_norm_url(url)] = hit
    pending = [u for u in pending if _norm_url(u) not in out]

    def take(rows) -> None:
        for row in rows or []:
            url = str(row.get("url") or "")
            content = row.get("content") or ""
            if not url or not content or row.get("error"):
                continue
            page = {"url": url, "title": str(row.get("title") or ""), "content": content,
                    "lane": row.get("lane") or "hermes-extract",
                    "links": int(content.count("](")) or 0}
            key = _norm_url(url)
            if key not in out:
                out[key] = page
            _cache_put(ctx, page)

    if pending:
        if run is not None:
            run.ensure_live()
        try:
            batch = pending[:8]
            raw = asyncio.run(_web_tools().web_extract_tool(batch,
                                                           char_limit=PAGE_CHARS + 500))
            take((json.loads(raw) or {}).get("results"))
            pending = [u for u in pending if _norm_url(u) not in out]
        except Exception as exc:  # noqa: BLE001  (batch failed: go one by one)
            if run is not None:
                run.emit("state", {"state": run.state,
                                   "detail": "batch extract failed: " + _trim(str(exc), 120)})
    if pending:
        if run is not None:
            run.ensure_live()
        # A batch can lose one URL to a provider 403; retry the missing ones *in
        # parallel* (measured: 4 pages 3.7 s wall instead of ~14 s serially).
        import concurrent.futures as cf

        def fetch_one(url: str):
            try:
                raw = asyncio.run(_web_tools().web_extract_tool([url],
                                                               char_limit=PAGE_CHARS + 500))
                return (json.loads(raw) or {}).get("results")
            except Exception:  # noqa: BLE001
                return None

        retry = list(pending)[:4]
        with cf.ThreadPoolExecutor(max_workers=min(4, len(retry))) as pool:
            for rows in pool.map(fetch_one, retry):
                take(rows)
        pending = [u for u in pending if _norm_url(u) not in out]
    for url in pending:
        if run is not None and run.cancelled.is_set():
            break
        page = _plain_fetch(url)
        if page:
            out[_norm_url(url)] = page
            _cache_put(ctx, page)
    return out


def _plain_fetch(url: str) -> Optional[Dict[str, Any]]:
    import httpx
    try:
        with httpx.Client(timeout=25, follow_redirects=True) as client:
            resp = client.get(url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 16) "
                                                          "AppleWebKit/537.36 (KHTML, like Gecko) "
                                                          "Chrome/128.0 Mobile Safari/537.36"})
        if resp.status_code != 200:
            return None
        links = len(re.findall(r"(?i)<a\s", resp.text))
        body = re.sub(r"(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\1>",
                      " ", resp.text)
        text = re.sub(r"(?s)<[^>]+>", " ", body)
        text = re.sub(r"&nbsp;?", " ", text)
        text = re.sub(r"[ \t\r\f\v]+", " ", text)
        text = re.sub(r"\n\s*\n+", "\n", text).strip()
        if len(text) < 200:
            return None
        return {"url": url, "title": "", "content": text, "lane": "plain-fetch",
                "links": links}
    except Exception:  # noqa: BLE001
        return None


# --------------------------------------------------------------------------- prompts --
PLAN_PROMPT = (
    "You break a research question into web searches. Search engines are English-first, "
    "so write the queries in English unless the question is about Iran, in which case "
    "keep the Persian place/name terms.\n"
    "Return ONLY JSON, no prose:\n"
    '{"subquestions":[{"q":"search query","why":"what fact it settles"}]}\n'
    "Rules: {n} items; each query must be self-contained (repeat the subject); prefer "
    "queries that hit primary sources (official docs, exchanges, statistics sites); no "
    "two queries may overlap. Today's date is {date}."
)

SYNTH_PROMPT = (
    "You write a research report in **Persian** for a phone console. Today is {date}.\n"
    "You get numbered pages below, each with a URL. Use ONLY those pages.\n"
    "HARD RULES:\n"
    "1. Every sentence that states a fact ends with the source URL in parentheses, "
    "copying it exactly from the page header, like (https://example.com/x).\n"
    "2. Never write a URL that is not in the page list. Never invent a number, a date, "
    "a price or a quote.\n"
    "3. If the pages do not settle a part of the question, write "
    "«تأیید نشد: داده‌ای پیدا نشد» for that part instead of guessing.\n"
    "4. Start with «نتیجه» in 3–5 bullet lines, then «جزئیات» in short paragraphs.\n"
    "5. Say how fresh the data is when the page gives a date, and flag any mismatch "
    "between the pages.\n"
    "{persona}\n"
    "Pages:\n{pages}"
)

GAP_PROMPT = (
    "These pages were collected for the research question: {question}\n"
    "Pages:\n{toc}\n"
    "Name at most 2 facts a careful reader would still call unsupported or stale, and "
    "give one English search query that would settle each. JSON only: "
    "{{\"gaps\":[\"query\"]}}"
)

FOLLOWUP_PLAN = (
    "A research run already answered this question: {parent_q}\n"
    "It already read these sources:\n{toc}\n"
    "The user's follow-up question is: {question}\n"
    "Decide whether the follow-up can be answered from those sources alone. If it "
    "genuinely needs fresh facts (a new number, a different subject, something after "
    "those pages), give at most 2 English search queries; otherwise give none.\n"
    "JSON only: {{\"need_search\":true|false,\"queries\":[\"…\"],\"why\":\"short\"}}"
)

FOLLOWUP_CONTEXT = (
    "این یک پرسش پیگیرانه از یک پژوهش قبلی است. خلاصهٔ گزارش قبلی:\n{prev}\n"
    "فقط به پرسش تازه پاسخ بده و همان قواعد ارجاع را رعایت کن؛ ارجاع به منابعی که "
    "در ادامه آمده‌اند مجاز است."
)


# ------------------------------------------------------------------------------- run --
class Run:
    """One research run: its step log, its event stream, its persisted result."""

    def __init__(self, ctx, question: str, depth: int, persona: str,
                 parent: str = "", parent_urls: Optional[List[str]] = None,
                 parent_report: str = ""):
        self.ctx = ctx
        self.id = uuid.uuid4().hex[:12]
        self.question = question
        self.depth = depth
        self.persona = persona
        self.parent = parent
        self.parent_urls = list(parent_urls or [])
        self.parent_report = parent_report
        self.created = float(ctx["now"]())
        self.state = "queued"
        self.steps: List[Dict[str, Any]] = []
        self.sources: List[Dict[str, Any]] = []
        self.report = ""
        self.error = ""
        self.cancelled = threading.Event()
        self.events: deque = deque(maxlen=EVENT_KEEP)
        self.seq = 0
        self.subs: List[Any] = []
        self.lock = threading.Lock()
        self.model_calls = 0
        self.searches = 0
        self.attempts: List[Dict[str, Any]] = []
        self.lanes: List[str] = []
        self.verification: Dict[str, Any] = {}
        self.timing: Dict[str, Any] = {}
        self.subquestions: List[Dict[str, str]] = []
        self.decision: Dict[str, Any] = {}
        self.rejected: List[Dict[str, Any]] = []
        self.ended = 0.0

    # events ---------------------------------------------------------------------
    def emit(self, kind: str, data: Optional[Dict[str, Any]] = None) -> int:
        with self.lock:
            self.seq += 1
            ev = {"seq": self.seq, "kind": kind, "ts": round(float(self.ctx["now"]()), 3),
                  "data": data or {}}
            self.events.append(ev)
            subs = list(self.subs)
        for queue in subs:
            try:
                queue.put_nowait(ev)
            except Exception:  # noqa: BLE001  (a slow client must not stall the run)
                pass
        return self.seq

    def since(self, seq: int) -> List[Dict[str, Any]]:
        with self.lock:
            return [e for e in self.events if e["seq"] > seq]

    def set_state(self, state: str) -> None:
        # Cancellation is monotonic: once the console says stop, no later stage may
        # move the state forward again (that is how a cancelled run used to keep going
        # and report "reading").
        if self.cancelled.is_set() and state != "cancelled":
            return
        self.state = state
        self.emit("state", {"state": state})

    def ensure_live(self) -> None:
        if self.cancelled.is_set():
            raise Cancelled("cancelled by the console")

    def step_start(self, kind: str, title: str) -> Dict[str, Any]:
        step = {"id": uuid.uuid4().hex[:8], "kind": kind, "title": _trim(title, 200),
                "state": "running", "started": round(float(self.ctx["now"]()), 3), "ms": 0}
        self.steps.append(step)
        self.emit("step.start", {"id": step["id"], "kind": kind, "title": step["title"]})
        return step

    def step_end(self, step: Dict[str, Any], state: str = "ok", detail: str = "") -> None:
        step["state"] = state
        step["ms"] = int((float(self.ctx["now"]()) - step["started"]) * 1000)
        step["detail"] = _redact(_trim(detail, 300))
        self.emit("step.end", {"id": step["id"], "state": state, "ms": step["ms"],
                               "detail": step["detail"]})

    # persistence ----------------------------------------------------------------
    def snapshot(self, full: bool = True) -> Dict[str, Any]:
        data = {"id": self.id, "question": self.question, "depth": self.depth,
                "persona": self.persona, "created": self.created, "state": self.state,
                "parent": self.parent,
                "steps": self.steps, "ms": int(((self.ended or float(self.ctx["now"]()))
                                                - self.created) * 1000),
                "error": self.error, "model_calls": self.model_calls,
                "searches": self.searches, "timing": self.timing,
                "verification": self.verification, "attempts": self.attempts,
                "lanes": self.lanes, "subquestions": self.subquestions,
                "decision": self.decision, "rejected": self.rejected}
        if full:
            data["report"] = self.report
            data["sources"] = self.sources
        return data

    def save(self, full: bool = True) -> None:
        try:
            os.makedirs(_dir(self.ctx), exist_ok=True)
            path = os.path.join(_dir(self.ctx), self.id + ".json")
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(self.snapshot(full), fh, ensure_ascii=False)
            os.replace(tmp, path)
        except OSError as exc:
            self.ctx["notify"]("research: save %s failed: %s" % (self.id, exc))


# -------------------------------------------------------------------------- pipeline --
def _parse_json(text: str) -> Optional[Dict[str, Any]]:
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(text[start:end + 1])
        return data if isinstance(data, dict) else None
    except ValueError:
        return None


def _plan(ctx, run: Run) -> List[Dict[str, str]]:
    want = DEPTHS[run.depth]["subquestions"]
    dates = _iso(float(ctx["now"]()))
    prompt = PLAN_PROMPT.replace("{n}", str(want)).replace("{date}", dates)
    answer = _chat(ctx, run, "plan", [
        {"role": "system", "content": "You are a search planner. JSON only."},
        {"role": "user", "content": prompt + "\n\nQuestion: " + run.question},
    ], max_tokens=700)
    data = _parse_json(answer) or {}
    items = []
    for row in (data.get("subquestions") or data.get("queries") or []):
        if isinstance(row, dict) and row.get("q"):
            items.append({"q": _trim(str(row["q"]), 220),
                          "why": _trim(str(row.get("why") or ""), 160)})
        elif isinstance(row, str):
            items.append({"q": _trim(row, 220), "why": ""})
    if not items:  # the model did not honour the JSON shape: fall back to the question
        items = [{"q": run.question, "why": "direct search"}]
    return items[:want]


def _search_all(ctx, run: Run, subquestions: List[Dict[str, str]]) -> List[Dict[str, Any]]:
    """Sub-questions in parallel; returns one row per sub-question with its hits."""
    budget = DEPTHS[run.depth]["max_searches"]
    outs: List[Optional[Dict[str, Any]]] = [None] * len(subquestions)

    def work(index: int, row: Dict[str, str]) -> None:
        if run.cancelled.is_set():
            return
        step = run.step_start("search", row["q"])
        started = float(ctx["now"]())
        try:
            hits, error = _search(run.lanes, row["q"], 3)
            run.searches += 1
            for hit in hits:
                hit["subquestion"] = index
            outs[index] = {"q": row["q"], "hits": hits, "error": error,
                           "ms": int((float(ctx["now"]()) - started) * 1000)}
            run.step_end(step, "ok" if hits else "empty",
                         "%d hits via %s" % (len(hits), hits[0]["lane"] if hits else error))
        except Exception as exc:  # noqa: BLE001
            outs[index] = {"q": row["q"], "hits": [], "error": _trim(str(exc), 160),
                           "ms": int((float(ctx["now"]()) - started) * 1000)}
            run.step_end(step, "error", str(exc))

    import concurrent.futures as cf
    wall0 = float(ctx["now"]())
    with cf.ThreadPoolExecutor(max_workers=min(4, max(1, len(subquestions)))) as pool:
        futures = [pool.submit(work, i, row) for i, row in enumerate(subquestions[:budget])]
        for fut in futures:
            try:
                fut.result()
            except Exception:  # noqa: BLE001
                pass
    wall = int((float(ctx["now"]()) - wall0) * 1000)
    rows = [o for o in outs if o]
    serial = sum(o["ms"] for o in rows)
    run.timing["search_serial_ms"] = serial
    run.timing["search_wall_ms"] = wall
    run.timing["search_saved_ms"] = max(0, serial - wall)
    return rows


PROSE_HOSTS = ("wikipedia.org", "endoflife.ai", "techstuffs.net", "britannica.com",
               "reuters.com", "apnews.com", "bbc.com", "bbc.co.uk", "aljazeera.com",
               "statista.com", "ourworldindata.org", "bonbast.com", "tgju.org",
               "github.com", "stackoverflow.com", "dev.to", "medium.com")
# Landing/index pages: they look official, extract to menus, and say nothing.
INDEX_PATHS = re.compile(r"/(about/versions|latest-updates|dashboards|search|category|tag)"
                         r"(/|$)| index\.|/news/?$")


def _hit_score(hit: Dict[str, str]) -> float:
    """Rank a search hit by how likely it is to contain prose about the question."""
    import urllib.parse
    url = hit.get("url") or ""
    path = urllib.parse.urlparse(url).path or "/"
    segments = [s for s in path.split("/") if s]
    score = 0.0
    if len(segments) >= 2:
        score += 2.0                      # /wiki/Android_versions beats /about/
    if len(segments) >= 3:
        score += 0.5
    if any(host in url for host in PROSE_HOSTS):
        score += 2.0
    if "wikipedia.org/wiki/" in url:
        score += 1.5
    if len(hit.get("snippet") or "") > 120:
        score += 1.0
    if path.endswith("/") or not segments:
        score -= 1.5                      # a bare section URL
    if INDEX_PATHS.search(path):
        score -= 2.0
    if re.search(r"\.(gov|edu|int)/", url):
        score += 0.5
    return score


def _pick_urls(rows: List[Dict[str, Any]], limit: int) -> List[str]:
    """One or two pages per sub-question, de-duplicated, prose-rich pages first."""
    picked: List[str] = []
    seen = set()
    for row in sorted(rows, key=lambda r: 0 if r["hits"] else 1):
        taken = 0
        for hit in sorted(row["hits"], key=_hit_score, reverse=True):
            key = _norm_url(hit["url"])
            if key in seen:
                continue
            seen.add(key)
            picked.append(hit["url"])
            taken += 1
            if taken >= 2 or len(picked) >= limit:
                break
        if len(picked) >= limit:
            break
    return picked


def _evidence(run: Run, picked: List[str], pages: Dict[str, Dict[str, Any]]) -> str:
    """The numbered page block the synthesis model is allowed to cite."""
    run.sources = []
    rejected: List[Dict[str, Any]] = []
    blocks = []
    used = 0
    for index, url in enumerate(picked, start=1):
        page = pages.get(_norm_url(url))
        if not page:
            rejected.append({"url": url, "quality": 0.0, "why": "not fetched"})
            continue
        score = _quality(page)
        if score < 0.45:            # a menu, not evidence
            rejected.append({"url": page["url"], "quality": score, "why": "looks like a menu"})
            continue
        content = _trim(page["content"], PAGE_CHARS)
        if used + len(content) > TOTAL_CHARS:
            content = content[: max(600, TOTAL_CHARS - used)]
        if not content:
            continue
        used += len(content)
        title = _trim(page.get("title") or "", 120)
        blocks.append("### [%d] %s\nURL: %s\n%s\n" % (index, title, page["url"], content))
        run.sources.append({"n": index, "url": page["url"], "title": title,
                            "chars": len(content), "lane": page.get("lane", ""),
                            "quality": score})
    run.rejected = rejected
    return "\n".join(blocks)


def _verify(run: Run) -> None:
    """Every cited URL must be a page we actually fetched; invented ones are cut out."""
    fetched = {_norm_url(s["url"]): s for s in run.sources}
    body_urls = URL_RE.findall(run.report)
    invented = []
    for url in body_urls:
        key = _norm_url(url)
        if key not in fetched:
            invented.append(url)
            run.report = run.report.replace(url, "بدون منبع")
    cited = set()
    for url in URL_RE.findall(run.report):
        key = _norm_url(url)
        if key in fetched:
            cited.add(key)
    kept = [s for s in run.sources if _norm_url(s["url"]) in cited]
    run.sources = kept
    run.verification = {"pages_fetched": len(fetched), "urls_in_body": len(body_urls),
                        "sources_cited": len(kept), "invented": invented[:20],
                        "invented_count": len(invented),
                        "clean": not invented and bool(kept)}


def _report_prompt(ctx, run: Run, evidence: str) -> str:
    persona = ""
    if run.persona:
        try:
            import jarvisd_personas as P  # noqa: PLC0415
            text = P.persona_prompt(run.persona, ctx)
            if text:
                persona = "لحن (فقط سبک نوشتن را عوض می‌کند، قواعد بالا را نه):\n" + _trim(text, 600)
        except Exception:  # noqa: BLE001
            persona = ""
    return SYNTH_PROMPT.replace("{date}", _iso(float(ctx["now"]()))).replace(
        "{persona}", persona).replace("{pages}", evidence)


def _synthesise(ctx, run: Run, evidence: str) -> None:
    step = run.step_start("synthesise", "نوشتن گزارش با منبع")
    prompt = _report_prompt(ctx, run, evidence)
    messages = [
        {"role": "system", "content": "You are a meticulous Persian research writer. "
                                      "A claim without the source URL next to it is a bug."},
        {"role": "user", "content": prompt + "\n\nQuestion: " + run.question},
    ]

    def delta(piece: str) -> None:
        run.emit("answer.delta", {"text": _redact(piece)})

    try:
        try:  # streaming gives the console a live report; not every provider allows it
            run.report = _chat(ctx, run, "synthesise", messages, max_tokens=1800,
                               stream_to=delta).strip()
        except Exception as stream_exc:  # noqa: BLE001
            run.emit("state", {"state": "synthesising", "detail": "streaming unavailable, "
                              "retrying without it: " + _trim(str(stream_exc), 120)})
            run.report = _chat(ctx, run, "synthesise-plain", messages,
                               max_tokens=1800).strip()
        if not run.report:
            raise RuntimeError("the model returned an empty report")
        run.step_end(step, "ok", "%d chars" % len(run.report))
    except Exception as exc:  # noqa: BLE001
        run.step_end(step, "error", str(exc))
        raise


def _gap_pass(ctx, run: Run, pages: Dict[str, Dict[str, Any]]) -> str:
    """Depth 3 only: one extra search round for the weakest parts of the evidence."""
    toc = "\n".join("- [%d] %s" % (s["n"], _trim(s.get("title") or s["url"], 100))
                    for s in run.sources[:8])
    try:
        answer = _chat(ctx, run, "gap", [
            {"role": "system", "content": "JSON only."},
            {"role": "user", "content": GAP_PROMPT.format(question=run.question, toc=toc)},
        ], max_tokens=300)
        data = _parse_json(answer) or {}
        queries = [str(q) for q in (data.get("gaps") or []) if isinstance(q, str)][:2]
    except Exception:  # noqa: BLE001  (a gap pass is a bonus, never a failure)
        return ""
    extra: List[str] = []
    for query in queries:
        try:
            hits, _err = _search(run.lanes, query, 2)
        except Exception:  # noqa: BLE001
            continue
        run.searches += 1
        for hit in hits[:1]:
            if _norm_url(hit["url"]) not in pages:
                extra.append(hit["url"])
    if not extra:
        return ""
    more = _extract(ctx, extra[:2], run)
    pages.update(more)
    return _evidence_extra(run, more)


def _evidence_extra(run: Run, pages: Dict[str, Dict[str, Any]]) -> str:
    blocks = []
    start = len(run.sources) + 1
    for offset, page in enumerate(pages.values()):
        score = _quality(page)
        if score < 0.45:
            continue
        content = _trim(page.get("content") or "", PAGE_CHARS)
        if not content:
            continue
        n = start + offset
        blocks.append("### [%d] %s\nURL: %s\n%s\n"
                      % (n, _trim(page.get("title") or "", 120), page["url"], content))
        run.sources.append({"n": n, "url": page["url"], "title": page.get("title") or "",
                            "chars": len(content), "lane": page.get("lane", "")})
    return "\n".join(blocks)


def _execute_followup(ctx, run: Run) -> None:
    """Reuse the parent run's sources; search only if the follow-up truly needs it.

    Two model calls at most: one to decide whether fresh facts are required, one to write
    the answer. Pages come from the shared page cache first, so a follow-up is usually
    seconds, not a new research run.
    """
    deadline = run.created + RUN_DEADLINE[run.depth]
    try:
        run.set_state("reading")
        step = run.step_start("read", "بازخوانی %d منبع قبلی" % len(run.parent_urls))
        pages = _extract(ctx, run.parent_urls, run, max_age=PAGE_TTL_FOLLOWUP)
        run.step_end(step, "ok" if pages else "empty",
                     "%d/%d pages" % (len(pages), len(run.parent_urls)))
        if not pages:
            raise RuntimeError("the sources of the earlier run are no longer readable")
        run.ensure_live()

        run.set_state("planning")
        step = run.step_start("plan", "آیا جست‌وجوی تازه لازم است؟")
        toc = "\n".join("- [%d] %s" % (i, _trim((p or {}).get("title") or u, 100))
                        for i, (u, p) in enumerate(pages.items(), start=1))
        queries: List[str] = []
        try:
            answer = _chat(ctx, run, "followup-plan", [
                {"role": "system", "content": "JSON only."},
                {"role": "user", "content": FOLLOWUP_PLAN.format(
                    parent_q=run.parent_report[:200] or run.question, toc=toc,
                    question=run.question)},
            ], max_tokens=300)
            data = _parse_json(answer) or {}
            queries = [str(q) for q in (data.get("queries") or []) if isinstance(q, str)][:2]
            run.decision = {"need_search": bool(data.get("need_search")), "queries": queries,
                            "why": _trim(str(data.get("why") or ""), 160)}
            run.step_end(step, "ok", "need_search=%s" % bool(queries))
        except Exception as exc:  # noqa: BLE001  (a follow-up must not die on the planner)
            run.step_end(step, "warn", str(exc))
            run.decision = {"need_search": False, "queries": [], "why": "planner failed"}

        if queries and not run.cancelled.is_set():
            run.set_state("searching")
            extra: List[str] = []
            for query in queries:
                hits, _err = _search(run.lanes, query, 2)
                run.searches += 1
                for hit in hits[:1]:
                    if _norm_url(hit["url"]) not in pages:
                        extra.append(hit["url"])
            if extra:
                fresh = _extract(ctx, extra[:2], run)
                pages.update(fresh)
        run.ensure_live()

        run.set_state("reading")
        evidence = _evidence(run, list(pages.keys()), pages)
        if not evidence:
            raise RuntimeError("no usable page from the earlier run")
        run.save(False)

        run.set_state("synthesising")
        step = run.step_start("synthesise", "نوشتن پاسخ پیگیرانه")
        messages = [
            {"role": "system", "content": "You are a meticulous Persian research writer. "
                                          "A claim without the source URL next to it is a bug."},
            {"role": "user", "content": FOLLOWUP_CONTEXT.format(
                prev=_trim(run.parent_report, 1200)) + "\n\n" + _report_prompt(ctx, run, evidence)
                + "\n\nFollow-up question: " + run.question},
        ]

        def delta(piece: str) -> None:
            run.emit("answer.delta", {"text": _redact(piece)})

        try:
            run.report = _chat(ctx, run, "followup", messages, max_tokens=1400,
                               stream_to=delta).strip()
        except Exception:  # noqa: BLE001  (provider without streaming)
            run.report = _chat(ctx, run, "followup-plain", messages, max_tokens=1400).strip()
        run.step_end(step, "ok" if run.report else "empty", "%d chars" % len(run.report))
        run.ensure_live()

        _verify(run)
        run.ended = float(ctx["now"]())
        run.set_state("done" if run.report else "error")
        run.emit("done", {"id": run.id, "ms": int((run.ended - run.created) * 1000),
                          "sources": len(run.sources), "state": run.state,
                          "parent": run.parent})
        ctx["notify"]("research: follow-up %s on %s done in %ss (%d searches)"
                      % (run.id, run.parent, int(run.ended - run.created), run.searches))
    except Cancelled:
        run.ended = float(ctx["now"]())
        run.error = "cancelled by the console"
        run.state = "cancelled"
        run.emit("error", {"detail": run.error, "state": "cancelled"})
        run.emit("done", {"id": run.id, "state": "cancelled",
                          "ms": int((run.ended - run.created) * 1000),
                          "sources": len(run.sources)})
        ctx["notify"]("research: follow-up %s cancelled" % run.id)
    except Exception as exc:  # noqa: BLE001
        run.ended = float(ctx["now"]())
        run.error = _redact(_trim(str(exc), 400))
        run.state = "cancelled" if run.cancelled.is_set() else "error"
        run.emit("error", {"detail": run.error, "state": run.state})
        run.emit("done", {"id": run.id, "state": run.state,
                          "ms": int((run.ended - run.created) * 1000),
                          "sources": len(run.sources)})
        ctx["notify"]("research: follow-up %s %s: %s" % (run.id, run.state, run.error))
    finally:
        run.save(True)


def _execute(ctx, run: Run) -> None:
    if run.parent:
        _execute_followup(ctx, run)
        return
    deadline = run.created + RUN_DEADLINE[run.depth]

    def check_deadline(stage: str) -> None:
        if float(ctx["now"]()) > deadline:
            raise RuntimeError("deadline of %ds reached during %s"
                               % (RUN_DEADLINE[run.depth], stage))
        run.ensure_live()

    try:
        run.set_state("planning")
        step = run.step_start("plan", "تقسیم سؤال به زیرسؤال")
        try:
            run.subquestions = _plan(ctx, run)
            run.step_end(step, "ok", "%d sub-questions" % len(run.subquestions))
        except Cancelled:
            run.step_end(step, "cancelled", "cancelled")
            raise
        except Exception as exc:  # noqa: BLE001
            run.step_end(step, "error", str(exc))
            raise
        run.save(False)
        check_deadline("planning")

        run.set_state("searching")
        rows = _search_all(ctx, run, run.subquestions)
        run.ensure_live()
        picked = _pick_urls(rows, DEPTHS[run.depth]["max_pages"])
        if not picked:
            raise RuntimeError("no search result survived (searched %d sub-questions)"
                               % len(rows))
        run.save(False)
        check_deadline("searching")

        run.set_state("reading")
        step = run.step_start("read", "خواندن %d صفحه" % len(picked))
        pages = _extract(ctx, picked, run)
        run.ensure_live()
        evidence = _evidence(run, picked, pages)
        if len(run.sources) < 3:
            # Menus and 403s can eat the whole budget; spend one more batch on the
            # next-ranked hits before settling for two pages of evidence.
            spare = [u for u in _pick_urls(rows, DEPTHS[run.depth]["max_pages"] + 4)
                     if _norm_url(u) not in pages][:4]
            if spare:
                pages.update(_extract(ctx, spare, run))
                run.ensure_live()
                evidence = _evidence(run, picked + spare, pages)
        run.step_end(step, "ok" if evidence else "empty",
                     "%d usable page(s) of %d fetched" % (len(run.sources), len(pages)))
        if not evidence:
            raise RuntimeError("no readable page: every fetched page looked like a menu")
        run.save(False)
        check_deadline("reading")

        if run.depth >= 3 and run.model_calls < DEPTHS[3]["max_calls"]:
            run.set_state("searching")
            extra = _gap_pass(ctx, run, pages)
            run.ensure_live()
            if extra:
                evidence = evidence + "\n" + extra

        run.set_state("synthesising")
        _synthesise(ctx, run, evidence)
        run.ensure_live()

        _verify(run)
        run.ended = float(ctx["now"]())
        run.set_state("done" if run.report else "error")
        run.emit("done", {"id": run.id, "ms": int((run.ended - run.created) * 1000),
                          "sources": len(run.sources), "state": run.state})
        ctx["notify"]("research: %s done in %ss (%d sources, %d searches, %d calls)"
                      % (run.id, int(run.ended - run.created), len(run.sources),
                         run.searches, run.model_calls))
    except Cancelled:
        run.ended = float(ctx["now"]())
        run.error = "cancelled by the console"
        run.state = "cancelled"
        run.emit("error", {"detail": run.error, "state": "cancelled"})
        run.emit("done", {"id": run.id, "ms": int((run.ended - run.created) * 1000),
                          "sources": len(run.sources), "state": "cancelled"})
        ctx["notify"]("research: %s cancelled" % run.id)
    except Exception as exc:  # noqa: BLE001
        run.ended = float(ctx["now"]())
        run.error = _redact(_trim(str(exc), 400))
        run.state = "cancelled" if run.cancelled.is_set() else "error"
        run.emit("error", {"detail": run.error, "state": run.state})
        run.emit("done", {"id": run.id, "ms": int((run.ended - run.created) * 1000),
                          "sources": len(run.sources), "state": run.state})
        ctx["notify"]("research: %s %s: %s" % (run.id, run.state, run.error))
    finally:
        if run.state not in ("done", "error", "cancelled"):
            run.ended = run.ended or float(ctx["now"]())
            run.state = "cancelled" if run.cancelled.is_set() else run.state
        run.save(True)


# ------------------------------------------------------------------------ registry --
def _get(ctx, rid: str) -> Optional[Run]:
    with _LOCK:
        run = _RUNS.get(rid)
    if run is not None:
        return run
    try:  # a finished run survives a daemon restart: rehydrate from disk
        with open(os.path.join(_dir(ctx), rid + ".json"), encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    run = Run(ctx, str(data.get("question") or ""), int(data.get("depth") or 2),
              str(data.get("persona") or ""))
    run.id = str(data.get("id") or rid)
    run.created = float(data.get("created") or run.created)
    run.state = str(data.get("state") or "done")
    run.steps = data.get("steps") or []
    run.sources = data.get("sources") or []
    run.report = str(data.get("report") or "")
    run.error = str(data.get("error") or "")
    run.timing = data.get("timing") or {}
    run.verification = data.get("verification") or {}
    run.attempts = data.get("attempts") or []
    run.lanes = data.get("lanes") or []
    run.subquestions = data.get("subquestions") or []
    run.decision = data.get("decision") or {}
    run.parent = str(data.get("parent") or "")
    run.parent_urls = [str(s.get("url")) for s in run.sources if isinstance(s, dict)]
    run.parent_report = run.report
    run.model_calls = int(data.get("model_calls") or 0)
    run.searches = int(data.get("searches") or 0)
    run.ended = run.created + (float(data.get("ms") or 0) / 1000.0)
    with _LOCK:
        _RUNS[run.id] = run
    return run


def _recent(ctx, limit: int = 20) -> List[Dict[str, Any]]:
    rows: Dict[str, Dict[str, Any]] = {}
    try:
        names = sorted(os.listdir(_dir(ctx)), reverse=True)
    except OSError:
        names = []
    for name in names[:200]:
        if not name.endswith(".json"):
            continue
        try:
            with open(os.path.join(_dir(ctx), name), encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            continue
        rows[str(data.get("id") or name[:-5])] = {
            "id": str(data.get("id") or name[:-5]),
            "question": _redact(_trim(str(data.get("question") or ""), 200)),
            "state": str(data.get("state") or ""), "created": data.get("created") or 0,
            "ms": data.get("ms") or 0, "depth": data.get("depth") or 2,
            "sources": len(data.get("sources") or [])}
    with _LOCK:
        for run in _RUNS.values():
            rows[run.id] = {"id": run.id, "question": _trim(run.question, 200),
                            "state": run.state, "created": run.created,
                            "ms": int(((run.ended or float(ctx["now"]())) - run.created) * 1000),
                            "depth": run.depth, "sources": len(run.sources)}
    ordered = sorted(rows.values(), key=lambda r: float(r.get("created") or 0), reverse=True)
    return ordered[: max(1, min(limit, 100))]


def start_research(ctx, question: str, depth: int = 2, persona: str = "",
                   parent: "Optional[Run]" = None) -> Run:
    run = Run(ctx, question, depth, persona,
              parent=parent.id if parent else "",
              parent_urls=[str(s.get("url")) for s in (parent.sources if parent else [])],
              parent_report=parent.report if parent else "")
    with _LOCK:
        _RUNS[run.id] = run
    run.save(False)
    thread = threading.Thread(target=_execute, args=(ctx, run), name="research-" + run.id,
                              daemon=True)
    thread.start()
    return run


# ---------------------------------------------------------------------------- routes --
def register(app, ctx):  # noqa: C901
    from fastapi.responses import StreamingResponse

    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    async def body(req) -> Dict[str, Any]:
        try:
            raw = await req.body()
            data = json.loads(raw) if raw else {}
        except Exception:  # noqa: BLE001
            return {}
        return data if isinstance(data, dict) else {}

    @app.post("/research")
    async def research_start(req: Request):
        data = await body(req)
        question = _trim(str(data.get("question") or ""), 2000)
        if len(question) < 3:
            return err("bad_question", "question is required")
        try:
            depth = int(data.get("depth") or 2)
        except (TypeError, ValueError):
            depth = 2
        if depth not in DEPTHS:
            return err("bad_depth", "depth must be 1, 2 or 3")
        persona = str(data.get("persona") or "")
        try:
            run = start_research(ctx, question, depth, persona)
        except Exception as exc:  # noqa: BLE001
            return err("internal", _redact(str(exc)), 500)
        ctx["notify"]("research: %s queued (depth %d)" % (run.id, depth))
        return {"ok": True, "id": run.id}

    @app.post("/research/{rid}/followup")
    async def research_followup(rid: str, req: Request):
        """Ask a follow-up of a finished run: its sources are reused, not re-researched."""
        if not re.match(r"^[0-9a-f]{6,32}$", rid):
            return err("bad_id", rid)
        parent = _get(ctx, rid)
        if parent is None:
            return err("not_found", rid, 404)
        if parent.state != "done" or not parent.sources:
            return err("not_ready", "run %s is %s with %d sources"
                       % (rid, parent.state, len(parent.sources)), 409)
        data = await body(req)
        question = _trim(str(data.get("question") or ""), 2000)
        if len(question) < 3:
            return err("bad_question", "question is required")
        persona = str(data.get("persona") or parent.persona)
        try:
            run = start_research(ctx, question, min(3, max(1, parent.depth)), persona,
                                 parent=parent)
        except Exception as exc:  # noqa: BLE001
            return err("internal", _redact(str(exc)), 500)
        ctx["notify"]("research: follow-up %s on %s queued" % (run.id, parent.id))
        return {"ok": True, "id": run.id, "parent": parent.id,
                "reused_sources": len(parent.sources)}

    @app.get("/research/{rid}")
    def research_get(rid: str):
        if not re.match(r"^[0-9a-f]{6,32}$", rid):
            return err("bad_id", rid)
        run = _get(ctx, rid)
        if run is None:
            return err("not_found", rid, 404)
        data = run.snapshot(True)
        data["ok"] = run.state in ("done", "cancelled")
        data["lanes"] = run.lanes
        return data

    @app.get("/research/{rid}/events")
    async def research_events(rid: str, since: int = 0):
        if not re.match(r"^[0-9a-f]{6,32}$", rid):
            return err("bad_id", rid)
        run = _get(ctx, rid)
        if run is None:
            return err("not_found", rid, 404)

        async def gen():
            import asyncio
            queue: Any = asyncio.Queue(maxsize=256)
            with run.lock:
                run.subs.append(queue)
            try:
                for ev in run.since(max(0, since)):
                    yield _sse(ev)
                if run.state in ("done", "error", "cancelled"):
                    yield _sse({"seq": run.seq + 1, "kind": "done",
                                "ts": round(float(ctx["now"]()), 3),
                                "data": {"id": run.id, "state": run.state,
                                         "replayed": True}})
                    return
                while True:
                    try:
                        ev = await asyncio.wait_for(queue.get(), timeout=15)
                    except asyncio.TimeoutError:
                        yield ": ping\n\n"
                        if run.state in ("done", "error", "cancelled"):
                            return
                        continue
                    yield _sse(ev)
                    if ev["kind"] in ("done", "error"):
                        return
            finally:
                with run.lock:
                    if queue in run.subs:
                        run.subs.remove(queue)

        return StreamingResponse(gen(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache",
                                          "X-Accel-Buffering": "no"})

    @app.post("/research/{rid}/cancel")
    def research_cancel(rid: str):
        if not re.match(r"^[0-9a-f]{6,32}$", rid):
            return err("bad_id", rid)
        run = _get(ctx, rid)
        if run is None:
            return err("not_found", rid, 404)
        was_live = run.state not in ("done", "error", "cancelled")
        run.cancelled.set()
        if was_live:
            run.set_state("cancelled")
            for step in run.steps:            # leave no step spinning for ever
                if step.get("state") == "running":
                    run.step_end(step, "cancelled", "cancelled")
            run.emit("error", {"detail": "cancelled by the console", "state": "cancelled"})
            run.emit("done", {"id": run.id, "state": "cancelled",
                              "ms": int((float(ctx["now"]()) - run.created) * 1000),
                              "sources": len(run.sources)})
            run.ended = float(ctx["now"]())
            run.error = "cancelled by the console"
            run.save(True)
            ctx["notify"]("research: %s cancelled" % run.id)
        return {"ok": True, "state": run.state, "cancelled": True, "was_live": was_live}

    @app.get("/research")
    def research_recent(limit: int = 20):
        try:
            return _recent(ctx, limit)
        except Exception as exc:  # noqa: BLE001
            return err("internal", _redact(str(exc)), 500)


def _sse(ev: Dict[str, Any]) -> str:
    return "data: %s\n\n" % json.dumps(ev, ensure_ascii=False, default=str)
