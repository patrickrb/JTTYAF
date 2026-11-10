"""Tests for release_notes.py — the tag lane's release-notes restoration.

Stdlib only, like test_publish_listings.py, so android.yml's test job can run
it with no extra install. Every branch the workflow step used to carry in bash
is pinned here: existing / missing release, lookup failure, dispatch versus
tag push, and the marker structure.
"""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest import mock

import release_notes as rn

BODY = (
    "## Release notes\r\n\r\n"
    "<!-- jttyaf-notes-start -->\r\n"
    "New: hamlib CAT support.\r\nFixes for Icom SWR meters.\r\n\r\n"
    "<!-- jttyaf-notes-end -->\r\n\r\n"
    "<!-- jttyaf-version: 0.151.0 -->\r\n"
    "_Version `0.151.0` — promoted from android-dev.1155._\r\n"
)
NOTES = "New: hamlib CAT support.\nFixes for Icom SWR meters.\n\n"


class DecideTest(unittest.TestCase):
    def test_existing_release_on_dispatch_restores_notes_and_body(self):
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", BODY)
        self.assertTrue(out.found)
        self.assertIsNone(out.error)
        self.assertEqual(out.notes, NOTES)
        self.assertNotIn("\r", out.body)
        self.assertIn("<!-- jttyaf-version: 0.151.0 -->", out.body)

    def test_existing_release_on_push_restores_too(self):
        out = rn.decide("push", "android-v0.151.0", 0, "", BODY)
        self.assertTrue(out.found)
        self.assertEqual(out.notes, NOTES)

    def test_missing_release_on_dispatch_is_an_error(self):
        out = rn.decide("workflow_dispatch", "android-v9.9.9", 1, "release not found", "")
        self.assertIsNotNone(out.error)
        self.assertIn("android-v9.9.9", out.error)
        self.assertIn("release not found", out.error)

    def test_missing_release_on_push_falls_back(self):
        out = rn.decide("push", "android-v9.9.9", 1, "release not found\n", "")
        self.assertFalse(out.found)
        self.assertIsNone(out.error)
        self.assertIn("plain tag push", out.message)

    def test_lookup_failure_on_push_is_an_error_not_a_fallback(self):
        # Auth, rate limit, transient API error: the release may well exist and
        # carrying on would overwrite it.
        for err in ("HTTP 401: Bad credentials", "HTTP 403: API rate limit exceeded", ""):
            out = rn.decide("push", "android-v0.151.0", 1, err, "")
            self.assertIsNotNone(out.error, err)
            self.assertIn("refusing to continue", out.error)

    def test_lookup_failure_on_dispatch_is_an_error(self):
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 1, "HTTP 401: Bad credentials", "")
        self.assertIsNotNone(out.error)
        self.assertIn("Bad credentials", out.error)

    def test_no_markers_on_dispatch_is_an_error(self):
        # A release cut by a plain tag push, or one whose body was edited.
        out = rn.decide("workflow_dispatch", "android-v0.149", 0, "", "## Release\nno markers here\n")
        self.assertIsNotNone(out.error)
        self.assertIn("no usable release notes", out.error)

    def test_no_markers_on_push_keeps_body_and_ships_without_notes(self):
        out = rn.decide("push", "android-v0.149", 0, "", "## Release\nno markers here\n")
        self.assertTrue(out.found)
        self.assertEqual(out.notes, "")
        self.assertIn("no markers here", out.body)

    def test_blank_notes_between_markers_on_dispatch_is_an_error(self):
        body = "<!-- jttyaf-notes-start -->\n\n   \n<!-- jttyaf-notes-end -->\n"
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body)
        self.assertIsNotNone(out.error)
        self.assertIn("no release notes between", out.error)

    def test_end_marker_before_start_is_malformed(self):
        # A lenient scan would capture everything after the start marker and
        # ship the trailing release text to Play.
        body = "<!-- jttyaf-notes-end -->\n<!-- jttyaf-notes-start -->\ntrailing text\n"
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body)
        self.assertIsNotNone(out.error)
        self.assertIn("end marker comes before", out.error)
        out = rn.decide("push", "android-v0.151.0", 0, "", body)
        self.assertTrue(out.found)
        self.assertEqual(out.notes, "")

    def test_duplicate_markers_are_malformed(self):
        body = ("<!-- jttyaf-notes-start -->\nA\n<!-- jttyaf-notes-end -->\n"
                "<!-- jttyaf-notes-start -->\nB\n<!-- jttyaf-notes-end -->\n")
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body)
        self.assertIsNotNone(out.error)
        self.assertIn("found 2 and 2", out.error)

    def test_only_one_of_the_markers_is_malformed(self):
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "",
                        "<!-- jttyaf-notes-start -->\nA\n")
        self.assertIsNotNone(out.error)


class ExtractNotesTest(unittest.TestCase):
    def test_matches_the_old_awk_extraction(self):
        self.assertEqual(rn.extract_notes(BODY.replace("\r", "")), NOTES)

    def test_no_markers_raises(self):
        with self.assertRaises(rn.MalformedMarkers):
            rn.extract_notes("plain body")


class MainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def path(self, name, content=None):
        p = os.path.join(self.tmp, name)
        if content is not None:
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write(content)
        return p

    def run_main(self, event, status, stderr, body):
        argv = [
            "restore",
            "--event", event, "--tag", "android-v0.151.0",
            "--gh-status", str(status),
            "--gh-stderr", self.path("err.txt", stderr),
            "--body", self.path("body.raw", body),
            "--notes-out", self.path("notes.txt"),
            "--body-out", self.path("existing-release-body.md"),
            "--github-output", self.path("out.txt", ""),
        ]
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = rn.main(argv)
        return code, stdout.getvalue()

    def read(self, name):
        with open(os.path.join(self.tmp, name), encoding="utf-8", newline="") as f:
            return f.read()

    def test_restored_notes_are_byte_capped_for_play(self):
        body = "<!-- jttyaf-notes-start -->\n" + "\u0416" * 300 + "\n<!-- jttyaf-notes-end -->\n"
        code, out = self.run_main("workflow_dispatch", 0, "", body)
        self.assertEqual(code, 0)
        self.assertLessEqual(len(self.read("notes.txt").encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        # The body itself is untouched: only Play's copy is capped.
        self.assertIn("\u0416" * 300, self.read("existing-release-body.md"))

    def test_found_writes_notes_body_and_output(self):
        code, out = self.run_main("workflow_dispatch", 0, "", BODY)
        self.assertEqual(code, 0)
        self.assertEqual(self.read("notes.txt"), NOTES)
        self.assertNotIn("\r", self.read("existing-release-body.md"))
        self.assertEqual(self.read("out.txt"), "found=true\n")
        self.assertIn("hamlib CAT support", out)

    def test_not_found_on_push_writes_no_files(self):
        code, out = self.run_main("push", 1, "release not found", "")
        self.assertEqual(code, 0)
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "notes.txt")))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "existing-release-body.md")))
        self.assertEqual(self.read("out.txt"), "found=false\n")

    def test_error_exits_1_with_an_error_annotation_and_no_files(self):
        code, out = self.run_main("workflow_dispatch", 1, "release not found", "")
        self.assertEqual(code, 1)
        self.assertTrue(out.startswith("::error::"))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "notes.txt")))
        self.assertEqual(self.read("out.txt"), "")


class EnsureNotesTest(unittest.TestCase):
    PR_LIST = (
        "- #801 rigs: Follow IC-705 dial changes\n"
        "- #802 Fix Bluetooth CAT write race\n"
        "- #803 ci: stop publishing to Play production on main merges\n"
    )

    def test_nonblank_notes_are_left_alone(self):
        notes, fell_back = rn.ensure_notes("Real notes.\n", self.PR_LIST)
        self.assertEqual(notes, "Real notes.\n")
        self.assertFalse(fell_back)

    def test_blank_notes_become_the_pr_titles(self):
        # Claude's schema allows an empty string and `jq -r` writes it as a
        # bare newline: exactly what used to produce blank markers.
        for blank in ("", "\n", "   \n\n", None):
            notes, fell_back = rn.ensure_notes(blank, self.PR_LIST)
            self.assertTrue(fell_back, repr(blank))
            self.assertEqual(
                notes,
                "Follow IC-705 dial changes\n"
                "Fix Bluetooth CAT write race\n"
                "stop publishing to Play production on main merges\n",
            )

    def test_blank_notes_and_no_prs_use_the_default_text(self):
        # The workflow never hands over an empty list: with no PR merges in the
        # range it writes the "(no pull-request merges in range)" placeholder,
        # which is a log line, not a release note.
        for no_prs in ("", rn.NO_PRS_SENTINEL, rn.NO_PRS_SENTINEL + "\n"):
            notes, fell_back = rn.ensure_notes("\n", no_prs)
            self.assertTrue(fell_back, repr(no_prs))
            self.assertEqual(notes, rn.DEFAULT_NOTES + "\n", repr(no_prs))
            self.assertNotIn("pull-request", notes)

    def test_sentinel_matches_what_the_workflow_writes(self):
        # Drift guard: the placeholder is spelled in android.yml; if it changes
        # there, this must change too or Play gets the placeholder as notes.
        wf = os.path.join(os.path.dirname(__file__), "..", "workflows", "android.yml")
        with open(wf, encoding="utf-8") as f:
            self.assertIn(":-" + rn.NO_PRS_SENTINEL + "}", f.read())

    def test_fallback_keeps_whole_lines_under_plays_limit(self):
        long_list = "".join("- #%d %s\n" % (i, "x" * 120) for i in range(10))
        notes = rn.fallback_notes(long_list)
        self.assertLessEqual(len(notes), rn.PLAY_NOTES_LIMIT)
        self.assertEqual(notes.count("\n"), 4)  # 4 x 121 = 484 fits, 5 would not
        self.assertTrue(all(len(l) == 120 for l in notes.splitlines()))

    def test_fallback_budgets_bytes_not_characters(self):
        # 120 emoji is 120 characters but 480 bytes: one line fits, a second
        # would not. A character budget would have taken four such lines and
        # left `head -c 500` to cut the second one mid-codepoint.
        emoji_line = "\U0001F4E1" * 120
        long_list = "".join("- #%d %s\n" % (i, emoji_line) for i in range(4))
        notes = rn.fallback_notes(long_list)
        self.assertEqual(notes, emoji_line + "\n")
        self.assertLessEqual(len(notes.encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        # The byte-truncated file must still be valid UTF-8 (a no-op here).
        notes.encode("utf-8")[: rn.PLAY_NOTES_LIMIT].decode("utf-8")

    def test_cap_notes_cuts_multibyte_text_at_a_line_then_a_codepoint(self):
        # Claude's notes are asked to stay under 400 characters, which Cyrillic
        # (2 bytes each) pushes past 500 bytes.
        line = "\u0416" * 100  # 200 bytes + newline
        three_lines = (line + "\n") * 3  # 603 bytes
        capped = rn.cap_notes(three_lines)
        self.assertEqual(capped, (line + "\n") * 2)  # 402 bytes, cut at a line
        one_long_line = "\u0416" * 300  # 600 bytes, no line break to cut at
        capped = rn.cap_notes(one_long_line)
        self.assertLessEqual(len(capped.encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        self.assertEqual(capped, "\u0416" * 250)  # whole codepoints only
        self.assertEqual(rn.cap_notes("short\n"), "short\n")

    def test_ensure_notes_caps_real_notes_too(self):
        notes, fell_back = rn.ensure_notes("\u0416" * 300 + "\n", "")
        self.assertFalse(fell_back)
        self.assertLessEqual(len(notes.encode("utf-8")), rn.PLAY_NOTES_LIMIT)

    def test_round_trip_producer_to_manual_ship(self):
        # The producer/consumer case: notes that went through ensure_notes
        # land between the markers "Write release notes" emits, and the manual
        # ship's restore accepts that body — while a blank producer output that
        # skipped ensure_notes would have been refused.
        def body_for(notes):
            return ("## Release notes\n\n%s\n%s\n%s\n\n<!-- jttyaf-version: 0.151.0 -->\n"
                    % (rn.START, notes, rn.END))
        blank, _ = "\n", None
        refused = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body_for(blank))
        self.assertIsNotNone(refused.error)
        ensured, _ = rn.ensure_notes(blank, self.PR_LIST)
        accepted = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body_for(ensured))
        self.assertTrue(accepted.found)
        self.assertIn("Follow IC-705 dial changes", accepted.notes)


class EnsureNotesMainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.notes = os.path.join(self.tmp, "notes.txt")

    def run_ensure(self, content, pr_list):
        if content is not None:
            with open(self.notes, "w", encoding="utf-8", newline="") as f:
                f.write(content)
        os.environ["PR_LIST_TEST"] = pr_list
        try:
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = rn.main(["ensure-notes", "--notes", self.notes, "--pr-list-env", "PR_LIST_TEST"])
        finally:
            del os.environ["PR_LIST_TEST"]
        with open(self.notes, encoding="utf-8", newline="") as f:
            return code, stdout.getvalue(), f.read()

    def test_blank_file_is_rewritten_with_a_warning(self):
        code, out, notes = self.run_ensure("\n", "- #1 A title\n")
        self.assertEqual(code, 0)
        self.assertEqual(notes, "A title\n")
        self.assertIn("::warning", out)

    def test_missing_file_is_created(self):
        code, out, notes = self.run_ensure(None, rn.NO_PRS_SENTINEL)
        self.assertEqual(code, 0)
        self.assertEqual(notes, rn.DEFAULT_NOTES + "\n")
        # The warning must not claim PR titles were used when there were none.
        self.assertIn("::warning", out)
        self.assertNotIn("using the PR titles instead", out)

    def test_real_notes_are_untouched_and_silent(self):
        code, out, notes = self.run_ensure("Real notes.\n", "- #1 A title\n")
        self.assertEqual(code, 0)
        self.assertEqual(notes, "Real notes.\n")
        self.assertEqual(out, "")

    def test_oversized_real_notes_are_trimmed_with_a_warning(self):
        code, out, notes = self.run_ensure(("\u0416" * 100 + "\n") * 3, "")
        self.assertEqual(code, 0)
        self.assertLessEqual(len(notes.encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        self.assertIn("::warning title=Release notes trimmed", out)


PIN_BODY = (
    "First dev -> staging promotion.\r\n\r\n"
    "<!-- jttyaf-version: 1.0.0 -->\r\n"
    "<!-- jttyaf-notes-start -->\r\n"
    "First release of JTTYAF.\r\nDecode and send JTTY on your phone.\r\n"
    "<!-- jttyaf-notes-end -->\r\n\r\n"
    "Generated with Claude Code\r\n"
)


class PinFromBodyTest(unittest.TestCase):
    def test_version_and_notes_pinned(self):
        out = rn.pin_from_body(PIN_BODY, "0.0.0", "PR #12")
        self.assertIsNone(out.error)
        self.assertTrue(out.found)
        self.assertEqual(out.version, "1.0.0")
        self.assertEqual(out.notes, "First release of JTTYAF.\nDecode and send JTTY on your phone.\n")
        self.assertIn("PR #12", out.message)
        self.assertIn("version 1.0.0", out.message)
        self.assertIn("release notes", out.message)

    def test_version_only(self):
        out = rn.pin_from_body("Promote.\n<!-- jttyaf-version: 2.3.4 -->\n", "2.3.3")
        self.assertTrue(out.found)
        self.assertEqual(out.version, "2.3.4")
        self.assertEqual(out.notes, "")

    def test_notes_only_leaves_the_version_to_the_bump_step(self):
        out = rn.pin_from_body(
            "<!-- jttyaf-notes-start -->\nHand-written notes.\n<!-- jttyaf-notes-end -->\n", "0.5.0")
        self.assertTrue(out.found)
        self.assertEqual(out.version, "")
        self.assertEqual(out.notes, "Hand-written notes.\n")

    def test_body_without_markers_pins_nothing(self):
        for body in ("Just a promotion PR.\n", "", None):
            out = rn.pin_from_body(body, "0.0.0")
            self.assertFalse(out.found, repr(body))
            self.assertIsNone(out.error)
            self.assertEqual(out.version, "")
            self.assertEqual(out.notes, "")

    def test_marker_spacing_is_tolerated(self):
        out = rn.pin_from_body("<!-- jttyaf-version:1.2.3-->", "0.0.0")
        self.assertEqual(out.version, "1.2.3")

    def test_non_semver_version_is_an_error(self):
        for bad in ("v1.0.0", "1.0", "1.0.0-rc1", "one"):
            out = rn.pin_from_body("<!-- jttyaf-version: %s -->" % bad, "0.0.0")
            self.assertIsNotNone(out.error, bad)
            self.assertIn(bad, out.error)
            self.assertFalse(out.found)

    def test_version_not_above_previous_is_an_error(self):
        for pinned, prev in (("1.0.0", "1.0.0"), ("0.9.9", "1.0.0"), ("1.0.0", "1.0.10")):
            out = rn.pin_from_body("<!-- jttyaf-version: %s -->" % pinned, prev)
            self.assertIsNotNone(out.error, (pinned, prev))
            self.assertIn("not above the previous production version", out.error)

    def test_version_compares_numerically_not_lexically(self):
        out = rn.pin_from_body("<!-- jttyaf-version: 1.0.10 -->", "1.0.9")
        self.assertIsNone(out.error)
        self.assertEqual(out.version, "1.0.10")

    def test_conflicting_version_markers_are_an_error(self):
        out = rn.pin_from_body(
            "<!-- jttyaf-version: 1.0.0 -->\n<!-- jttyaf-version: 1.1.0 -->\n", "0.0.0")
        self.assertIsNotNone(out.error)
        self.assertIn("more than one version", out.error)

    def test_repeated_identical_version_marker_is_fine(self):
        out = rn.pin_from_body(
            "<!-- jttyaf-version: 1.0.0 -->\n<!-- jttyaf-version: 1.0.0 -->\n", "0.0.0")
        self.assertIsNone(out.error)
        self.assertEqual(out.version, "1.0.0")

    def test_blank_notes_markers_are_an_error(self):
        out = rn.pin_from_body(
            "<!-- jttyaf-notes-start -->\n\n<!-- jttyaf-notes-end -->\n", "0.0.0")
        self.assertIsNotNone(out.error)
        self.assertIn("empty release-notes markers", out.error)

    def test_malformed_notes_markers_are_an_error(self):
        out = rn.pin_from_body(
            "<!-- jttyaf-notes-end -->\nx\n<!-- jttyaf-notes-start -->\n", "0.0.0")
        self.assertIsNotNone(out.error)
        self.assertIn("malformed", out.error)

    def test_pinned_notes_are_byte_capped_for_play(self):
        body = "<!-- jttyaf-notes-start -->\n" + ("Ж" * 100 + "\n") * 3 + "<!-- jttyaf-notes-end -->\n"
        out = rn.pin_from_body(body, "0.0.0")
        self.assertIsNone(out.error)
        self.assertLessEqual(len(out.notes.encode("utf-8")), rn.PLAY_NOTES_LIMIT)

    def test_pinned_notes_round_trip_through_the_release_body_markers(self):
        # What "Write release notes" puts between the markers must come back out
        # unchanged for the main promotion and the manual production ship.
        out = rn.pin_from_body(PIN_BODY, "0.0.0")
        release_body = "## Release notes\n\n%s\n%s\n%s\n\n<!-- jttyaf-version: %s -->\n" % (
            rn.START, out.notes, rn.END, out.version)
        self.assertEqual(rn.extract_notes(release_body), out.notes + "\n")


class PinMainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.body = os.path.join(self.tmp, "pr-body.md")
        self.notes = os.path.join(self.tmp, "pinned-notes.txt")
        self.gh_out = os.path.join(self.tmp, "github_output")

    def run_pin(self, body, prev="0.0.0"):
        with open(self.body, "w", encoding="utf-8", newline="") as f:
            f.write(body)
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = rn.main(["pin", "--body", self.body, "--prev-version", prev,
                            "--source", "PR #12", "--notes-out", self.notes,
                            "--github-output", self.gh_out])
        gh = open(self.gh_out, encoding="utf-8").read() if os.path.exists(self.gh_out) else ""
        return code, stdout.getvalue(), gh

    def test_pinned_version_and_notes_write_the_file_and_outputs(self):
        code, out, gh = self.run_pin(PIN_BODY)
        self.assertEqual(code, 0)
        self.assertEqual(gh, "found=true\nversion=1.0.0\nnotes=true\n")
        with open(self.notes, encoding="utf-8", newline="") as f:
            self.assertEqual(f.read(), "First release of JTTYAF.\nDecode and send JTTY on your phone.\n")
        self.assertIn("Pinned in PR #12", out)

    def test_nothing_pinned_writes_no_notes_file(self):
        code, out, gh = self.run_pin("Promotion with no markers.\n")
        self.assertEqual(code, 0)
        self.assertEqual(gh, "found=false\nversion=\nnotes=false\n")
        self.assertFalse(os.path.exists(self.notes))

    def test_version_only_reports_no_notes(self):
        code, out, gh = self.run_pin("<!-- jttyaf-version: 1.0.0 -->\n")
        self.assertEqual(code, 0)
        self.assertEqual(gh, "found=true\nversion=1.0.0\nnotes=false\n")
        self.assertFalse(os.path.exists(self.notes))

    def test_error_exits_1_with_an_annotation_and_no_outputs(self):
        code, out, gh = self.run_pin("<!-- jttyaf-version: 1.0.0 -->\n", prev="1.0.0")
        self.assertEqual(code, 1)
        self.assertIn("::error::", out)
        self.assertEqual(gh, "")
        self.assertFalse(os.path.exists(self.notes))


class InternalInspectionTest(unittest.TestCase):
    def inspect(self, payload, get_status=200, delete_status=204, auth_error=None,
                post_status=200):
        import publish_listings as pl
        from test_publish_listings import FakeResponse

        # Only this endpoint allowlist exists: a track mutation, upload, commit,
        # other-track read or missing cleanup fails rather than silently mocking it.
        base = pl.API + "/applications/radio.ks3ckc.jttyaf/edits"
        pending = []

        def create(url, timeout=None):
            self.assertEqual(url, base)
            if post_status == 200:
                pending.append("inspection-edit")
            return FakeResponse({"id": "inspection-edit"}, post_status)

        def read(url, timeout=None):
            self.assertEqual(url, base + "/inspection-edit/tracks/internal")
            self.assertEqual(pending, ["inspection-edit"])
            if isinstance(get_status, Exception):
                raise get_status
            return FakeResponse(payload, get_status)

        def delete(url, timeout=None):
            self.assertEqual(url, base + "/inspection-edit")
            pending.pop()
            if isinstance(delete_status, Exception):
                raise delete_status
            return FakeResponse({}, delete_status)

        session = mock.Mock(spec=["post", "get", "delete"])
        session.post = create
        session.get = read
        session.delete = delete
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "read.json")
            auth = ({"side_effect": auth_error} if auth_error else {"return_value": session})
            with mock.patch.object(pl, "play_session", **auth):
                rc = rn.main(["inspect-internal", "--report", path])
            with open(path) as f:
                report = json.load(f)
        self.assertEqual(pending, [])
        return rc, report

    def test_read_preserves_exact_status_and_codes_without_mutating_a_track(self):
        for state in ("draft", "completed", "inProgress", "halted"):
            with self.subTest(state=state):
                payload = {"track": "internal", "releases": [
                    {"name": "1.0.1", "status": "completed", "versionCodes": ["1029"]},
                    {"name": "1.0.2", "status": state, "versionCodes": ["1033"]}]}
                rc, report = self.inspect(payload)
                self.assertEqual(rc, 0)
                self.assertEqual(report["packageName"], "radio.ks3ckc.jttyaf")
                self.assertEqual(report["response"], payload)
                self.assertTrue(report["readSucceeded"])
                self.assertTrue(report["editDeleted"])
                self.assertIn("readAt", report)
                self.assertEqual(report["testerDeviceAvailability"], "not verified")

    def test_empty_internal_list_is_reported_without_inventing_a_release(self):
        rc, report = self.inspect({"track": "internal"})
        self.assertEqual(rc, 0)
        self.assertEqual(report["response"], {"track": "internal"})
        self.assertTrue(report["editDeleted"])

    def test_wrong_track_http_failure_or_auth_failure_is_not_reported_as_success(self):
        for kwargs in ({"payload": {"track": "production"}},
                       {"payload": {}, "get_status": 403},
                       {"payload": {}, "get_status": OSError("secret must not appear")},
                       {"payload": {}, "post_status": 403},
                       {"payload": {}, "auth_error": OSError("secret must not appear")}):
            with self.subTest(kwargs=kwargs):
                rc, report = self.inspect(**kwargs)
                self.assertEqual(rc, 1)
                self.assertFalse(report["readSucceeded"])
                self.assertNotIn("response", report)
                self.assertNotIn("secret must not appear", json.dumps(report))
                created = "auth_error" not in kwargs and "post_status" not in kwargs
                self.assertEqual(report["editDeleted"], True if created else None)

    def test_delete_failure_fails_inspection_even_after_a_successful_read(self):
        for failure in (403, OSError("secret must not appear")):
            with self.subTest(failure=failure):
                rc, report = self.inspect({"track": "internal"}, delete_status=failure)
                self.assertEqual(rc, 1)
                self.assertTrue(report["readSucceeded"])
                self.assertFalse(report["editDeleted"])
                self.assertIn("cleanupErrorType", report)
                self.assertNotIn("secret must not appear", json.dumps(report))


class InternalReleaseTest(unittest.TestCase):
    def test_exact_draft_is_verified_alongside_an_older_release(self):
        track = {"track": "internal", "releases": [
            {"name": "1.0.1", "status": "completed", "versionCodes": ["1029"]},
            {"name": "1.2.3", "status": "draft", "versionCodes": ["1047"],
             "releaseNotes": [{"language": "de-DE", "text": "Andere Notizen"},
                              {"language": "en-US", "text": "Better CAT recovery.\n"}]},
        ]}
        rn.validate_internal_release(track, "1.2.3", 1047, "Better CAT recovery.")

    def test_stale_or_incorrect_draft_is_rejected(self):
        release = {"name": "1.2.3", "status": "draft", "versionCodes": ["1047"],
                   "releaseNotes": [{"language": "en-US", "text": "Better CAT recovery."}]}
        for field, wrong in (("name", "1.2.2"), ("status", "completed"),
                             ("versionCodes", ["1046"]),
                             ("versionCodes", ["1047", "1046"]),
                             ("releaseNotes", [{"language": "en-US", "text": "Old notes"}]),
                             ("releaseNotes", [{"language": "de-DE", "text": "Better CAT recovery."}])):
            with self.subTest(field=field, wrong=wrong):
                bad = copy.deepcopy(release)
                bad[field] = wrong
                with self.assertRaises(ValueError):
                    rn.validate_internal_release({"track": "internal", "releases": [bad]},
                                                 "1.2.3", 1047, "Better CAT recovery.")

    def test_missing_or_wrong_track_is_not_delivery(self):
        for track in ({"track": "internal"}, {"track": "production", "releases": []}, {}):
            with self.subTest(track=track), self.assertRaises(ValueError):
                rn.validate_internal_release(track, "1.2.3", 1047, "Better CAT recovery.")


class ReleaseBillingPolicyTest(unittest.TestCase):
    def test_paid_generation_is_only_first_internal_creation(self):
        workflow = (Path(__file__).parents[1] / "workflows/android.yml").read_text()
        condition = re.search(
            r"id: ai\n(?: *#.*\n)* *if: >-\n((?:          .*\n)+)", workflow
        ).group(1)
        for key, name in (("steps.lane.outputs.lane", "lane"),
                          ("github.run_attempt", "attempt"),
                          ("steps.changes.outputs.android_changed", "changed"),
                          ("steps.candidate.outputs.found", "candidate"),
                          ("steps.pin.outputs.version", "pin")):
            condition = condition.replace(key, name)
        condition = " ".join(condition.split()).replace("&&", "and").replace("||", "or")
        for lane in ("dev", "promote", "production", "tag", "none"):
            for attempt in (1, 2, 5):
                for changed in ("true", "false"):
                    for candidate in ("true", "false"):
                        for pin in ("", "1.2.3"):
                            context = dict(lane=lane, attempt=attempt, changed=changed,
                                           candidate=candidate, pin=pin)
                            with self.subTest(**context):
                                allowed = eval(condition, {"__builtins__": {}}, context)
                                self.assertEqual(allowed, lane == "dev" and attempt == 1
                                                 and changed == "true"
                                                 and candidate == "false" and pin == "")


class ReleaseRoutingTest(unittest.TestCase):
    """Execute the workflow's actual Bash, without credentials or publishers."""

    workflow = (Path(__file__).parents[1] / "workflows/android.yml").read_text()

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        subprocess.run(["git", "init", "--quiet", temp.name], check=True)
        scripts = self.root / ".github/scripts"
        scripts.mkdir(parents=True)
        shutil.copy(Path(__file__).with_name("android-next-version.sh"), scripts)

    def step(self, step_id):
        return next(block for block in re.split(r"^      - name: ", self.workflow, flags=re.M)
                    if re.search(rf"^        id: {step_id}$", block, re.M))

    def run_step(self, step_id, event, ref, **overrides):
        output = self.root / "output"
        output.write_text("")
        env = dict(PATH=os.environ["PATH"], GITHUB_EVENT_NAME=event, GITHUB_REF=ref,
                   GITHUB_REF_NAME=ref.rsplit("/", 1)[-1], GITHUB_RUN_NUMBER="61",
                   GITHUB_OUTPUT=str(output), ANDROID_CHANGED="true", PREV_TAG="",
                   PREV_VERSION="3.14.15", CAND_FOUND="true", CAND_TAG="android-dev.60",
                   CAND_VERSION="7.12.34", PIN_VERSION="", PIN_NOTES="false",
                   BUMP="patch", BUMP_SOURCE="deterministic fallback")
        env.update(overrides)
        script = textwrap.dedent(self.step(step_id).split("        run: |\n", 1)[1])
        result = subprocess.run(["bash", "-c", script], cwd=self.root, env=env,
                                capture_output=True, text=True)
        return result, dict(line.split("=", 1) for line in output.read_text().splitlines())

    def route(self, event, ref, **overrides):
        result, lane = self.run_step("lane", event, ref)
        self.assertEqual(result.returncode, 0, result.stderr)
        result, release = self.run_step("tag", event, ref, LANE=lane["lane"], **overrides)
        self.assertEqual(result.returncode, 0, result.stderr)
        return release

    def test_event_ref_truth_table(self):
        refs = ("refs/heads/main", "refs/heads/staging", "refs/heads/dev",
                "refs/heads/feature/test", "refs/heads/main-extra",
                "refs/heads/android-v2.3.4", "refs/tags/android-v2.3.4",
                "refs/tags/android-dev.60", "refs/tags/desktop-v2.3.4",
                "refs/pull/22/merge")
        for event in ("pull_request", "push", "workflow_dispatch", "workflow_run",
                      "release", "schedule", "repository_dispatch"):
            for ref in refs:
                with self.subTest(event=event, ref=ref):
                    release = self.route(event, ref)
                    internal = event == "push" and ref in ("refs/heads/main", "refs/heads/staging")
                    production = event in ("push", "workflow_dispatch") and ref == "refs/tags/android-v2.3.4"
                    self.assertEqual(release["should_release"], str(internal or production).lower())
                    self.assertEqual(release["release_tag"], "android-dev.61" if internal else
                                     "android-v2.3.4" if production else "")
                    self.assertEqual(release["is_prerelease"], str(internal).lower())
                    self.assertEqual(release["play_track"], "internal" if internal else
                                     "production" if production else "")
                    if internal or production:
                        self.assertEqual(release["version_name"], "7.12.34" if internal else "2.3.4")
                    result, _ = self.run_step("boundary", event, ref,
                                             RELEASE_TAG=release["release_tag"],
                                             PRERELEASE=release["is_prerelease"],
                                             PLAY_TRACK=release["play_track"])
                    self.assertEqual(result.returncode == 0, internal or production)

    def test_main_and_staging_without_app_changes_do_not_release(self):
        for branch in ("main", "staging"):
            release = self.route("push", f"refs/heads/{branch}", ANDROID_CHANGED="false")
            self.assertEqual(release["should_release"], "false")
            self.assertEqual(release["release_tag"], "")
            self.assertEqual(release["play_track"], "")

    def test_missing_candidate_uses_patch_or_pin_without_stable_tag(self):
        for branch in ("main", "staging"):
            for pin, expected in (("", "3.14.16"), ("9.2.1", "9.2.1")):
                with self.subTest(branch=branch, pin=pin):
                    release = self.route("push", f"refs/heads/{branch}",
                                         CAND_FOUND="false", PIN_VERSION=pin)
                    self.assertEqual(release["version_name"], expected)
                    self.assertEqual(release["release_tag"], "android-dev.61")
                    self.assertEqual(release["is_prerelease"], "true")

    def test_boundary_rejects_old_main_and_partial_internal_outputs(self):
        for branch in ("main", "staging"):
            for tag, prerelease, track in (("android-v7.12.34", "false", ""),
                                           ("android-v7.12.34", "false", "production"),
                                           ("android-dev.61", "false", "internal"),
                                           ("android-dev.61", "true", "production"),
                                           ("android-dev.60", "true", "internal")):
                with self.subTest(branch=branch, tag=tag, prerelease=prerelease, track=track):
                    result, _ = self.run_step("boundary", "push", f"refs/heads/{branch}",
                                             RELEASE_TAG=tag, PRERELEASE=prerelease, PLAY_TRACK=track)
                    self.assertNotEqual(result.returncode, 0)

    def test_publishers_and_retry_use_internal_draft_and_checked_outputs(self):
        # Execute the actual Actions expressions that wire both upload attempts.
        def evaluate(expression, **context):
            expression = expression.removeprefix("${{ ").removesuffix(" }}")
            for key, value in context.items():
                expression = expression.replace(key, repr(value))
            return eval(expression.replace("&&", "and").replace("||", "or"), {"__builtins__": {}})

        for step_id in ("play_publish", "play_publish_retry"):
            block = self.step(step_id)
            for track, expected in (("internal", "draft"), ("production", "completed")):
                status = re.search(r"^          status: (.*)$", block, re.M).group(1)
                self.assertEqual(evaluate(status, **{"steps.tag.outputs.play_track": track}), expected)
            self.assertIn("track: ${{ steps.tag.outputs.play_track }}", block)
        self.assertIn("prerelease: ${{ steps.tag.outputs.is_prerelease == 'true' }}", self.workflow)
        self.assertLess(self.workflow.index("id: boundary"), self.workflow.index("name: Create GitHub Release"))
        self.assertIn("if: steps.tag.outputs.play_track == 'internal' && steps.tag.outputs.should_release == 'true'",
                      self.workflow.split("name: Verify the internal draft in Google Play", 1)[1])

    def test_retired_publishers_and_chained_release_triggers_stay_absent(self):
        workflows = Path(__file__).parents[1] / "workflows"
        for retired in ("desktop.yml", "ios.yml"):
            self.assertFalse((workflows / retired).exists(), retired)
        for path in workflows.glob("*.yml"):
            self.assertNotRegex(path.read_text(), r"(?m)^  (workflow_run|workflow_call|repository_dispatch|release):")

    def test_inspection_never_builds_publishes_or_takes_the_publisher_lock(self):
        parts = re.split(r"^  ([\w-]+):\n", self.workflow.split("jobs:\n", 1)[1], flags=re.M)
        jobs = dict(zip(parts[1::2], parts[2::2]))
        conditions = {job: re.search(r"^    if: (.*)$", jobs[job], re.M).group(1)
                      for job in ("inspect-internal", "detect", "build")}
        concurrency = re.search(r"^  group: >-\n((?:    .*\n)+)", self.workflow, re.M).group(1)

        def evaluate(expression, context):
            expression = " ".join(expression.split()).removeprefix("${{").removesuffix("}}")
            for key, value in context.items():
                expression = expression.replace(key, repr(value))
            expression = re.sub(r"!(?!=)", "not ", expression).replace("&&", "and").replace("||", "or")
            return eval(expression, {"__builtins__": {}, "always": lambda: True,
                                     "startsWith": lambda value, prefix: value.startswith(prefix),
                                     "format": lambda pattern, value: pattern.format(value)})

        for event in ("push", "pull_request", "workflow_dispatch"):
            for ref in ("refs/heads/main", "refs/heads/staging", "refs/heads/dev", "refs/tags/android-v2.3.4"):
                for inspect in (True, False):
                    with self.subTest(event=event, ref=ref, inspect=inspect):
                        context = {"github.event_name": event, "github.ref": ref,
                                   "github.run_id": "61", "inputs.inspect_internal": inspect}
                        read_only = event == "workflow_dispatch" and inspect
                        self.assertEqual(evaluate(conditions["inspect-internal"], context), read_only)
                        detected = evaluate(conditions["detect"], context)
                        self.assertEqual(detected, not read_only)
                        context.update({"needs.detect.outputs.run": "true" if detected else "",
                                        "needs.test.result": "skipped" if read_only else "success"})
                        self.assertEqual(evaluate(conditions["build"], context), not read_only)
                        publisher = (event == "push" and ref != "refs/heads/dev"
                                     or event == "workflow_dispatch" and not inspect
                                     and ref == "refs/tags/android-v2.3.4")
                        self.assertEqual(evaluate(concurrency, context), "play-publish" if publisher else "build-61")
        self.assertIn("needs: [detect, test]", jobs["build"])
        self.assertIn("release_notes.py inspect-internal", jobs["inspect-internal"])

    def test_discord_main_and_staging_are_internal_notifications(self):
        workflow = (Path(__file__).parents[1] / "workflows/discord-merge-notify.yml").read_text()
        script = textwrap.dedent(workflow.split("        run: |\n", 1)[1])
        capture = self.root / "curl-arguments"
        for branch, color, emoji in (("main", 3447003, "📦"), ("staging", 3447003, "📦"),
                                      ("dev", 15105570, "🔧")):
            env = dict(PATH=os.environ["PATH"], WEBHOOK_URL="https://example.invalid/webhook",
                       BRANCH=branch, SHA="1234567890", COMMIT_MSG="Internal candidate",
                       AUTHOR="Fixture", COMMIT_URL="https://example.invalid/commit",
                       COMPARE_URL="https://example.invalid/compare", REPO="fixture/JTTYAF",
                       CAPTURE=str(capture))
            result = subprocess.run(["bash", "-c", 'curl() { printf "%s\\n" "$@" > "$CAPTURE"; }\n' + script],
                                    cwd=self.root, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            args = capture.read_text().splitlines()
            payload = json.loads(args[args.index("-d") + 1])["embeds"][0]
            self.assertEqual(payload["color"], color)
            self.assertTrue(payload["title"].startswith(emoji))
            self.assertIn(f"merged into {branch}", payload["title"])


if __name__ == "__main__":
    unittest.main()
