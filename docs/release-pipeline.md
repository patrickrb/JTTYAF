# Release pipeline

JTTYAF is Android-only. The conversion on `dev`/`staging` deliberately removed
desktop/iOS sources and workflows. Legacy `main` still has orphan desktop/iOS
workflows until the normal reviewed promotion removes them; do not restore them.

**Production hold:** no production releases, promotions or rollouts until the
user tests all five apps and subsequently explicitly approves production.
Preserving an explicit tag/manual production route is not permission to use it.

## Branch lifecycle

- **feature → dev** — every work item. CI runs (tests, build check) but nothing
  is published. Dev builds **never** reach the Releases page.
- **dev → staging** — bundle up many dev PRs and promote them together. Merging
  this PR cuts `android-dev.<run#>` with `prerelease:true` and a Play **internal
  draft**. APK version and Console release name are plain `x.y.z`. A draft is
  not tester availability, and does not authorize another track or rollout.
- **staging → main** — promote the validated staging build. Merging this PR (a
  push to `main`) also cuts only `android-dev.<run#>`, `prerelease:true`, Play
  **internal draft**, reusing staging's version and notes without a paid call.
  No branch push creates `android-v*`, a stable GitHub Release, or a production
  Play release. Both upload attempts and the internal read-back use this boundary.
- **Explicit production route — held:** only an `android-v*` **tag push** or
  `workflow_dispatch` on that tag can select stable GitHub/Play production.
  Manual branch runs, PRs, and unsupported events never publish. Existing-tag
  dispatch restores saved release notes and fails closed if they are missing.

Source gates (enforced as required status checks):

- PRs to `staging` must come from `dev` (`Staging branch source gate`).
- PRs to `main` must come from `staging` (`Main branch source gate`).

## Tag / release namespaces

Only `android-dev.<run#>` is automatic on staging/main. `android-v<x.y.z>` is
reserved for deliberate approved production. No active desktop/iOS publisher
exists; `desktop-dev.*` is an allowed internal namespace if a separately
approved desktop target returns, not a reason to restore obsolete workflows.

- Android versions are chosen at internal creation (see below). Older tags are the
  two-part `android-v0.149` form; they normalise to `0.149.0` so numbering stays
  continuous. Android seeds from a legacy bare `v*` tag if no `android-v*`
  exists yet.

## Android versioning + release notes (AI-assisted)

Ported from Sorrel's `play-beta.yml`. On a push to `staging` the Android
`build` job:

1. Takes the latest reachable internal candidate as the staging baseline (its
   SemVer is stored in the release body), or the latest `android-v*` tag when
   there is no candidate, and collects everything
   from there to `HEAD`: merged PRs (number, branch, title), the commit log,
   and size signals (commit count, `jttyaf/` diff stat, changed files by area).
2. If neither `jttyaf/`, `ham-radio-core/` nor the staging publishing/versioning
   workflow/scripts changed, it builds but **cuts no release**. Docs-only
   promotions do not create empty versions.
3. Otherwise asks Claude (`claude-opus-5`, structured output) for the semver
   bump — `major` / `minor` / `patch`, judged from both content and size, with
   changes outside `jttyaf/` not counting — and for ≤400-character Play release
   notes aimed at ham operators.
4. Builds `versionName = <x.y.z>`, tags `android-dev.<run#>`, and
   publishes the notes to the GitHub prerelease body **and** the Play internal
   **draft**'s "Release notes" (`whatsNewDirectory`). The Console release name
   is the same SemVer. The prerelease body also
   carries hidden markers (`<!-- jttyaf-version: x.y.z -->` and
   `<!-- jttyaf-notes-start/end -->`).
5. Uses the existing authorized Play account to read back **only internal**
   through an uncommitted edit, then abandons that read edit. The exact draft
   name, versionCode and English release notes must match. Upload or read-back
   failures fail staging's Android gate; a GitHub Release alone is not a Play
   delivery verdict. The credential-free `play-internal-readback` Actions artifact
   preserves the actual Google track response. A verified draft is not a claim
   of tester rollout, review approval, enrollment or propagation.

To inspect the **current** internal state without another build or upload, run
`Android CI & Release` on the selected branch with `inspect_internal=true`.
This mode skips all build/release jobs and GETs only
`applications/radio.ks3ckc.jttyaf/tracks/internal/releases` using the existing
authorized Play account. No edit is created or committed, so it does not join
the publishing queue or require a publishing pause. Normal production queue
selection, credential grants and merge/review protections remain unchanged.

The `play-internal-current` artifact records Google's release summaries and UTC
read time. The API returns at most 20 non-obsolete releases; absence is not proof
of historical nonexistence. It exposes lifecycle, releaseName and active version
codes, not versionName or exact notes. PUBLISHED can include partial or halted
rollout; a draft is not tester availability. The check does not require a draft,
since a manual Console action may have changed its lifecycle. It never rolls
out or promotes anything; the owner's manual Console rollout hold still applies.
Leave the input false for normal workflow behavior.

On the later push to `main` the job looks for the newest reachable `android-dev.*`
candidate newer than the stable baseline, reads its version and notes from the
markers, and creates another **internal** `android-dev.<run#>` with that SemVer.
If no usable candidate exists, use a deterministic patch bump and PR-title notes
without calling Claude. The run number distinguishes releases without changing
the promoted version or reserving a production tag.

`versionCode` is unchanged: still `GITHUB_RUN_NUMBER + 1000`.

The helper `.github/scripts/android-next-version.sh` does the bump arithmetic
and has a self-test (`--self-test`).

**Secret:** `ANTHROPIC_API_KEY` (repository secret). Without it, or if the API
call fails, the run annotates a warning, takes a **patch** bump, and uses the
PR titles as the notes — a release is never blocked on the AI step.

**Paid-call boundary:** at most one Anthropic request, only on attempt 1 of
internal creation (`staging` push). No API retries or server-side fallback.
Promotions and tag/manual shipping reuse the saved staging/release notes.
Workflow reruns never call Anthropic again: an already-created internal
candidate is the baseline, so an unchanged rerun is build-only and keeps its
release; if creation failed before saving the release, use deterministic
patch/PR-title notes instead. Pinned versions continue to skip generation.
This policy does not authorize any production action; the all-five-app testing
and subsequent explicit production approval hold remains in force.

### Pinning the version or the notes by hand

Write them into the **promotion PR's description** (the dev → staging PR; or
the staging → main PR when there is no staging build to promote), using the
same hidden markers the release bodies carry:

```
<!-- jttyaf-version: 1.0.0 -->
<!-- jttyaf-notes-start -->
Plain-text Play release notes, under 500 bytes.
<!-- jttyaf-notes-end -->
```

Either one may appear alone. A pinned version skips the AI bump step; pinned
notes replace the AI's what's-new. The PR is found from the merge commit
(`commits/<sha>/pulls`), so merge, squash and rebase merges all work. The rules
are enforced by `release_notes.py pin` (unit-tested): the version must be a
plain `x.y.z` **above the previous release version**, and the notes markers
must not be empty — a bad pin fails the run with an `::error::` rather than
silently falling back. On `main` a promoted staging candidate still takes
precedence, preserving the version and notes the internal testers ran.

## Store listings

Play *store listing* text (title, descriptions, per-locale metadata) lives in
`fastlane/metadata/android/` and ships through its own workflow,
`play-listings.yml` — not through the Android release pipeline above, and not
on any branch push. A PR touching the metadata is **validated** (completeness
and Play's character limits) and then the text just sits in the repo; it reaches
the store only when someone runs the publish on purpose:

- **Actions → "Play store listings" → Run workflow**, mode `publish` (the
  default mode is the read-only `dry-run`), or
- locally: `python .github/scripts/publish_listings.py`.

Listing copy never publishes on a branch push; app internal drafts can.

## Safe first landing and workflow audit

1. Independently review and merge the once-only notes PR #20 into `dev` first,
   then the internal-only safeguard PR. Neither feature/dev push publishes.
2. Review the normal **dev → staging** PR with both changes; keep
   `enforce-source-is-dev`, Android gates and required reviews. Its new guarded
   staging workflow may create only an internal prerelease/draft. Wait for its
   CI and actual internal read-back; do not infer tester availability from an artifact.
3. Review the resulting **staging → main** PR, including its entire resulting
   workflow tree. Preserve `enforce-source-is-staging` and Android/review gates.
   The `push` event uses the pushed tip's workflow, not the old main definition
   ([GitHub workflow trigger rules](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows#workflow-triggers)).
   The same tree carries the Android guard and the already-approved conversion's
   desktop/iOS workflow deletions. There is no intermediate unguarded main push.
   Recheck this against the actual reviewed merge tree if any branch advances.
4. Do not merge the orphan-iOS draft #21 directly or waive its source gate. The
   normal promotion already removes that orphan; #21 can then be superseded.
   Patrick must inspect effective **main** rules and retire `iOS CI / ios-gate`
   only if still required (and an orphan `desktop-gate` if required). Exposed
   main checks currently show only Android/source gates; classic protection
   reads return 403, so inaccessible exact rule IDs remain unverified.
5. Existing queued/in-flight runs retain their old definitions; do not rerun or
   cancel them. Before an owner lands promotion, inspect active old publishers
   and Play edit activity. The scheduled watcher has a separate concurrency key
   and may collide with a publisher's Play edit; it cannot commit a release.

| Workflow | Main-push effect after normal promotion |
| --- | --- |
| Android CI & Release | Only `android-dev.*`, prerelease true, Play internal draft; paid generation skipped on main/retry |
| Legacy desktop / iOS | Absent from dev/staging and the promoted tree; old main desktop's automatic stable release is not retained |
| Discord merge notify | Notification only; main/staging labeled internal, never a delivery verdict |
| Discord Play track watch | Schedule/manual only; reads tracks via an abandoned edit, writes state branch/Discord, never commits an edit or updates tracks |
| Play store listings | PR validation/manual only; no branch-push publication |
| Agent instructions / native tests | Checks only |
| Main / staging source gates | PR checks only; source requirements unchanged |

All ten legacy-main workflow definitions and eight promoted definitions were
inspected, including their scripts: no `workflow_run`, `workflow_call`, release
event, repository-dispatch or workflow-dispatch publisher chain exists.

## Executed release-policy checks

`python3 -m unittest discover -s .github/scripts -p 'test_*.py'` executes the
checked-in YAML's lane, tag and pre-publication Bash against 70 event/ref pairs
in temporary Git repositories; no credentials, network or real publication.

| Event/ref | Expected publication |
| --- | --- |
| PR (including tag-shaped refs) | None |
| Push dev / feature / lookalike branches | None |
| Push staging / main, changed app | `android-dev.61`, prerelease true, internal draft |
| Push staging / main, unchanged app | None |
| Push / manual dispatch on `android-v2.3.4` tag | Deliberate production route retained, not exercised |
| Internal/desktop tags, manual branch runs | None |
| workflow_run / release / schedule / repository_dispatch | None, even on production-shaped refs |

Additional checks cover saved asymmetric SemVer, patch/pin fallback, ten invalid
branch publication tuples, both Play upload status expressions, retired
publishers/chains and 120 once-only paid-call contexts. Baseline controls execute
old Android main routing and old desktop main tag selection: automatic stable
main behavior must fail the new internal-only expectations.
