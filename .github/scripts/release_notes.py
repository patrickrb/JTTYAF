#!/usr/bin/env python3
"""Release-notes helpers for android.yml: `pin`, `restore` and `ensure-notes`.

`pin` runs first on a staging or main push and reads the body of the promotion
PR that produced HEAD. A human can fix the release by hand there, using the
same hidden markers the release bodies carry: `<!-- jttyaf-version: x.y.z -->`
pins the semver (the AI bump step is then skipped) and notes between
`<!-- jttyaf-notes-start -->` / `<!-- jttyaf-notes-end -->` replace the AI's
what's-new. Either may appear alone; a body with neither changes nothing.

`ensure-notes` runs in "Write release notes" for the lanes that produce notes
(staging and main). The producers can yield a blank file — Claude's structured
output permits an empty `notes` string, and `jq -r` writes that as a bare
newline — and a release whose body carries blank markers would later be
refused by the manual production ship (`restore`, below). So before the notes
go into the markers, a blank file is replaced with the PR titles (whole lines,
up to Play's 500-character limit) or, failing that, "Bug fixes and
improvements." — the same fallback the AI step used when the API was
unreachable, now applied in one place for every producer.

`restore` restores the release notes of the GitHub Release an android-v* tag
already has. Used by the `tag` lane. The manual production ship re-runs the
workflow on an android-v<x.y.z> tag whose GitHub Release the main-merge run
already created, carrying the promoted staging notes in its body between the
hidden markers written by "Write release notes":

    <!-- jttyaf-notes-start -->
    ...notes...
    <!-- jttyaf-notes-end -->

The workflow step runs `gh release view` and hands this script the result; the
decision — existing release / missing release / lookup failure, manual dispatch
versus tag push, and the marker parsing — lives here so test_release_notes.py
can cover every branch instead of a bash step nobody can run in CI.

Outcomes (`found=...` is appended to --github-output):
  found=true   the release exists: --body-out gets its body verbatim (for the
               release step to hand back to softprops unchanged) and --notes-out
               gets the text between the markers (for Play's what's-new).
  found=false  a plain tag push found no release: nothing is written, the
               workflow falls back to the version string as before.
  exit 1       the run must stop, with a ::error:: line saying why. Always on a
               workflow_dispatch that cannot read its release or finds no notes
               in it (the manual ship is documented to send the promoted notes,
               and carrying on would overwrite the release body with a
               placeholder); on a tag push for any lookup failure other than a
               genuine "release not found".
"""
import argparse
import json
import os
import re
import sys

START = "<!-- jttyaf-notes-start -->"
END = "<!-- jttyaf-notes-end -->"

# Play's what's-new limit, in BYTES: the workflow truncates notes.txt with
# `head -c 500` before upload, and the API limit is on the encoded text. A
# character budget would let multibyte titles (Cyrillic, CJK, emoji) pass here
# and be cut mid-codepoint downstream, sending Play invalid UTF-8.
PLAY_NOTES_LIMIT = 500
DEFAULT_NOTES = "Bug fixes and improvements."
# What the AI step's "Collect changes" writes to its prs output when the range
# had no PR merges (android.yml: `${PRS:-(no pull-request merges in range)}`).
# A placeholder for the log, not a release note; must never reach Play.
NO_PRS_SENTINEL = "(no pull-request merges in range)"


def utf8_len(text):
    return len(text.encode("utf-8"))


def fallback_notes(pr_list):
    """Release notes from the PR list the AI step collects ("- #123 scope: title").

    Whole lines only, stopping before the total would exceed PLAY_NOTES_LIMIT
    bytes of UTF-8 (each line counts its newline), so the downstream `head -c`
    never lands inside a multibyte character. Empty input yields DEFAULT_NOTES.
    """
    out = []
    n = 0
    for line in (pr_list or "").splitlines():
        line = re.sub(r"^- #[0-9]+ [^:]*: ?", "", line)
        line = re.sub(r"^- #[0-9]+ ", "", line)
        if not line.strip() or line.strip() == NO_PRS_SENTINEL:
            continue
        if n + utf8_len(line) + 1 > PLAY_NOTES_LIMIT:
            break
        out.append(line)
        n += utf8_len(line) + 1
    return "\n".join(out) + "\n" if out else DEFAULT_NOTES + "\n"


def cap_notes(notes):
    """Fit any producer's notes into PLAY_NOTES_LIMIT bytes without splitting a
    character: cut at the last line break inside the budget, else at the last
    whole codepoint. Claude is asked for <= 400 characters, which multibyte text
    can push past 500 bytes; the workflow's `head -c 500` would then cut
    mid-codepoint, so the cap is applied here first and that head is a no-op.
    """
    data = notes.encode("utf-8")
    if len(data) <= PLAY_NOTES_LIMIT:
        return notes
    cut = data.rfind(b"\n", 0, PLAY_NOTES_LIMIT)
    if cut > 0:
        return data[:cut].decode("utf-8") + "\n"
    return data[:PLAY_NOTES_LIMIT].decode("utf-8", errors="ignore")


def ensure_notes(notes, pr_list):
    """(notes, used_fallback): nonblank notes come back unchanged apart from the
    byte cap; blank ones become the fallback."""
    if notes is not None and notes.strip():
        return cap_notes(notes), False
    return fallback_notes(pr_list), True


class MalformedMarkers(Exception):
    """The body does not carry exactly one ordered start/end marker pair."""


def extract_notes(body):
    """Text between the markers, line-wise, as the awk extraction produced it.

    Strict about structure: exactly one start marker, exactly one end marker,
    start before end. Anything else raises MalformedMarkers — with the end
    marker first, a lenient scan would capture everything after the start
    marker and ship trailing release text to Play as the what's-new.
    """
    starts = body.count(START)
    ends = body.count(END)
    if starts == 0 and ends == 0:
        raise MalformedMarkers("no jttyaf-notes markers")
    if starts != 1 or ends != 1:
        raise MalformedMarkers(
            "expected one start and one end marker, found %d and %d" % (starts, ends)
        )
    s = body.index(START)
    e = body.index(END)
    if e < s:
        raise MalformedMarkers("end marker comes before the start marker")
    inner = body[s + len(START):e]
    # The markers sit on their own lines; drop the line break that follows the
    # start marker so the notes begin at their first line, like awk's did.
    if inner.startswith("\n"):
        inner = inner[1:]
    return inner


class Outcome:
    def __init__(self, found=None, error=None, notes="", body="", message=""):
        self.found = found      # True / False, or None when error is set
        self.error = error      # the ::error:: text, or None
        self.notes = notes
        self.body = body
        self.message = message  # informational stdout line


def decide(event, tag, gh_status, gh_stderr, body):
    """The whole decision, pure so it can be tested without gh or a runner."""
    dispatch = event == "workflow_dispatch"
    gh_stderr = (gh_stderr or "").strip()
    if gh_status != 0:
        if dispatch:
            return Outcome(error=(
                "Could not read the GitHub Release for %s (%s). A manual production "
                "ship needs the release the main merge created — check the tag and "
                "rerun." % (tag, gh_stderr)))
        if "not found" not in gh_stderr.lower():
            # A tag push with a release we merely failed to read is the same
            # overwrite risk; only a genuine "no release" may fall through.
            return Outcome(error=(
                "Could not read the GitHub Release for %s (%s); refusing to continue "
                "and risk overwriting it." % (tag, gh_stderr)))
        return Outcome(found=False, message=(
            "No existing release for %s (plain tag push) — Play's what's-new falls "
            "back to the version string." % tag))

    body = (body or "").replace("\r", "")
    try:
        notes = extract_notes(body)
    except MalformedMarkers as m:
        if dispatch:
            return Outcome(error=(
                "The GitHub Release for %s has no usable release notes (%s), so there "
                "is nothing to send to Play as the what's-new. Put the notes in the "
                "release body between %s and %s and rerun." % (tag, m, START, END)))
        # A tag push keeps the body but ships without notes, as it always did.
        notes = ""
    if dispatch and not notes.strip():
        return Outcome(error=(
            "The GitHub Release for %s has no release notes between the jttyaf-notes "
            "markers, so there is nothing to send to Play as the what's-new. Add "
            "them to the release body and rerun." % tag))
    return Outcome(found=True, notes=notes, body=body, message=(
        "Release %s already exists; keeping its body." % tag))


VERSION_MARKER_RE = re.compile(r"<!-- jttyaf-version: *([^>]*?) *-->")
SEMVER_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def parse_semver(text):
    """(major, minor, patch) for a strict x.y.z string, else None."""
    if not SEMVER_RE.match(text or ""):
        return None
    return tuple(int(p) for p in text.split("."))


def validate_internal_release(track, version, code, notes):
    """Require Google's track response to contain this exact draft and notes."""
    if track.get("track") != "internal":
        raise ValueError("Google returned a different track, not internal")
    matches = [r for r in track.get("releases", []) if r.get("name") == version]
    if len(matches) != 1:
        raise ValueError("Expected one internal release named %s" % version)
    release = matches[0]
    if release.get("status") != "draft":
        raise ValueError("Internal release %s is not a draft" % version)
    if release.get("versionCodes") != [str(code)]:
        raise ValueError("Internal draft does not contain exactly versionCode %s" % code)
    english = [n.get("text", "").strip() for n in release.get("releaseNotes", [])
               if n.get("language") == "en-US"]
    if not notes.strip() or english != [notes.strip()]:
        raise ValueError("Internal draft's English release notes do not match the build")


class PinOutcome:
    def __init__(self, found=False, version="", notes="", error=None, message=""):
        self.found = found      # a version or notes (or both) were pinned
        self.version = version  # "" when the body pins no version
        self.notes = notes      # "" when the body pins no notes
        self.error = error      # the ::error:: text, or None
        self.message = message


def pin_from_body(body, prev_version, source="the promotion PR"):
    """Read a hand-pinned version and/or release notes out of a PR body.

    The promotion PR (dev -> staging, or staging -> main when no staging build
    is being promoted) may carry the same hidden markers the release bodies
    use: `<!-- jttyaf-version: x.y.z -->` fixes the semver, and notes between
    `<!-- jttyaf-notes-start -->` / `<!-- jttyaf-notes-end -->` replace the
    AI's. Either may appear alone. A body with neither pins nothing
    (found=False) and the AI step decides as usual.

    Strict where it matters: a version marker must hold a plain x.y.z and must
    be greater than the previous production version (a pin that would re-use
    or go below a shipped version is an error, not a silent fallback), and
    pinned notes must not be blank — blank markers mean somebody meant to write
    notes and did not.
    """
    body = (body or "").replace("\r", "")
    versions = VERSION_MARKER_RE.findall(body)
    has_notes_markers = START in body or END in body
    if not versions and not has_notes_markers:
        return PinOutcome(message="No version or notes pinned in %s." % source)

    version = ""
    if versions:
        if len(set(versions)) > 1:
            return PinOutcome(error=(
                "%s pins more than one version (%s); keep a single "
                "<!-- jttyaf-version: x.y.z --> marker." % (source, ", ".join(versions))))
        version = versions[0]
        if parse_semver(version) is None:
            return PinOutcome(error=(
                "%s pins version '%s', which is not a plain x.y.z semver." % (source, version)))
        prev = parse_semver(prev_version) or (0, 0, 0)
        if parse_semver(version) <= prev:
            return PinOutcome(error=(
                "%s pins version %s, which is not above the previous production "
                "version %s." % (source, version, prev_version or "0.0.0")))

    notes = ""
    if has_notes_markers:
        try:
            notes = extract_notes(body)
        except MalformedMarkers as m:
            return PinOutcome(error=(
                "%s has malformed release-notes markers (%s)." % (source, m)))
        if not notes.strip():
            return PinOutcome(error=(
                "%s has empty release-notes markers; write the notes between %s and %s "
                "or remove the markers." % (source, START, END)))
        notes = cap_notes(notes)

    what = []
    if version:
        what.append("version %s" % version)
    if notes:
        what.append("release notes")
    return PinOutcome(found=True, version=version, notes=notes,
                      message="Pinned in %s: %s." % (source, " and ".join(what)))


def build_arg_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="command", required=True)

    p = sub.add_parser("pin", help="read a hand-pinned version/notes from a promotion PR body")
    p.add_argument("--body", required=True, help="file holding the PR body")
    p.add_argument("--prev-version", default="0.0.0", help="previous production version (x.y.z)")
    p.add_argument("--source", default="the promotion PR", help="how to name the PR in messages")
    p.add_argument("--notes-out", required=True, help="written only when notes are pinned")
    p.add_argument("--github-output",
                   help="$GITHUB_OUTPUT; found=, version= and notes=true|false are appended")

    r = sub.add_parser("restore", help="restore the notes of the release a tag already has")
    r.add_argument("--event", required=True, help="$GITHUB_EVENT_NAME")
    r.add_argument("--tag", required=True)
    r.add_argument("--gh-status", type=int, required=True, help="exit code of gh release view")
    r.add_argument("--gh-stderr", required=True, help="file holding gh's stderr")
    r.add_argument("--body", required=True, help="file holding gh's stdout (the body)")
    r.add_argument("--notes-out", required=True)
    r.add_argument("--body-out", required=True)
    r.add_argument("--github-output", help="$GITHUB_OUTPUT; found=true|false is appended")

    e = sub.add_parser("ensure-notes", help="replace a blank notes file with the fallback text")
    e.add_argument("--notes", required=True, help="notes file; created or rewritten when blank")
    e.add_argument("--pr-list-env", default="PR_LIST",
                   help="env var holding the AI step's PR list (default PR_LIST)")

    v = sub.add_parser("verify-internal", help="read back the committed internal draft from Play")
    v.add_argument("--version", required=True)
    v.add_argument("--code", type=int, required=True)
    v.add_argument("--notes", required=True)
    v.add_argument("--report", required=True, help="credential-free Google track response and verdict")
    return ap


def run_verify_internal(args):
    # Reuse the existing authorized Play session/cleanup, not a second auth path.
    from publish_listings import API, DEFAULT_PACKAGE, abandon_edit, play_session

    try:
        session = play_session()
        url = "%s/applications/%s/edits" % (API, DEFAULT_PACKAGE)
        response = session.post(url, timeout=30)
        response.raise_for_status()
        edit_id = response.json()["id"]
        try:
            response = session.get("%s/%s/tracks/internal" % (url, edit_id), timeout=30)
            response.raise_for_status()
            track = response.json()
        finally:
            abandon_edit(session, DEFAULT_PACKAGE, edit_id)
        with open(args.notes, encoding="utf-8") as f:
            notes = f.read()
        error = None
        try:
            validate_internal_release(track, args.version, args.code, notes)
        except ValueError as exc:
            error = str(exc)
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump({"packageName": DEFAULT_PACKAGE, "track": track,
                       "expectedVersion": args.version, "expectedVersionCode": args.code,
                       "verified": error is None, "error": error}, f, indent=2)
            f.write("\n")
        if error:
            print("::error::" + error)
            return 1
        print("Verified Play internal draft %s (versionCode %s) and release notes for %s"
              % (args.version, args.code, DEFAULT_PACKAGE))
        return 0
    except Exception as exc:
        # Never print the credentials or auth library's request/token details.
        response = getattr(exc, "response", None)
        print("::error::Cannot verify Play internal draft: %s%s" % (
            type(exc).__name__, " HTTP %s" % response.status_code if response is not None else ""))
        return 1


def run_ensure_notes(args):
    notes = None
    if os.path.exists(args.notes):
        with open(args.notes, encoding="utf-8", errors="replace") as f:
            notes = f.read()
    ensured, used_fallback = ensure_notes(notes, os.environ.get(args.pr_list_env, ""))
    if used_fallback or ensured != notes:
        with open(args.notes, "w", encoding="utf-8", newline="\n") as f:
            f.write(ensured)
    if used_fallback:
        print("::warning title=Release notes fell back::The notes producer left notes.txt "
              "blank — using the fallback text (the PR titles, or the default line when "
              "there were none) so the release stays shippable.")
        print("Notes:")
        print(ensured)
    elif ensured != notes:
        print("::warning title=Release notes trimmed::notes.txt exceeded Play's %d-byte "
              "what's-new limit and was cut at a line boundary." % PLAY_NOTES_LIMIT)
    return 0


def run_pin(args):
    with open(args.body, encoding="utf-8", errors="replace") as f:
        body = f.read()
    out = pin_from_body(body, args.prev_version, args.source)
    if out.error:
        print("::error::" + out.error)
        return 1
    if out.notes:
        with open(args.notes_out, "w", encoding="utf-8", newline="\n") as f:
            f.write(out.notes if out.notes.endswith("\n") else out.notes + "\n")
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8", newline="\n") as f:
            f.write("found=%s\n" % ("true" if out.found else "false"))
            f.write("version=%s\n" % out.version)
            f.write("notes=%s\n" % ("true" if out.notes else "false"))
    print(out.message)
    if out.notes:
        print("Notes:")
        print(out.notes)
    return 0


def main(argv=None):
    args = build_arg_parser().parse_args(argv)
    if args.command == "verify-internal":
        return run_verify_internal(args)
    if args.command == "ensure-notes":
        return run_ensure_notes(args)
    if args.command == "pin":
        return run_pin(args)

    with open(args.gh_stderr, encoding="utf-8", errors="replace") as f:
        gh_stderr = f.read()
    with open(args.body, encoding="utf-8", errors="replace") as f:
        body = f.read()

    out = decide(args.event, args.tag, args.gh_status, gh_stderr, body)
    if out.error:
        print("::error::" + out.error)
        return 1
    if out.found:
        with open(args.body_out, "w", encoding="utf-8", newline="\n") as f:
            f.write(out.body if out.body.endswith("\n") else out.body + "\n")
        with open(args.notes_out, "w", encoding="utf-8", newline="\n") as f:
            f.write(cap_notes(out.notes))
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8", newline="\n") as f:
            f.write("found=%s\n" % ("true" if out.found else "false"))
    print(out.message)
    if out.found:
        print("Notes:")
        print(out.notes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
