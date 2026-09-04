# Contributing to UniPatches

## Universal Overlay contributions

For Universal Overlay changes, read [UNIVERSAL_OVERLAY_MODULES.md](UNIVERSAL_OVERLAY_MODULES.md)
before adding or changing a module. It documents runtime boundaries, configuration wiring,
lifecycle rules, and failure isolation. For the complete architecture and file map, read
[UNIVERSAL_OVERLAY.md](UNIVERSAL_OVERLAY.md).

Keep each contribution small and reviewable: one logical change per pull request and one logical
change per commit. Use a feature branch and a semantic commit such as `feat: add ...` or
`fix: handle ...`.

When adding a module:

- Put it in the matching `activity/`, `statistic/`, or `hook/` directory.
- Extend the correct category base class and provide a stable, unique key and user-facing metadata.
- Add a disabled-by-default option, serialize its token, decode it, and register the module.
- Isolate failures and restore mutable Activity state independently.
- Do not add package-, class-, engine-, or APK-specific assumptions.

Before submitting, run `git diff --check` and verify the patch build. Do not manually edit generated
files such as `patches-list.json`, `patches-bundle.json`, or `CHANGELOG.md`.
