#!/usr/bin/env python3
"""Pretty-print out/conversation.jsonl as Markdown."""

import json, os, sys

log = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "conversation.jsonl")
if len(sys.argv) > 1:
    log = sys.argv[1]

if not os.path.exists(log):
    print(f"No log file found: {log}")
    sys.exit(1)

with open(log) as f:
    for line in f:
        entry = json.loads(line)
        role = entry.get("role", "")
        ts = entry.get("timestamp", "")
        content = entry.get("content", "")

        if role == "system":
            print(f"---\n**System** _{ts}_\n")
            print(f"{content}\n")

        elif role == "user":
            print(f"---\n**User** _{ts}_\n")
            print(f"{content}\n")

        elif role == "assistant":
            print(f"---\n**Agent** _{ts}_\n")
            print(f"{content}\n")

        elif role == "tool_call":
            name = entry.get("name", "")
            args = entry.get("arguments", {})
            code = args.get("code", "")
            print(f"---\n**Tool Call: `{name}`** _{ts}_\n")
            if code:
                print(f"```jo\n{code}\n```\n")

        elif role == "tool_result":
            name = entry.get("name", "")
            print(f"**Tool Result: `{name}`** _{ts}_\n")
            print(f"```\n{content}\n```\n")
