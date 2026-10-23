#!/usr/bin/env python3
"""A PreToolUse hook that refuses edits to protected parts of the repository.

Claude Code runs this before a file-editing tool call, hands it a JSON payload on
standard input, and reads the exit code:

    exit 0   the call goes ahead
    exit 2   the call is blocked and whatever this script printed to stderr is
             shown to the agent as the reason

Exit code 1 does NOT block. It is treated as a broken hook and the tool call
proceeds, so never signal a refusal with 1.

That last rule is the reason the settings snippet does not call this script
directly. It calls it through a shell wrapper:

    sh -c 'python3 "$CLAUDE_PROJECT_DIR/guardrails/protect.py"; [ $? -eq 0 ] || exit 2'

The wrapper turns EVERY non-zero exit into a 2. Without it, a typo in
PROTECTED_PATHS is a syntax error, Python exits 1 before any code in this file
runs, and the edit goes through. The try/except at the bottom cannot catch that,
because the file never gets as far as being executed. Use the wrapper.

Inside that wrapper this script fails closed. An unreadable payload, a
file-editing call whose target it cannot work out, an unconfigured protected
list, and an exception at run time all end in exit 2.

Set PROTECTED_PATHS below to the parts of the repository your plan puts out of
scope. Until you do, every file-editing call is blocked, because a guardrail that
is configured to protect nothing should say so.
Entries are repository-relative directories. Matching resolves symlinks and is
anchored to the repository root, so a path that merely looks similar
(`../other-repo/billing/x`) does not match.

Try it without an agent:

    python3 guardrails/protect.py --self-test

    echo '{"tool_name":"Edit","tool_input":{"file_path":"billing/x.java"}}' \\
        | python3 guardrails/protect.py ; echo "exit $?"

Stdlib only, so there is nothing to install.
"""

from __future__ import annotations

import json
import os
import sys

# The token this file ships with, so an unedited list is obvious.
PLACEHOLDER = "MODULE-YOU-ARE-NOT-TOUCHING/"

# EDIT THIS LIST. One repository-relative directory per entry, trailing slash.
PROTECTED_PATHS = [
    "billing/",
    "cli/",
    "accounts/",
]

# Files that must not change whatever the plan says. These are the conventions
# the plan itself cites, so an agent rewriting them to suit its work is caught.
PROTECTED_FILES = [
    "AGENTS.md",
    "CLAUDE.md",
    "CHANGE-REQUEST.md",
    # PLAN.md: the revenue report changes only through the shared ledger fold.
    "analytics/campusreserve_analytics/revenue.py",
    "analytics/tests/test_revenue.py",
]

# Tools that touch no file, so this hook has nothing to check and lets them by.
# Anything NOT on this list is treated as able to write, including a tool name
# this script has never heard of, which is what keeps a future editing tool from
# slipping past. Bash is here, but the hook never sees it anyway (see
# guardrails/README.md).
NON_FILE_TOOLS = (
    "Bash", "BashOutput", "KillShell", "Read", "Grep", "Glob",
    "WebFetch", "WebSearch", "Task", "TodoWrite",
)

ALLOW = 0
BLOCK = 2


def project_root() -> str:
    """The directory every protected entry is measured from.

    Claude Code sets CLAUDE_PROJECT_DIR for hook commands. Outside a hook the
    working directory stands in, which is what makes the manual echo test work.
    """
    return os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())


def target_path(payload: dict) -> str:
    """The file the tool call is about, or an empty string when there is none.

    Write, Edit, and Read carry file_path. NotebookEdit carries notebook_path.
    """
    tool_input = payload.get("tool_input") or {}
    if not isinstance(tool_input, dict):
        return ""
    path = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
    return str(path)


def resolve(path: str, root: str) -> str:
    """An absolute, symlink-resolved path. A relative path is read against root."""
    candidate = path if os.path.isabs(path) else os.path.join(root, path)
    return os.path.realpath(candidate)


def is_inside(target: str, container: str) -> bool:
    """Is target the container itself, or somewhere under it?"""
    try:
        return os.path.commonpath([container, target]) == container
    except ValueError:
        # Different drives on Windows, or a mix this cannot compare.
        return False


def violation(path: str, root: str, paths, files) -> str | None:
    """The entry this path violates, or None when the path is in bounds."""
    if not path:
        return None
    target = resolve(path, root)
    for entry in paths:
        if is_inside(target, resolve(entry.rstrip("/"), root)):
            return entry
    for entry in files:
        if target == resolve(entry, root):
            return entry
    return None


def refuse(message: str) -> int:
    """Prints the reason the agent will be shown, and returns the blocking code."""
    print(message, file=sys.stderr)
    sys.stderr.flush()
    return BLOCK


def decide(payload: dict, root: str, paths, files) -> int:
    """ALLOW or BLOCK for one hook payload, printing the reason when blocking."""
    tool = payload.get("tool_name") or "an unnamed tool"
    path = target_path(payload)

    if tool in NON_FILE_TOOLS:
        return ALLOW

    if PLACEHOLDER in paths:
        return refuse(
            f"protect.py blocked {tool}: this guardrail is not configured. Edit"
            " PROTECTED_PATHS at the top of guardrails/protect.py, replacing"
            f" {PLACEHOLDER!r} with the modules your plan puts out of scope. Every"
            " file-editing call is blocked until you do.")

    if not path:
        return refuse(
            f"protect.py blocked {tool}: it can write files but this call carries no"
            " file path, so there is no way to tell whether it is in bounds.")

    hit = violation(path, root, paths, files)
    if hit is None:
        return ALLOW

    relation = "is under" if hit.endswith("/") else "matches"
    return refuse(
        f"protect.py blocked {tool}: {path} {relation} {hit}, which this plan puts"
        " out of scope. Ask the user before changing the scope.")


def report_placeholder() -> None:
    """Says so on stdout, for the self test, while the placeholder is in the list.

    This goes to stdout because that is where a person reading the self test is
    looking. It is deliberately not the only signal. With the placeholder in
    place every file-editing call is blocked outright, which the agent sees.
    """
    if PLACEHOLDER in PROTECTED_PATHS:
        print(
            f"\nNOT CONFIGURED: PROTECTED_PATHS still holds {PLACEHOLDER!r}. Every"
            " file-editing call is blocked until you replace it with the modules"
            " your plan puts out of scope.")
        sys.stdout.flush()


def self_test() -> int:
    """Checks the matching rules against a synthetic list of its own.

    This does not read PROTECTED_PATHS, so it answers the same way before and
    after you edit that list. It tells you the matching works. It does not tell
    you your list is right.
    """
    root = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    paths = ["out-of-scope-module/"]
    files = ["AGENTS.md"]

    cases = [
        ("blocked", {"tool_name": "Edit",
                     "tool_input": {"file_path": "out-of-scope-module/Money.java"}}),
        ("blocked", {"tool_name": "Write", "tool_input": {"file_path": "AGENTS.md"}}),
        ("blocked", {"tool_name": "NotebookEdit",
                     "tool_input": {"notebook_path": "out-of-scope-module/notes.ipynb"}}),
        ("blocked", {"tool_name": "Edit", "tool_input": {}}),
        ("blocked", {"tool_name": "SomeFutureEditingTool", "tool_input": {}}),
        ("allowed", {"tool_name": "Edit", "tool_input": {"file_path": "core-domain/X.java"}}),
        ("allowed", {"tool_name": "Bash", "tool_input": {"command": "mvn -q test"}}),
        ("allowed", {"tool_name": "Edit",
                     "tool_input": {"file_path": "../out-of-scope-module/Money.java"}}),
    ]

    failures = 0
    for expected, payload in cases:
        actual = "blocked" if decide(payload, root, paths, files) == BLOCK else "allowed"
        mark = "ok " if actual == expected else "BAD"
        print(f"{mark} expected {expected}, got {actual}: {target_path(payload) or payload}")
        sys.stdout.flush()
        failures += actual != expected

    print(f"\nroot: {root}")
    print(f"your list: {', '.join(list(PROTECTED_PATHS) + list(PROTECTED_FILES))}")
    report_placeholder()
    return 1 if failures else 0


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        return self_test()

    raw = sys.stdin.read()
    if not raw.strip():
        return refuse("protect.py: the hook payload was empty, refusing the call.")
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as failure:
        return refuse(f"protect.py: could not read the hook payload ({failure}),"
                      " refusing the call.")
    if not isinstance(payload, dict):
        return refuse("protect.py: the hook payload was not an object, refusing the call.")

    return decide(payload, project_root(), PROTECTED_PATHS, PROTECTED_FILES)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except BaseException as failure:  # noqa: BLE001
        # Fail closed.
        print(f"protect.py: the hook itself failed ({failure!r}), refusing the call.",
              file=sys.stderr)
        sys.exit(BLOCK)
