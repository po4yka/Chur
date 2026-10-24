<!-- benjamin-plus: JetBrains/benjamin-plus-skill @ 0f7b2df (2026-08-25), injected-instruction.md verbatim
except rule 1's example, which quotes the `==` labels: zsh expands an unquoted `==` and the whole command fails.
Update: re-copy from https://github.com/JetBrains/benjamin-plus-skill/blob/main/injected-instruction.md -->

BENJAMIN-PLUS MODE ACTIVE

# Benjamin-Plus

Every request you send re-reads the whole conversation so far. The bill is
steps × context, not words. Save by taking fewer steps and keeping bulky tool
output out of the transcript — never by skimping on the work itself. Solve the
task exactly as you otherwise would; these rules change how you look things
up, not what you build.

**1. Recon in one pass.**
Before changing anything, collect every independent fact in a single step:
chain probes with `;` and label the sections
(`echo '== layout =='; ls -la; echo '== deps =='; head -30 requirements.txt`),
or issue several tool calls in one message. A second lookup round is for
questions the first round's answers created. Copying a convention (a DSL,
schema, or file format)? Sample two existing examples of the exact construct
you will write, not one.

**2. Look through a keyhole.**
A command that only inspects ends with a limiter: `| head -50`, `| tail -20`,
`grep -m 20`, `wc -l` before contents, Read with offset/limit. Size unknown?
Measure first, then read the slice you need. Read a file whole only when you
are about to edit it or copy from it verbatim — truncating data you will
transform corrupts output, so keyhole rules apply to inspection, never to
ingestion. If a peek was too narrow, take exactly one wider look.

**3. Probe the environment once.**
Before running code with several dependencies, check them all in one probe
and install everything missing in one command — never one traceback at a
time. A plain `import x, y, z` stops at the first missing module, so check
each one:
`python3 -c "import importlib.util as u; [print(m) for m in ['x','y','z'] if not u.find_spec(m)]"`
and `command -v tool1 tool2` for binaries.

**4. Green means the task's own check.**
If the task names verification commands, those are the check: run them
exactly as written, and green means exit status zero. A failure you judge
environmental (missing package, compiler, or tool) is still your failure —
fix the environment and re-run; "unrelated to my change" is not a green
check. The same check failing twice on the same approach means the approach
is wrong: name one alternative and try it before patching the next symptom.
When the check passes, stop: no victory laps, no re-reading files you just
wrote. Close with at most two lines.

**5. Polling is a step.**
A running command that hasn't finished is not new information — but every
status check re-reads the whole conversation. If your harness returns while a
command is still running, wait in large slices (30 seconds or more; minutes
for builds and test suites) before checking again. Never re-poll at
one-second intervals, and never send empty input just to peek. Where
execution blocks until completion, this rule costs nothing.

Never build a verification harness, test suite, or checker the task didn't
ask for — verify stated properties with the shortest command that measures
them, and spend the saved steps on the task itself. If saving a step risks a
wrong result, spend the step: efficiency never outranks correctness, a
failing check, or anything the task explicitly asks you to produce.

<!-- ponytail: DietrichGebert/ponytail. The hooks in .agents/hooks (hooks-lock.json) inject its ruleset at session
start and into subagents: .claude/settings.json wires them for Claude Code, .codex/hooks.json for Codex (trust them
once in /hooks). The six ponytail skills are in .agents/skills (skills-lock.json). MIT License, Copyright (c) 2026
DietrichGebert: see .agents/NOTICE.md. Update: npx skills add DietrichGebert/ponytail --skill '*' --agent claude-code
--agent codex --yes, copy the locked files from upstream hooks/, then run scripts/check-vendored-skills.py --write
--clones <dir> and refresh the hashes in hooks-lock.json. -->

# Chur investigation

- Locate before you read: `rg -n`, `git grep -n`, or LSP symbols, then read the slice. `ChurController.kt`, `ChurRoutes.kt`, `VaultShell.kt`, and `DESIGN.md` are each 1,500 to 3,800 lines. A slice or search result stays valid until a file it covers changes; do not read it again.
- Check visual state with the UI tree or a pixel diff first. Read a screenshot only for what text cannot show; downscale it first (`sips -Z 1000 in.png --out small.png`) and crop it to the changed region.
- Delegate only independent, substantial work. Put the paths and the still-valid findings in the brief, so that the subagent does not inventory the repository again. Review a patch once; a follow-up review reads only the fix diff.
- The `sccache` wrapper from the user's environment fails in a sandboxed `TMPDIR` ("Failed to create temp dir"). There, run Cargo with `RUSTC_WRAPPER= CARGO_BUILD_RUSTC_WRAPPER=`.

# Chur checks

- These rules and "Chur tests" take precedence over the vendored skills in `.agents/skills` and the Ponytail text that the hooks inject. Keep the vendored files verbatim, so that `scripts/check-vendored-skills.py --verify-upstream` can prove their provenance, and change the policy here.
- Batch the related edits, then validate. Iterate with the narrowest check that covers the edit: `cargo test --locked -p <crate> <filter>`, or one Gradle module task with `--tests`. Choose the final checks from the path table in `DEVELOPMENT.md` "Build commands"; a workspace-wide gate runs once, after the last edit, only when the change reaches across modules. CI runs the full set on every pull request, so do not repeat it locally. These checks are the task's own check of Benjamin-Plus rule 4: when they pass, stop.
- The workspace gate in the `rust-lints` skill "Verification" applies only to the lint and toolchain row of the path table; CI runs it before a merge. The `cargo-workflows` and `rust-native-linking` skills that it names are not in this repository.
- A passing check stays valid until its inputs change: an edit under the paths it covers, a checkout, or a dependency or toolchain change. Do not re-run it to reconfirm; re-run a suspected flaky test only with the reason stated. No `clean`, `cargo clean`, or `--rerun-tasks` unless a stale output is the observed fault.
- Record what you checked in the commit message or pull request description. Add a dated file under `docs/assurance/` only for a release-gate evidence package, a security or data-at-rest audit, or when the user asks for one.

# Chur tests

- Add a test only for a behavior or regression that no existing test fails on. Search the module's tests first and extend the nearest one. This is how Ponytail's "one small test or an assert-based self-check" for new non-trivial logic applies: an existing test that fails when the logic breaks satisfies it, a trivial change or a deletion needs none, and the test is part of the change, not the extra harness that Benjamin-Plus forbids.
- Assert on observable output or state of the real code. Expected values are literals, vectors, or an independent oracle, never the code under test or a copy of its logic. A fake stands in for a platform boundary; it is not the thing asserted.
- No test reads source text or reaches private members by name when a public call or the compiler can check the same thing. No production interface, parameter, or visibility change only to test trivial wiring.
- The same rules apply to throwaway assertions, Ponytail's assert-based self-checks, and smoke scripts: delete them after use, or put them in a suite that CI runs. A script nothing runs is not coverage.
