#!/usr/bin/env python3
"""Regression tests for generate_patches_readme.py.

Run with:
    python3 -m unittest discover -s .github/scripts -p "test_*.py"

The generator runs inside the semantic-release `prepareCmd`, so a non-zero exit
stops the release *after* CHANGELOG/gradle.properties have already been bumped.
Every failure mode below is therefore asserted on the exit code as well as on
the resulting file contents.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "generate_patches_readme.py"

START_MARKER = "<!-- PATCHES_START -->"
END_MARKER = "<!-- PATCHES_END -->"

README_TEMPLATE = """# Demo

## Patches

{start}
placeholder
{end}

tail
"""


def make_patch(name, description="A patch.", targets=None, options=None):
    patch = {
        "name": name,
        "category": None,
        "description": description,
        "default": True,
        "dependencies": [],
        "compatiblePackages": None,
        "options": options or [],
    }
    if targets is not None:
        patch["compatiblePackages"] = [{
            "packageName": "com.example.app",
            "name": "Example",
            "targets": targets,
        }]
    return patch


class GeneratorTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.readme = self.tmp / "README.md"
        self.patch_list = self.tmp / "patches-list.json"

    def write_readme(self, start=START_MARKER):
        self.readme.write_text(
            README_TEMPLATE.format(start=start, end=END_MARKER),
            encoding="utf-8",
        )

    def write_patch_list(self, patches, version="1.2.3"):
        self.patch_list.write_text(
            json.dumps({"version": version, "patches": patches}, indent=2),
            encoding="utf-8",
        )

    def run_generator(self, *args, extra_env=None):
        env = dict(os.environ)
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            capture_output=True,
            encoding="utf-8",
            errors="replace",
            env=env,
            cwd=str(self.tmp),
        )

    def default_run(self):
        return self.run_generator(
            "owner/repo", "dev", str(self.patch_list), str(self.readme)
        )


class TestHappyPath(GeneratorTestCase):
    def test_injects_patches_between_markers(self):
        self.write_readme()
        self.write_patch_list([make_patch("No Ads")])

        result = self.default_run()

        self.assertEqual(result.returncode, 0, result.stderr)
        content = self.readme.read_text(encoding="utf-8")
        self.assertIn(START_MARKER, content)
        self.assertIn(END_MARKER, content)
        self.assertIn("[No Ads](#no-ads)", content)
        self.assertNotIn("placeholder", content)
        # Content outside the markers must survive untouched.
        self.assertTrue(content.startswith("# Demo"))
        self.assertTrue(content.rstrip().endswith("tail"))

    def test_is_idempotent(self):
        self.write_readme()
        self.write_patch_list([make_patch("No Ads"), make_patch("Bypass Checks")])

        first = self.default_run()
        self.assertEqual(first.returncode, 0, first.stderr)
        once = self.readme.read_text(encoding="utf-8")

        second = self.default_run()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(self.readme.read_text(encoding="utf-8"), once)

    def test_expanded_marker_is_preserved(self):
        self.write_readme(start="<!-- PATCHES_START EXPANDED -->")
        self.write_patch_list([make_patch("No Ads")])

        result = self.default_run()

        self.assertEqual(result.returncode, 0, result.stderr)
        content = self.readme.read_text(encoding="utf-8")
        self.assertIn("<!-- PATCHES_START EXPANDED -->", content)
        self.assertIn("<details open>", content)

    def test_only_one_leading_v_is_stripped_from_the_version(self):
        self.write_readme()
        self.write_patch_list([make_patch("No Ads")], version="vv1.2")

        result = self.default_run()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("](https://github.com/owner/repo/releases/tag/vv1.2)",
                      self.readme.read_text(encoding="utf-8"))


class TestOutputEncoding(GeneratorTestCase):
    def test_exits_zero_when_stdout_forced_to_ascii(self):
        """The generator prints emoji; without a UTF-8 stream it dies on Windows.

        PYTHONIOENCODING=ascii reproduces the cp1252-style failure the script
        used to hit after it had already rewritten README.md.
        """
        self.write_readme()
        self.write_patch_list([make_patch("No Ads")])

        result = self.run_generator(
            "owner/repo", "dev", str(self.patch_list), str(self.readme),
            extra_env={"PYTHONIOENCODING": "ascii"},
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("UnicodeEncodeError", result.stderr)
        self.assertIn("✅", result.stdout)


class TestBackslashSafety(GeneratorTestCase):
    def test_backslashes_in_descriptions_are_written_verbatim(self):
        # re.sub() treats "\\" followed by a digit as a group reference, so a
        # plain string replacement used to raise "bad escape \1".
        self.write_readme()
        self.write_patch_list([
            make_patch(
                "Path Patch",
                description=r"Rewrites C:\temp\1\lib and \g<0> tokens.",
            )
        ])

        result = self.default_run()

        self.assertEqual(result.returncode, 0, result.stderr)
        content = self.readme.read_text(encoding="utf-8")
        self.assertIn(r"C:\temp\1\lib", content)
        self.assertIn(r"\g<0>", content)


class TestVersionsTable(GeneratorTestCase):
    def test_versionless_target_does_not_misalign_description_row(self):
        self.write_readme()
        self.write_patch_list([
            make_patch(
                "Mixed Targets",
                targets=[
                    {"version": None, "isExperimental": False,
                     "description": "no pinned version"},
                    {"version": "1.0", "isExperimental": False,
                     "description": "pinned"},
                ],
            )
        ])

        result = self.default_run()

        self.assertEqual(result.returncode, 0, result.stderr)
        lines = self.readme.read_text(encoding="utf-8").splitlines()
        header_at = next(
            i for i, line in enumerate(lines) if "Supported versions" in line
        )
        versions_rows = []
        for line in lines[header_at + 1:]:
            if not line.startswith("|"):
                if versions_rows:
                    break
                continue
            versions_rows.append(line)

        self.assertEqual(len(versions_rows), 3, versions_rows)
        widths = [line.count("|") for line in versions_rows]
        self.assertEqual(len(set(widths)), 1, f"ragged table: {versions_rows}")

    def test_all_versionless_targets_omit_the_table(self):
        self.write_readme()
        self.write_patch_list([
            make_patch(
                "Unversioned",
                targets=[{"version": None, "isExperimental": False,
                          "description": "always"}],
            )
        ])

        result = self.default_run()

        self.assertEqual(result.returncode, 0, result.stderr)
        content = self.readme.read_text(encoding="utf-8")
        self.assertNotIn("Supported versions", content)

    def test_experimental_versions_are_marked(self):
        self.write_readme()
        self.write_patch_list([
            make_patch(
                "Experimental Target",
                targets=[{"version": "2.0", "isExperimental": True,
                          "description": None}],
            )
        ])

        result = self.default_run()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("🧪", self.readme.read_text(encoding="utf-8"))


class TestFailureModes(GeneratorTestCase):
    def test_missing_readme_markers_exits_nonzero_without_touching_file(self):
        self.readme.write_text("# Demo\n\nno markers here\n", encoding="utf-8")
        self.write_patch_list([make_patch("No Ads")])

        result = self.default_run()

        self.assertEqual(result.returncode, 1)
        self.assertIn("not found", result.stderr)
        self.assertEqual(
            self.readme.read_text(encoding="utf-8"),
            "# Demo\n\nno markers here\n",
        )

    def test_reversed_markers_are_rejected(self):
        self.readme.write_text(
            f"# Demo\n{END_MARKER}\nbody\n{START_MARKER}\n",
            encoding="utf-8",
        )
        self.write_patch_list([make_patch("No Ads")])

        result = self.default_run()

        self.assertEqual(result.returncode, 1)
        self.assertIn("before the start marker", result.stderr)

    def test_invalid_repo_format_exits_nonzero(self):
        self.write_readme()
        self.write_patch_list([make_patch("No Ads")])

        result = self.run_generator(
            "not-a-repo", "dev", str(self.patch_list), str(self.readme)
        )

        self.assertEqual(result.returncode, 1)
        self.assertIn("invalid repo format", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_missing_patches_list_exits_nonzero(self):
        self.write_readme()

        result = self.run_generator(
            "owner/repo", "dev", str(self.tmp / "nope.json"), str(self.readme)
        )

        self.assertEqual(result.returncode, 1)
        self.assertIn("not found", result.stderr)

    def test_malformed_json_exits_nonzero(self):
        self.write_readme()
        self.patch_list.write_text("{not json", encoding="utf-8")

        result = self.default_run()

        self.assertEqual(result.returncode, 1)
        self.assertIn("not valid JSON", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_patches_list_without_version_exits_nonzero(self):
        self.write_readme()
        self.patch_list.write_text(
            json.dumps({"patches": [make_patch("No Ads")]}), encoding="utf-8"
        )

        result = self.default_run()

        self.assertEqual(result.returncode, 1)
        self.assertIn("version", result.stderr)


class TestArgumentHandling(GeneratorTestCase):
    def test_usage_printed_when_arguments_are_missing(self):
        result = self.run_generator()

        self.assertEqual(result.returncode, 1)
        self.assertIn("Usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
