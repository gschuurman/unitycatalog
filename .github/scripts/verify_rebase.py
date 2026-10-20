#!/usr/bin/env python3
"""Verify that every line the fork commits add is still present, verbatim, in
the working tree after the daily rebase re-applies them onto a new upstream
Unity Catalog tip.

`git am` (even without --3way) can apply a patch "successfully" while a hunk
lands via fuzzy context matching, or effectively no-ops because upstream's
own version of a file changed in the same region. Neither case is reported
as a conflict, so a real regression (a dropped endpoint override, a lost
policy filter, ...) can silently ship. This script re-checks
the applied result against the original diff's added lines and fails loudly
instead.

Usage: verify_rebase.py <diff-file>
Exits 1 and prints missing (file, line) pairs if anything the diff added is
no longer found in the corresponding file on disk.
"""
import os
import re
import sys

def parse_added_lines(diff_path):
    """Yield (file_path, added_line) for every '+' line in a unified diff,
    skipping diff/hunk metadata, binary sections, and blank lines."""
    current_file = None
    binary = False
    with open(diff_path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.startswith("diff --git "):
                current_file = None
                binary = False
                continue
            if line.startswith("Binary files "):
                binary = True
                continue
            m = re.match(r"^\+\+\+ b/(.+)$", line)
            if m:
                current_file = m.group(1)
                continue
            if line.startswith("+++ /dev/null"):
                current_file = None
                continue
            if binary or current_file is None:
                continue
            if line.startswith("+++") or line.startswith("---"):
                continue
            if line.startswith("+") and not line.startswith("+++"):
                content = line[1:]
                if content.strip():
                    yield current_file, content

def main():
    if len(sys.argv) != 2:
        print("usage: verify_rebase.py <diff-file>", file=sys.stderr)
        return 2

    missing = []
    file_cache = {}
    for path, added_line in parse_added_lines(sys.argv[1]):
        if path not in file_cache:
            try:
                if os.path.islink(path):
                    # A diff line for a symlink is the link target itself
                    # (git stores it as the blob content); reading the file
                    # normally follows the link and checks the *target's*
                    # content instead, which can never match and would
                    # false-positive on every future rebase.
                    file_cache[path] = os.readlink(path)
                else:
                    with open(path, encoding="utf-8", errors="replace") as fh:
                        file_cache[path] = fh.read()
            except FileNotFoundError:
                file_cache[path] = None
        content = file_cache[path]
        if content is None or added_line.strip() not in content:
            missing.append((path, added_line))

    if missing:
        print(f"REBASE CONTENT-LOSS GUARD: {len(missing)} line(s) added by "
              f"the fork commits are missing after re-applying them:\n")
        by_file = {}
        for path, line in missing:
            by_file.setdefault(path, []).append(line)
        for path, lines in by_file.items():
            print(f"  {path}:")
            for line in lines:
                print(f"    - {line}")
        return 1

    print("Rebase content-loss guard: all fork lines present.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
