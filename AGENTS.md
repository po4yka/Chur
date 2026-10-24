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

# Chur checks

- Iterate with the narrowest check that covers the edit: `cargo test --locked -p <crate> <filter>`, or one Gradle module task with `--tests`. Run each workspace-wide gate (`cargo clippy --workspace`, `cargo test --workspace`, `./gradlew jvmTest testAndroidHostTest`, the Android assemble and lint) once, after the last edit, and only for the code the change touches. `DEVELOPMENT.md` "Build commands" lists them; `.github/workflows/rust.yml` is the source of truth.
- Ponytail's one small test for new non-trivial logic is part of the change, not the extra harness Benjamin-Plus forbids.

# Chur tests

- Add a test only for a behavior or regression that no existing test fails on. Search the module's tests first and extend the nearest one.
- Assert on observable output or state of the real code. Expected values are literals, vectors, or an independent oracle, never the code under test or a copy of its logic. A fake stands in for a platform boundary; it is not the thing asserted.
- No test reads source text or reaches private members by name when a public call or the compiler can check the same thing. No production interface, parameter, or visibility change only to test trivial wiring.
- The same rules apply to throwaway assertions and smoke scripts: delete them after use, or put them in a suite that CI runs. A script nothing runs is not coverage.
