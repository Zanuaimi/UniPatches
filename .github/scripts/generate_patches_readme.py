#!/usr/bin/env python3
"""
Generates the patches section of README.md from patches-list.json
and injects it between <!-- PATCHES_START --> / <!-- PATCHES_END --> markers.

Spoilers are expanded (open by default) if:
  1. Total patch count <= AUTO_EXPAND_THRESHOLD.
  2. The README marker explicitly says: <!-- PATCHES_START EXPANDED -->

python3 generate_patches_readme.py <owner/repo> <branch> [patches-list.json] [README.md]
"""

import json
import re
import sys
from pathlib import Path


# The whole script emits emoji. On Windows the process starts with the ANSI code
# page (cp1252), so the first print() raises UnicodeEncodeError and exits 1
# *after* README.md has already been rewritten — which leaves a half-applied
# release step. Force UTF-8 for both streams before anything is written.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        # python < 3.7 or a stream that does not support reconfigure()
        pass

USAGE = "Usage: generate_patches_readme.py <owner/repo> <branch> [patches-list.json] [README.md]"


def fail(message: str, code: int = 1) -> None:
    """Report an error without a traceback, then exit with a non-zero status."""
    print(f"error: {message}", file=sys.stderr)
    sys.exit(code)


if len(sys.argv) < 3:
    print(USAGE, file=sys.stderr)
    sys.exit(1)

repo_full   = sys.argv[1]
branch      = sys.argv[2]
json_path   = Path(sys.argv[3]) if len(sys.argv) > 3 else Path("patches-list.json")
readme_path = Path(sys.argv[4]) if len(sys.argv) > 4 else Path("README.md")


if repo_full.count("/") != 1 or not all(repo_full.split("/")):
    fail(f"invalid repo format: {repo_full!r} (expected owner/repo)")

if not json_path.is_file():
    fail(f"patches list not found: {json_path}")

if not readme_path.is_file():
    fail(f"README not found: {readme_path}")

owner, repo = repo_full.split("/", 1)


try:
    with open(json_path, encoding="utf-8") as f:
        data = json.load(f)
except json.JSONDecodeError as exc:
    fail(f"{json_path} is not valid JSON: {exc}")

if "patches" not in data or not isinstance(data["patches"], list):
    fail(f'{json_path} is missing the top-level "patches" array')

if "version" not in data:
    fail(f'{json_path} is missing the top-level "version" field')


def pkg_emoji(pkg):
    """Return a standard package emoji regardless of the package name."""
    return "📦"

# Group patches by package; patches with no compatiblePackages are universal.
# JSON structure: compatiblePackages is a list of objects with
# { packageName, name, targets: [{ version, isExperimental, description }] }
by_pkg = {}   # packageName -> { name, emoji, patches, targets }
universal = {}

for patch in data["patches"]:
    cp = patch.get("compatiblePackages")
    if not cp:
        # Deduplicate universal patches by name
        if patch["name"] not in universal:
            universal[patch["name"]] = patch
        continue
    for pkg_entry in cp:
        pkg  = pkg_entry["packageName"]
        name = pkg_entry.get("name") or pkg  # fall back to package name if no label
        if pkg not in by_pkg:
            by_pkg[pkg] = {
                "name":    name,
                "emoji":   pkg_emoji(pkg),
                "patches": {},
                "targets": pkg_entry.get("targets", []),
            }
        # Deduplicate patches that appear across multiple packages
        if patch["name"] not in by_pkg[pkg]["patches"]:
            by_pkg[pkg]["patches"][patch["name"]] = patch


def anchor(name):
    """Convert a patch name to a GitHub-compatible anchor slug."""
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", name.lower())).strip("-")


def patches_table(patches):
    """Render a sorted markdown table of patches with name, description, and options."""
    rows = [
        "| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |",
        "|----------|----------------|-----------|",
    ]
    for p in sorted(patches, key=lambda x: x["name"]):
        a = anchor(p["name"])
        options = p.get("options") or []
        if options:
            # Show only option titles as a bullet list
            parts = [opt.get("title") or opt.get("key") or "" for opt in options]
            opts_cell = "<br>".join(f"• {t}" for t in parts)
        else:
            opts_cell = ""
        desc = (p.get("description") or "").replace("\n", "<br>")
        rows.append(f"| [{p['name']}](#{a}) | {desc} | {opts_cell} |")
    return "\n".join(rows)


def versions_table(targets):
    """Render a markdown table of supported versions.
    Experimental versions get a 🧪 prefix.
    Versions with a description get it shown in a second row below.

    Cells and descriptions are built from the *same* filtered target list so the
    description row can never contain more columns than the header row (a target
    without a version string used to produce a misaligned table).
    """
    rendered = [
        t for t in (targets or [])
        if t.get("version") is not None
    ]

    if not rendered:
        return ""

    cells = [
        f"🧪&nbsp;{t['version']}" if t.get("isExperimental") else t["version"]
        for t in rendered
    ]

    header = "| " + " | ".join(cells) + " |"
    sep = "| " + " | ".join(":---:" for _ in cells) + " |"
    rows = [header, sep]

    # Optional description row — only rendered if at least one target has one
    descs = [(t.get("description") or "").replace("\n", "<br>") for t in rendered]
    if any(descs):
        rows.append("| " + " | ".join(descs) + " |")

    return "\n".join(rows)


def spoiler(label, count, targets, tbl, expanded=False):
    """Wrap a patches table in a <details> spoiler with a versions sub-table.
    If expanded=True, the spoiler is open by default (for repos with few patches).
    """
    noun = "patch" if count == 1 else "patches"
    vtbl = versions_table(targets)
    versions_section = f"**🎯 Supported versions:**\n\n{vtbl}\n\n" if vtbl else ""
    tag = "<details open>" if expanded else "<details>"
    return f"""{tag}
<summary>{label}&nbsp;&nbsp;•&nbsp;&nbsp;{count} {noun}</summary>
<br>

{versions_section}{tbl}

</details>"""


def build_content(expanded=False):
    """Build the full generated patches section."""
    lines = [
        f"> **[v{ver}](https://github.com/{owner}/{repo}/releases/tag/v{ver})**"
        f"&nbsp;&nbsp;•&nbsp;&nbsp;`{branch}`&nbsp;&nbsp;•&nbsp;&nbsp;"
        f"{total} patches total"
    ]

    # One spoiler per app, in the order they appear in the JSON
    for pkg, entry in by_pkg.items():
        patches = list(entry["patches"].values())
        label   = f"{entry['emoji']} {entry['name']}"
        lines.append(spoiler(label, len(patches), entry["targets"], patches_table(patches), expanded))
        lines.append("")

    # Universal patches (no specific app)
    if universal:
        uni_patches = list(universal.values())
        noun = "patch" if len(uni_patches) == 1 else "patches"
        tag  = "<details open>" if expanded else "<details>"
        lines.append(f"""{tag}
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;{len(uni_patches)} {noun}</summary>
<br>

{patches_table(uni_patches)}

</details>""")
        lines.append("")

    return "\n".join(lines)


# Build and inject
raw_ver = str(data["version"])
# Strip exactly one leading "v" if present. lstrip("v") was removed because it
# strips *characters*, so "vv1.2" would silently become "1.2".
ver   = raw_ver[1:] if raw_ver.startswith("v") else raw_ver
total = sum(len(e["patches"]) for e in by_pkg.values()) + len(universal)

readme = readme_path.read_text(encoding="utf-8")

# Marker pattern — matches both <!-- PATCHES_START --> and <!-- PATCHES_START EXPANDED -->
START_PATTERN = r"<!-- PATCHES_START(?:\s+EXPANDED)?\s*-->"
END_MARKER    = "<!-- PATCHES_END -->"

marker_match = re.search(START_PATTERN, readme)

if not marker_match or END_MARKER not in readme:
    # Fallback: print to stdout so CI can catch the issue
    print(build_content(expanded=False))
    sys.stderr.write(
        f"error: markers <!-- PATCHES_START [EXPANDED] --> / {END_MARKER} "
        f"not found in {readme_path}; generated section printed to stdout instead.\n"
    )
    sys.exit(1)

actual_start = marker_match.group(0)

if readme.index(END_MARKER) < marker_match.start():
    fail(f"{END_MARKER} appears before the start marker; refusing to write {readme_path}")

# Auto-expand threshold
AUTO_EXPAND_THRESHOLD = 20

# Spoilers are expanded if:
# 1. Total patch count is small (≤ AUTO_EXPAND_THRESHOLD)
#    with only a few patches where collapsing adds no benefit.
# 2. The README marker explicitly requests it: <!-- PATCHES_START EXPANDED -->
expanded = (
    total <= AUTO_EXPAND_THRESHOLD or
    "EXPANDED" in actual_start
)

generated  = build_content(expanded=expanded)

# Replace template links if present
readme = readme.replace("https://morphe.software/add-source?github=xyz-user/xyz-patches", f"https://morphe.software/add-source?github={repo_full}")
readme = readme.replace("https://github.com/xyz-user/xyz-patches", f"https://github.com/{repo_full}")

# A callable replacement is used so that backslashes and \1-style sequences that
# appear in patch descriptions or option titles are inserted verbatim. Passing a
# plain string makes re.sub() interpret them as group references.
replacement = f"{actual_start}\n{generated}\n{END_MARKER}"
new_readme, substitutions = re.subn(
    rf"{START_PATTERN}.*?{re.escape(END_MARKER)}",
    lambda _match: replacement,
    readme,
    flags=re.DOTALL,
)

if substitutions != 1:
    fail(f"expected exactly one patches block in {readme_path}, found {substitutions}")

readme_path.write_text(new_readme, encoding="utf-8")
print(f"✅ Injected patches section into {readme_path} (v{ver}, branch={branch}, {total} patches, expanded={expanded})")
