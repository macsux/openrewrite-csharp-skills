# rewrite-skills

Local-development scaffolding for OpenRewrite / Moderne CLI work in Conductor: the workspace setup
script, the overlays it applies, and the guidance and skills it copies into each worktree. Nothing
here is shipped or consumed at runtime — it exists so a freshly created Conductor workspace is
immediately usable across the CLI, the rewrite SDK, and the recipes repos.

## Editing CLAUDE.local.md

`CLAUDE.local.md` is the instruction file this repo installs into those worktrees. It is a
HOW-TO-USE document, not a design document.

Keep every edit brief: practical tips on how to use something, plus a high-level note on how the
pieces are coupled (if necessary - do not include for obvious stuff). That is all it needs. Do NOT add implementation details, rationale, trade-offs,
or explanations of how something works internally — that belongs with the code. Padding this file
with mechanism is the single most common mistake made here; when in doubt, cut it.

If you change `CLAUDE.local.md` during a turn, you MUST print a git patch of that change to the
console in the same response, so it can be reviewed and pushed back on before it sticks.
