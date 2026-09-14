#!/usr/bin/env python3
"""Structural checks for apktool/JADX smali produced from a patched APK.

This is intentionally independent of the patcher. It catches malformed boolean guards and
runtime guard bodies that overwrite parameter registers before an APK reaches a device.
Usage: verify_ads_runtime_smali.py <decompiled-directory> [options]

Options:
  --expected-policy <payload>       Require the exact serialized policy string.
  --baseline <decompiled-directory> Compare smali files against an original APK.
  --allow-path <regular-expression> Allow a changed relative smali path; repeatable.
  --forbid-runtime-rewards          Reject reward-policy calls in the target APK.
  --require-runtime-method <regex>  Require matching methods to contain a guarded original body.
  --forbid-runtime-method <regex>   Require matching methods to contain no AdsRuntimePolicy call.
  --expected-module-mask <integer>  Require the module-mask field in the policy to match.
  --require-max-runtime-rewards     Require guarded MAX Unity and native rewarded show methods.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


METHOD_RE = re.compile(r"^\.method\b.*?(\([^)]*\)(?:[VZIBSCLFJD]|\[[^ ]+))\s*$")
PARAM_WRITE_RE = re.compile(
    r"\b(?:const(?:/[0-9]+)?|move(?:-object|-wide)?(?:/(?:from16|16))?|move-result(?:-object|-wide)?|new-instance|iget(?:-object|-boolean|-byte|-char|-short|-wide)?|sget(?:-object|-boolean|-byte|-char|-short|-wide)?)\s+p\d+\b"
)
LABEL_RE = re.compile(r"^\s*:([A-Za-z0-9_.$-]+)")
REWARD_POLICY_RE = re.compile(
    r"AdsRuntimePolicy;->should(?:SkipRewarded|GrantReward|FakeRewardAvailability)\(\)Z"
)


def check_file(path: Path) -> list[str]:
    errors: list[str] = []
    lines = path.read_text(errors="replace").splitlines()
    index = 0
    while index < len(lines):
        match = METHOD_RE.match(lines[index])
        if not match:
            index += 1
            continue
        signature = match.group(1)
        start = index
        index += 1
        while index < len(lines) and lines[index].strip() != ".end method":
            index += 1
        body = lines[start:index]
        labels: list[str] = []
        in_switch_table = False
        for line in body:
            stripped = line.strip()
            if stripped.startswith(".packed-switch") or stripped.startswith(".sparse-switch"):
                in_switch_table = True
                continue
            if stripped in (".end packed-switch", ".end sparse-switch"):
                in_switch_table = False
                continue
            if not in_switch_table and (label := LABEL_RE.match(line)):
                labels.append(label.group(1))
        duplicate_labels = sorted({label for label in labels if labels.count(label) > 1})
        for label in duplicate_labels:
            errors.append(f"{path}:{start + 1}: duplicate label :{label}")
        if signature.endswith(")Z") and any("return-void" in line for line in body):
            errors.append(f"{path}:{start + 1}: boolean method contains return-void")
        if "/unipatch/overlaycore/" not in path.as_posix():
            policy_indexes = [i for i, line in enumerate(body) if "AdsRuntimePolicy;->" in line]
            for policy_index in policy_indexes:
                # A guard can contain internal labels (for example a skip/block branch).
                # Inspect through the generated *_original label so writes in every injected
                # branch are checked, while the original SDK body remains out of scope.
                guarded_region = []
                original_label_found = False
                for line in body[policy_index:]:
                    guarded_region.append(line)
                    label = LABEL_RE.match(line)
                    if label and label.group(1).endswith("original"):
                        original_label_found = True
                        break
                if not original_label_found:
                    errors.append(f"{path}:{start + policy_index + 1}: runtime guard has no original-body label")
                for offset, line in enumerate(guarded_region, start + policy_index + 1):
                    if PARAM_WRITE_RE.search(line):
                        errors.append(f"{path}:{offset}: runtime guard writes a parameter register")
                if signature.endswith(")Z"):
                    if any("return-void" in line for line in guarded_region):
                        errors.append(f"{path}:{start + policy_index + 1}: boolean runtime guard contains return-void")
                    if not any(re.search(r"\breturn(?:/\w+)?\s+v\d+\b", line) for line in guarded_region):
                        errors.append(f"{path}:{start + policy_index + 1}: boolean runtime guard has no register return")
        index += 1
    return errors


def parse_options(args: list[str]) -> tuple[Path, str | None, Path | None, list[re.Pattern[str]], bool, list[re.Pattern[str]], list[re.Pattern[str]], int | None, bool] | None:
    if not args:
        return None
    root = Path(args[0])
    expected_policy: str | None = None
    baseline: Path | None = None
    allowed_paths: list[re.Pattern[str]] = []
    forbid_runtime_rewards = False
    required_runtime_methods: list[re.Pattern[str]] = []
    forbidden_runtime_methods: list[re.Pattern[str]] = []
    expected_module_mask: int | None = None
    require_max_runtime_rewards = False
    index = 1
    while index < len(args):
        option = args[index]
        if option in ("--expected-policy", "--baseline", "--allow-path", "--require-runtime-method", "--forbid-runtime-method", "--expected-module-mask"):
            if index + 1 >= len(args):
                return None
            value = args[index + 1]
            if option == "--expected-policy":
                expected_policy = value
            elif option == "--baseline":
                baseline = Path(value)
            elif option == "--allow-path":
                try:
                    allowed_paths.append(re.compile(value))
                except re.error:
                    return None
            else:
                if option == "--expected-module-mask":
                    try:
                        expected_module_mask = int(value)
                    except ValueError:
                        return None
                else:
                    try:
                        target = re.compile(value)
                    except re.error:
                        return None
                    (required_runtime_methods if option == "--require-runtime-method" else forbidden_runtime_methods).append(target)
            index += 2
        elif option == "--forbid-runtime-rewards":
            forbid_runtime_rewards = True
            index += 1
        elif option == "--require-max-runtime-rewards":
            require_max_runtime_rewards = True
            index += 1
        else:
            return None
    return root, expected_policy, baseline, allowed_paths, forbid_runtime_rewards, required_runtime_methods, forbidden_runtime_methods, expected_module_mask, require_max_runtime_rewards


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0
    options = parse_options(sys.argv[1:])
    if options is None:
        print(f"usage: {Path(sys.argv[0]).name} <decompiled-directory> [options]", file=sys.stderr)
        return 2
    root, expected_policy, baseline, allowed_paths, forbid_runtime_rewards, required_runtime_methods, forbidden_runtime_methods, expected_module_mask, require_max_runtime_rewards = options
    if not root.is_dir():
        print(f"not a decompiled directory: {root}", file=sys.stderr)
        return 2
    errors = [error for path in root.rglob("*.smali") for error in check_file(path)]
    methods: list[tuple[Path, str, str]] = []
    for path in root.rglob("*.smali"):
        lines = path.read_text(errors="replace").splitlines()
        index = 0
        while index < len(lines):
            match = METHOD_RE.match(lines[index])
            if not match:
                index += 1
                continue
            start = index
            index += 1
            while index < len(lines) and lines[index].strip() != ".end method":
                index += 1
            methods.append((path, lines[start], "\n".join(lines[start:index])))
            index += 1
    for pattern in required_runtime_methods:
        matches = [(path, header, body) for path, header, body in methods if pattern.search(f"{path.relative_to(root)} {header}")]
        if not matches:
            errors.append(f"no method matched --require-runtime-method {pattern.pattern}")
        for path, header, body in matches:
            if "AdsRuntimePolicy;->" not in body or not re.search(r"^\s*:[A-Za-z0-9_.$-]*original\s*$", body, re.MULTILINE):
                errors.append(f"{path}: method lacks a guarded original body: {header}")
    for pattern in forbidden_runtime_methods:
        for path, header, body in methods:
            if pattern.search(f"{path.relative_to(root)} {header}") and "AdsRuntimePolicy;->" in body:
                errors.append(f"{path}: forbidden runtime instrumentation: {header}")
    if require_max_runtime_rewards:
        max_targets = {
            "Unity bridge": re.compile(
                r"com/applovin/mediation/unity/MaxUnityAdManager\.smali .*\.method public showRewardedAd\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;\)V"
            ),
            "native": re.compile(
                r"com/applovin/mediation/ads/MaxRewardedAd\.smali .*\.method public showAd\(Ljava/lang/String;Ljava/lang/String;Landroid/app/Activity;\)V"
            ),
        }
        for name, pattern in max_targets.items():
            matches = [(path, header, body) for path, header, body in methods
                       if pattern.search(f"{path.relative_to(root)} {header}")]
            if not matches:
                errors.append(f"no eligible MAX {name} rewarded show method found")
            for path, header, body in matches:
                if "AdsRuntimePolicy;->shouldSkipRewarded" not in body:
                    errors.append(f"{path}: MAX rewarded show method lacks skip guard: {header}")
                if not re.search(r"^\s*:[A-Za-z0-9_.$-]*original\s*$", body, re.MULTILINE):
                    errors.append(f"{path}: MAX rewarded show method lacks original fallback: {header}")
    if expected_policy is not None:
        found_policy = any(
            expected_policy in path.read_text(errors="replace")
            for path in root.rglob("*.smali")
        )
        if not found_policy:
            errors.append("expected Ads Runtime Policy payload was not found in decompiled smali")
    if expected_module_mask is not None:
        policy_masks: list[str] = []
        for path in root.rglob("*.smali"):
            for match in re.finditer(r"(?:const-string\s+v\d+,\s+\")([^\"]+)(?:\"|$)", path.read_text(errors="replace")):
                fields = match.group(1).split("|")
                if len(fields) >= 3 and fields[0] == "1" and fields[1].isdigit():
                    policy_masks.append(fields[1])
        if str(expected_module_mask) not in policy_masks:
            errors.append(f"expected Ads Runtime Policy module mask was not found: {expected_module_mask}")
    if forbid_runtime_rewards:
        for path in root.rglob("*.smali"):
            if REWARD_POLICY_RE.search(path.read_text(errors="replace")):
                errors.append(f"runtime Rewards policy call found in {path.relative_to(root)}")
    if baseline is not None:
        if not baseline.is_dir():
            print(f"not a baseline decompiled directory: {baseline}", file=sys.stderr)
            return 2
        current_files = {path.relative_to(root) for path in root.rglob("*.smali")}
        baseline_files = {path.relative_to(baseline) for path in baseline.rglob("*.smali")}
        for relative in sorted(current_files | baseline_files):
            current = root / relative
            original = baseline / relative
            if not current.exists() or not original.exists() or current.read_bytes() != original.read_bytes():
                if not allowed_paths or not any(pattern.search(relative.as_posix()) for pattern in allowed_paths):
                    errors.append(f"unexpected smali modification: {relative}")
    if errors:
        print("\n".join(errors))
        return 1
    suffix = ", expected policy found" if expected_policy is not None else ""
    if baseline is not None:
        suffix += ", baseline changes allowed"
    print(f"Ads runtime smali verification passed: {root}{suffix}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
