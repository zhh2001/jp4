#!/usr/bin/env python3
"""
check-doc-snippets.py — verify the provenance markers on Java code blocks
in `docs/*.md` and `README.md`.

For every ```java ... ``` block, the script reads the marker on the line(s)
immediately above (skipping blank lines) and behaves as follows:

  <!-- snippet: PATH#NAME -->
        Verify mode. Read PATH (relative to the repo root), find the lines
        between `// SNIPPET_START NAME` and `// SNIPPET_END NAME`, strip the
        common leading indent, and compare line-by-line (after rstrip) to
        the doc's code block. Mismatches print a unified diff to stderr and
        the script exits 1.

  <!-- illustrative -->
        Skip the verification, but require a `*Real usage: ...*` footer
        within the 5 lines after the closing fence (so linkable
        illustratives carry a link to the relevant example).

  <!-- illustrative: concept fragment -->
  <!-- illustrative: trimmed adaptation ... -->
  <!-- illustrative: idiomatic ... -->
        Skip entirely — purely conceptual code blocks with no expected
        correspondence to any example file.

  (no marker)
        Fail with a hint listing the available marker classes.

A whole file can opt out by including, in its first 5 lines:

    <!-- doc-lint: skip-file (reason) -->

The reason text is free-form; it shows up in the summary so an audit can
see why each skipped file is skipped. Use this for frozen design /
specification documents that predate the marker convention.

Run from the repo root:
    python3 scripts/check-doc-snippets.py

Exit codes:
    0 — all blocks are properly marked, every snippet matches its example
    1 — at least one mismatch, missing snippet region, missing footer, or
        unmarked block

Options:
    --verbose       print one line per verified snippet
    --list-blocks   list every Java block + its classification, then exit 0
"""

from __future__ import annotations

import difflib
import re
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_GLOBS = ["README.md", "docs/*.md"]

SNIPPET_RE = re.compile(r"<!--\s*snippet:\s*([^\s#]+)#(\S+)\s*-->")
ILLUS_RE   = re.compile(r"<!--\s*illustrative(?::\s*(.+?))?\s*-->")
SKIP_FILE_RE = re.compile(r"<!--\s*doc-lint:\s*skip-file(?:\s*\(([^)]*)\))?\s*-->")
JAVA_OPEN  = "```java"
FENCE_END  = "```"
FOOTER_RE  = re.compile(r"\*Real usage:\s*\[")


def file_skip_reason(text: str) -> str | None:
    """If the file declares an opt-out marker in its first 5 lines, return the
    reason string (possibly empty). Otherwise return None."""
    head = text.splitlines()[:5]
    for line in head:
        m = SKIP_FILE_RE.search(line)
        if m:
            return (m.group(1) or "").strip()
    return None


def find_blocks(text: str):
    """Yield (line_no, marker_text, code_lines, post_lines) for each ```java block."""
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        if lines[i].strip() == JAVA_OPEN:
            j = i - 1
            while j >= 0 and lines[j].strip() == "":
                j -= 1
            marker = lines[j] if j >= 0 else ""
            k = i + 1
            while k < len(lines) and lines[k].strip() != FENCE_END:
                k += 1
            code = lines[i + 1:k]
            post = lines[k + 1:k + 6]
            yield (i + 1, marker, code, post)
            i = k + 1
        else:
            i += 1


def classify(marker: str):
    """Return (kind, data). kind ∈ snippet|illustrative|concept|trimmed|idiomatic|unmarked."""
    m = SNIPPET_RE.search(marker)
    if m:
        return ("snippet", (m.group(1), m.group(2)))
    m = ILLUS_RE.search(marker)
    if m:
        qualifier = (m.group(1) or "").strip().lower()
        if not qualifier:
            return ("illustrative", None)
        if qualifier.startswith("concept fragment"):
            return ("concept", None)
        if qualifier.startswith("trimmed"):
            return ("trimmed", None)
        if qualifier.startswith("idiomatic"):
            return ("idiomatic", None)
        # Unknown qualifier — treat as opt-out but flag in --verbose
        return ("illustrative", qualifier)
    return ("unmarked", None)


def extract_snippet(path: str, name: str) -> list[str]:
    """Extract the lines between SNIPPET_START / SNIPPET_END, stripping common indent."""
    full = REPO_ROOT / path
    if not full.is_file():
        raise ValueError(f"snippet path not found: {path}")
    src = full.read_text().splitlines()
    start_re = re.compile(rf"//\s*SNIPPET_START\s+{re.escape(name)}\s*$")
    end_re   = re.compile(rf"//\s*SNIPPET_END\s+{re.escape(name)}\s*$")
    starts = [i for i, l in enumerate(src) if start_re.search(l)]
    ends   = [i for i, l in enumerate(src) if end_re.search(l)]
    if len(starts) != 1 or len(ends) != 1:
        raise ValueError(
            f"in {path}: expected exactly one SNIPPET_START {name} and one "
            f"SNIPPET_END {name}; found {len(starts)} START and {len(ends)} END")
    s, e = starts[0], ends[0]
    if e <= s:
        raise ValueError(
            f"in {path}: SNIPPET_END {name} appears before SNIPPET_START {name}")
    region = src[s + 1:e]
    nonblank = [l for l in region if l.strip()]
    if not nonblank:
        raise ValueError(f"in {path}: SNIPPET {name} is empty")
    has_tab   = any(l[:1] == "\t" for l in nonblank)
    has_space = any(l[:1] == " "  for l in nonblank)
    if has_tab and has_space:
        raise ValueError(
            f"in {path}: SNIPPET {name} has mixed tab and space leading whitespace; "
            f"normalise to spaces in the example file")

    def leading_ws(s: str) -> int:
        return len(s) - len(s.lstrip(" \t"))

    common = min(leading_ws(l) for l in nonblank)
    return [l[common:] if len(l) >= common else l for l in region]


def normalize(lines: list[str]) -> list[str]:
    return [l.rstrip() for l in lines]


def main(argv: list[str]) -> int:
    verbose = "--verbose" in argv
    list_blocks = "--list-blocks" in argv

    files: list[Path] = []
    for pattern in DOCS_GLOBS:
        files += sorted(REPO_ROOT.glob(pattern))

    counts = {"snippet": 0, "illustrative": 0, "concept": 0,
              "trimmed": 0, "idiomatic": 0, "unmarked": 0}
    failures: list[str] = []
    skipped_files: list[tuple[Path, str]] = []   # (path, reason)

    for path in files:
        text = path.read_text()
        rel = path.relative_to(REPO_ROOT)
        skip_reason = file_skip_reason(text)
        if skip_reason is not None:
            skipped_files.append((rel, skip_reason))
            if list_blocks:
                print(f"{rel}  <SKIP-FILE>  {skip_reason!r}")
            continue
        for line_no, marker, code, post in find_blocks(text):
            kind, data = classify(marker)
            counts[kind] = counts.get(kind, 0) + 1

            if list_blocks:
                print(f"{rel}:{line_no}  {kind:<13}  {data!r}")
                continue

            if kind == "unmarked":
                failures.append(
                    f"{rel}:{line_no} — unmarked Java code block. Add one of:\n"
                    f"  <!-- snippet: <path>#<name> -->                  (verbatim from example file's SNIPPET_START/END region)\n"
                    f"  <!-- illustrative -->                            (linkable to an example; requires *Real usage:* footer)\n"
                    f"  <!-- illustrative: concept fragment -->          (abstract; no example correspondence)\n"
                    f"  <!-- illustrative: trimmed adaptation … -->      (adapted-from-example with explicit caveat)\n"
                    f"  <!-- illustrative: idiomatic … -->               (idiomatic pattern)")
                continue

            if kind == "illustrative" and data is None:
                if not any(FOOTER_RE.search(p) for p in post):
                    failures.append(
                        f"{rel}:{line_no} — <!-- illustrative --> block missing required "
                        f"`*Real usage: [...](...)*` footer within 5 lines after the closing fence")
                continue

            if kind != "snippet":
                continue

            snippet_path, snippet_name = data
            try:
                expected = extract_snippet(snippet_path, snippet_name)
            except ValueError as exc:
                failures.append(f"{rel}:{line_no} — snippet error: {exc}")
                continue

            exp_n = normalize(expected)
            act_n = normalize(code)
            if exp_n != act_n:
                diff = "\n".join(difflib.unified_diff(
                    exp_n, act_n,
                    fromfile=f"{snippet_path}#{snippet_name} (SNIPPET region)",
                    tofile=f"{rel}:{line_no} (doc code block)",
                    lineterm=""))
                failures.append(
                    f"{rel}:{line_no} — snippet '{snippet_path}#{snippet_name}' "
                    f"differs from its SNIPPET_START/END region:\n{diff}")
            elif verbose:
                print(f"{rel}:{line_no}  ✓ verbatim {snippet_path}#{snippet_name}")

    if list_blocks:
        return 0

    summary = (
        f"doc-snippets: {counts['snippet']} verified, "
        f"{counts['illustrative']} illustrative, "
        f"{counts['concept']} concept-fragment, "
        f"{counts['trimmed']} trimmed-adaptation, "
        f"{counts['idiomatic']} idiomatic, "
        f"{counts['unmarked']} unmarked, "
        f"{len(failures)} failures, "
        f"{len(skipped_files)} file{'s' if len(skipped_files) != 1 else ''} skipped (file-level)"
    )

    if skipped_files and (verbose or failures):
        # Verbose / failure mode: list which files were skipped + reasons
        for p, reason in skipped_files:
            stream = sys.stderr if failures else sys.stdout
            print(f"  skip-file: {p}  ({reason or 'no reason given'})", file=stream)

    if failures:
        for f in failures:
            print(f, file=sys.stderr)
            print("---", file=sys.stderr)
        print(summary, file=sys.stderr)
        return 1

    print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
