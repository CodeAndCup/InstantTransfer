#!/usr/bin/env bash
# Checks every hunk header's declared line counts against actual content lines.
set -euo pipefail
f="${1:-velocity-patch/seamless.patch}"
awk '
function fail(where) {
  printf "MISMATCH %s: declared -%d/+%d actual -%d/+%d\n", where, do_, dn, oc, nc; bad=1
}
/^@@ -/ {
  if (inhunk && (oc != do_ || nc != dn)) fail("before line " NR)
  inhunk = 1; oc = 0; nc = 0
  if (match($0, /-[0-9]+(,[0-9]+)?/)) {
    s = substr($0, RSTART + 1, RLENGTH - 1)
    n = split(s, p, ","); do_ = (n == 2 ? p[2] + 0 : 1)
  }
  if (match($0, /\+[0-9]+(,[0-9]+)?/)) {
    s = substr($0, RSTART + 1, RLENGTH - 1)
    n = split(s, p, ","); dn = (n == 2 ? p[2] + 0 : 1)
  }
  next
}
/^diff --git / {
  if (inhunk && (oc != do_ || nc != dn)) fail("at file boundary line " NR)
  inhunk = 0
  next
}
inhunk {
  c = substr($0, 1, 1)
  if (c == "\\") next
  else if (c == "-") oc++
  else if (c == "+") nc++
  else { oc++; nc++ }   # context or empty-line context
}
END {
  if (inhunk && (oc != do_ || nc != dn)) fail("at EOF")
  if (!bad) print "checked: all hunks consistent"
  exit bad
}
' "$f"
