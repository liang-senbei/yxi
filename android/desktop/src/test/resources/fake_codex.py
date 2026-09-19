#!/usr/bin/env python3
"""Fake `codex app-server` for offline protocol/controller tests. No model, no network, no auth.

Environment:
  FAKE_LOG            append every inbound line and every outbound line (">> " prefix)
  FAKE_THREAD_ID      thread id this fake serves (default thr-1)
  FAKE_OTHER_THREAD_ID  used by foreign-completion mode (default thr-other)

Modes:
  happy             initialize -> result; other requests answered generically
  outoforder        holds the first request, replies to the second one first
  notifications     emits thread/compacted after init and turn/started before turn replies
  approval          emits an item/commandExecution/requestApproval server request after init
  eof-on-request    answers initialize, exits as soon as the next request arrives
  init-error        answers initialize with a JSON-RPC error (code 402)
  noreply           answers initialize only, never answers later requests
  seq-auto          thread/read = empty thread; each turn/start replies turn-N then completes it
  completed-first   like seq-auto but turn/completed notification precedes the turn reply
  die-on-turn-start thread/read ok; exits the moment a turn/start arrives
  foreign-completion turn/start replies turn-1; then a turn/completed for the OTHER thread
                    plus a same-thread thread/compacted marker (FIFO lets tests await it)
  steer-mismatch    turn/steer replies turnId "turn-99" (never the expected turn-1)
  delta-full        turn/start: item/completed userMessage, agentMessage deltas, full agentMessage
  read-interleave   1st read = historical snapshot; turn/start streams msg-live; later reads
                    return the full snapshot including msg-live
  new-thread        thread/read always returns an empty turns list
  models            model/list serves two pages (m-a, hidden m-h, then m-b via cursor)
  create-ok         default branches already answer thread/start, resume and read
  create-snapshot   thread/start replies the real idle empty-thread shape; thread/read fails
                    with -32601 until a resume happens, then returns history (CLI 0.153.4)
"""
import json
import os
import sys

mode = sys.argv[sys.argv.index("--mode") + 1] if "--mode" in sys.argv else "happy"
log_path = os.environ.get("FAKE_LOG")
thread_id = os.environ.get("FAKE_THREAD_ID", "thr-1")
other_thread = os.environ.get("FAKE_OTHER_THREAD_ID", "thr-other")
reads = [0]
resumed = [False]


def record(line, outbound=False):
    if log_path:
        with open(log_path, "a", encoding="utf-8") as log:
            log.write((">> " if outbound else "") + line + "\n")


def send(obj):
    line = json.dumps(obj, ensure_ascii=False)
    record(line, outbound=True)
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


def notify(method, params):
    send({"method": method, "params": params})


def reply(req, result):
    send({"id": req["id"], "result": result})


def thread_reply(req, turns):
    reply(req, {"thread": {"id": thread_id, "turns": turns}})


def item(item_id, item_type, **fields):
    data = {"id": item_id, "type": item_type}
    data.update(fields)
    return data


def user_item(item_id, text):
    return item(item_id, "userMessage", content=[{"type": "text", "text": text}])


def agent_item(item_id, text):
    return item(item_id, "agentMessage", text=text)


def turn(turn_id, status, items=None):
    data = {"id": turn_id, "status": status}
    if items is not None:
        data["items"] = items
    return data


HISTORY_ITEMS = [user_item("msg-h0", "第一条"), agent_item("msg-h1", "回复一")]
FULL_SNAPSHOT = [turn("t0", "completed", HISTORY_ITEMS),
                 turn("t1", "completed", [agent_item("msg-live", "LIVE完整")])]

counter = [0]


def next_turn():
    counter[0] += 1
    return "turn-%d" % counter[0]


held = []

for raw in sys.stdin:
    raw = raw.strip()
    if not raw:
        continue
    record(raw)
    msg = json.loads(raw)
    method = msg.get("method")
    if method is None:
        continue  # reply to a server request we issued (e.g. approval) — just logged
    if msg.get("id") is None:
        continue  # client notification (e.g. "initialized") — just logged

    if method == "initialize":
        if mode == "init-error":
            send({"id": msg["id"], "error": {"code": 402, "message": "no auth"}})
            os._exit(0)
        reply(msg, {"userAgent": {"name": "fake-codex", "version": "0.0.1"}})
        if mode == "notifications":
            notify("thread/compacted", {"threadId": thread_id, "note": "before-requests"})
        if mode == "approval":
            send({"id": "srv-77", "method": "item/commandExecution/requestApproval",
                  "params": {"threadId": thread_id, "command": "rm -rf /srv/demo", "cwd": "/srv/demo",
                             "reason": "fixture"}})
        continue

    if mode == "outoforder":
        held.append(msg)
        if len(held) >= 2:
            for late in reversed(held):
                reply(late, {"served": late["method"]})
            held = []
        continue
    if mode == "eof-on-request":
        os._exit(0)
    if mode == "noreply":
        continue
    if mode == "die-on-turn-start" and method == "turn/start":
        os._exit(0)

    if method == "thread/read":
        reads[0] += 1
        if mode == "create-snapshot" and not resumed[0]:
            # Real CLI 0.153.4: includeTurns read right after start is rejected
            send({"id": msg["id"], "error": {"code": -32601, "message": "list_turns is not supported yet"}})
            continue
        if mode == "create-snapshot":
            thread_reply(msg, [turn("t0", "completed", HISTORY_ITEMS)])
        elif mode == "new-thread":
            thread_reply(msg, [])
        elif mode == "read-interleave" and reads[0] >= 2:
            thread_reply(msg, FULL_SNAPSHOT)
        elif mode == "read-interleave":
            thread_reply(msg, [turn("t0", "completed", HISTORY_ITEMS)])
        else:
            thread_reply(msg, [])
        continue
    if method == "thread/start":
        if mode == "create-snapshot":
            # Real CLI 0.153.4 shape: rich thread, idle, empty history
            reply(msg, {"thread": {"id": thread_id, "cwd": "/srv/demo", "turns": [],
                                   "status": {"type": "idle"}}})
        else:
            reply(msg, {"thread": {"id": thread_id, "cwd": "/srv/demo"}})
        continue
    if method == "thread/resume":
        resumed[0] = True
        thread_reply(msg, [])
        continue
    if method == "turn/steer":
        if mode == "steer-mismatch":
            reply(msg, {"turnId": "turn-99"})
        else:
            reply(msg, {"turnId": "turn-1"})
        continue
    if method == "model/list":
        if msg["params"].get("cursor") == "page2":
            reply(msg, {"data": [{"model": "m-b", "displayName": "Beta",
                                  "supportedReasoningEfforts": [{"reasoningEffort": "medium"}],
                                  "defaultReasoningEffort": "medium"}]})
        else:
            reply(msg, {"data": [{"model": "m-a", "displayName": "Alpha",
                                  "supportedReasoningEfforts": [{"reasoningEffort": "low"},
                                                                {"reasoningEffort": "high"}],
                                  "defaultReasoningEffort": "high"},
                                 {"model": "m-h", "displayName": "Hidden", "hidden": True,
                                  "supportedReasoningEfforts": [], "defaultReasoningEffort": ""}],
                        "nextCursor": "page2"})
        continue

    if method == "turn/start":
        if mode == "seq-auto":
            turn_id = next_turn()
            reply(msg, {"turn": {"id": turn_id, "status": "inProgress"}})
            notify("turn/completed", {"threadId": thread_id,
                                      "turn": {"id": turn_id, "status": "completed"}})
            continue
        if mode == "completed-first":
            turn_id = next_turn()
            notify("turn/started", {"threadId": thread_id,
                                    "turn": {"id": turn_id, "status": "inProgress"}})
            notify("turn/completed", {"threadId": thread_id,
                                      "turn": {"id": turn_id, "status": "completed"}})
            reply(msg, {"turn": {"id": turn_id, "status": "inProgress"}})
            continue
        if mode == "foreign-completion":
            reply(msg, {"turn": {"id": "turn-1", "status": "inProgress"}})
            notify("turn/completed", {"threadId": other_thread,
                                      "turn": {"id": "turn-1", "status": "completed"}})
            notify("thread/compacted", {"threadId": thread_id, "note": "after-foreign"})
            continue
        if mode == "steer-mismatch":
            reply(msg, {"turn": {"id": "turn-1", "status": "inProgress"}})
            continue
        if mode == "delta-full":
            reply(msg, {"turn": {"id": "turn-1", "status": "inProgress"}})
            notify("item/completed", {"threadId": thread_id, "item": user_item("msg-0", "帮我看下")})
            notify("item/agentMessage/delta", {"threadId": thread_id, "itemId": "msg-1", "delta": "你"})
            notify("item/agentMessage/delta", {"threadId": thread_id, "itemId": "msg-1", "delta": "好"})
            notify("item/completed", {"threadId": thread_id, "item": agent_item("msg-1", "你好，世界")})
            notify("turn/completed", {"threadId": thread_id,
                                      "turn": {"id": "turn-1", "status": "completed"}})
            continue
        if mode == "read-interleave":
            reply(msg, {"turn": {"id": "turn-1", "status": "inProgress"}})
            notify("item/agentMessage/delta", {"threadId": thread_id, "itemId": "msg-live", "delta": "L1"})
            notify("item/agentMessage/delta", {"threadId": thread_id, "itemId": "msg-live", "delta": "L2"})
            notify("item/completed", {"threadId": thread_id, "item": agent_item("msg-live", "LIVE完整")})
            notify("turn/completed", {"threadId": thread_id,
                                      "turn": {"id": "turn-1", "status": "completed"}})
            continue
        if mode == "notifications":
            notify("turn/started", {"threadId": thread_id,
                                    "turn": {"id": "turn-1", "status": "inProgress"}})
        reply(msg, {"turn": {"id": "turn-1", "status": "inProgress"}})
        continue

    reply(msg, {"ok": True})
