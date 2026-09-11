#!/usr/bin/env bash
# Conductor workspace setup for moderne-cli.
# Configured in Conductor: Settings → Repos → moderne-cli → Run script.
#
# This script lives in the rewrite-skills repo alongside everything it copies,
# so every source below is resolved relative to the script's own location and
# nothing outside the repo is hardcoded:
#
#   scripts/moderne-cli-setup.sh  this script
#   scripts/moderne-cli/          tracked-file overlays, keyed by repo-relative path
#   project-overlay/              copied to <workspace>/ and locally excluded,
#                                 including project-overlay/.local/ -> <workspace>/.local/
#
# Idempotent: safe to run on a workspace that's already been set up.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILLS_REPO="$(cd "$SCRIPT_DIR/.." && pwd)"

OVERLAY_SRC="$SCRIPT_DIR/moderne-cli"
SKILLS_SRC="$SKILLS_REPO/project-overlay"

# Resolve the target workspace from wherever Conductor invoked us. Prefer the
# env var Conductor sets; fall back to git so the script also works if run
# manually from inside the worktree.
REPO_ROOT="${CONDUCTOR_WORKSPACE_PATH:-$(git rev-parse --show-toplevel)}"

# Guard that fallback: run from inside rewrite-skills with no env var set, git
# would resolve REPO_ROOT to this repo and we would overlay it onto itself.
if [[ "$REPO_ROOT" == "$SKILLS_REPO" ]]; then
  echo "setup: refusing to overlay $SKILLS_REPO onto itself — run from the target" \
       "workspace, or set CONDUCTOR_WORKSPACE_PATH" >&2
  exit 1
fi

cd "$REPO_ROOT"

# The version pin below only applies to moderne-cli. Other repos set up by this
# script (e.g. a rewrite worktree, which has no gradle/libs.versions.toml) skip
# it and go straight to the overlays — this must not abort the run.
TOML="$REPO_ROOT/gradle/libs.versions.toml"

if [[ -f "$TOML" ]]; then
  # If skip-worktree is already set, clear it so sed can write cleanly,
  # then re-set it at the end. (sed -i works either way, but this keeps
  # git's view of the index consistent during the edit.)
  if git ls-files -v -- "$TOML" | grep -q '^S'; then
    git update-index --no-skip-worktree -- "$TOML"
  fi

  # Targeted line edits. Anchored to the start of the line + key name so we
  # only touch the intended coordinates, not every "latest.release" in the file.
  sed -i '' \
    -e 's|^rewrite-version = "latest\.release"|rewrite-version = "latest.integration"|' \
    -e 's|^\(rewrite-csharp = "org\.openrewrite:rewrite-csharp:\)latest\.release"|\1latest.integration"|' \
    "$TOML"

  git update-index --skip-worktree -- "$TOML"

  echo "setup: $TOML pinned to latest.integration and marked skip-worktree"
else
  echo "setup: no gradle/libs.versions.toml — skipping the version pin" >&2
fi

# ---------------------------------------------------------------------------
# Overlay tracked source files that carry local-dev-only changes. Each is
# copied from scripts/moderne-cli/ into the workspace, then marked
# skip-worktree so the local edits never get staged, committed, or pushed.
# (Same mechanism as the libs.versions.toml flip above; these are tracked
# files so skip-worktree is the right tool, not .git/info/exclude.)
# ---------------------------------------------------------------------------
OVERLAY_FILES=(
  "core/serialization/build.gradle.kts"
)
for rel in "${OVERLAY_FILES[@]}"; do
  src="$OVERLAY_SRC/$rel"
  dst="$REPO_ROOT/$rel"
  if [[ ! -f "$src" ]]; then
    echo "setup: overlay source $src not found — skipping" >&2
    continue
  fi
  if [[ ! -f "$dst" ]]; then
    echo "setup: overlay target $dst not found in worktree — skipping" >&2
    continue
  fi
  # Clear skip-worktree (if set) so cp can write through git's view cleanly,
  # copy the overlay in, then re-set skip-worktree.
  if git ls-files -v -- "$rel" | grep -q '^S'; then
    git update-index --no-skip-worktree -- "$rel"
  fi
  cp "$src" "$dst"
  git update-index --skip-worktree -- "$rel"
  echo "setup: overlaid $rel and marked skip-worktree"
done

# ---------------------------------------------------------------------------
# Hide tracked files that a source-linked build rewrites in place.
#
# These are not overlays -- nothing is copied over them. They are checked-in
# BUILD OUTPUTS that the local setup legitimately regenerates with different
# content, leaving `git status` permanently dirty and inviting an accidental
# commit of local-only state.
#
# tree-registry.json is the case in hand: local-rewrite.init.gradle.kts adds
# rewrite-mainframe/rewrite-ruby to :core:serialization's compile classpath (the
# source-linked SDK does not expose them the way the published jars do), so
# codegen sees those LST types and registers them. Correct locally, meaningless
# upstream.
#
# skip-worktree, not .git/info/exclude: exclude has no effect on tracked files.
# The trade-off is the usual one -- a pull that touches one of these will report
# a conflict until skip-worktree is cleared for it.
# ---------------------------------------------------------------------------
LOCALLY_REGENERATED_FILES=(
  "core/serialization/src/codegen/resources/tree-registry.json"
)
for rel in "${LOCALLY_REGENERATED_FILES[@]}"; do
  # Must be TRACKED: update-index --skip-worktree errors out on anything else,
  # and this script runs with `set -e` during workspace creation.
  if [[ -z "$(git ls-files -- "$rel")" ]]; then
    echo "setup: $rel not tracked in this repo — skipping" >&2
    continue
  fi
  if git ls-files -v -- "$rel" | grep -q '^S'; then
    continue
  fi
  git update-index --skip-worktree -- "$rel"
  echo "setup: marked $rel skip-worktree (regenerated by source-linked builds)"
done

# ---------------------------------------------------------------------------
# Copy project-overlay/ into the workspace and exclude its files locally so
# they never show up in `git status` or get committed. .git/info/exclude is
# per-clone and not tracked — the right home for this.
# ---------------------------------------------------------------------------
EXCLUDE_FILE="$(git -C "$REPO_ROOT" rev-parse --git-path info/exclude)"
MARKER_BEGIN="# >>> moderne-cli-setup: rewrite-skills overlay >>>"
MARKER_END="# <<< moderne-cli-setup: rewrite-skills overlay <<<"

if [[ -d "$SKILLS_SRC" ]]; then
  # --exclude='.git': a Conductor worktree's .git is a FILE (a gitdir pointer),
  # so copying a .git DIRECTORY over it fails with ".git: Not a directory" and
  # would clobber the worktree's git.
  #
  # --exclude='/.gitignore': .gitignore is a TRACKED file in the target repo, so
  # overwriting it leaves a real `M .gitignore` in every workspace and discards
  # the repo's own ignores (build/, .gradle, working-*/, ...), burying git status
  # in untracked build output. info/exclude cannot mask that — it has no effect
  # on tracked files. Local-only ignores belong in the managed block below.
  rsync -a --exclude='.git' --exclude='/.gitignore' "$SKILLS_SRC/" "$REPO_ROOT/"

  mkdir -p "$(dirname "$EXCLUDE_FILE")"
  touch "$EXCLUDE_FILE"

  # Drop any previous managed block, then re-append a fresh one reflecting
  # whatever is currently in the overlay source.
  sed -i '' "\|$MARKER_BEGIN|,\|$MARKER_END|d" "$EXCLUDE_FILE"
  {
    echo "$MARKER_BEGIN"
    (cd "$SKILLS_SRC" && find . -type f -not -path './.git/*' -not -path './.gitignore') |
      sed 's|^\./||'
    # Also covers what the steps below generate under .local/ (.moderne/cli/*),
    # which has no counterpart in the overlay source.
    echo ".local/"
    echo "$MARKER_END"
  } >> "$EXCLUDE_FILE"

  echo "setup: copied project-overlay and added entries to .git/info/exclude"
fi

# ---------------------------------------------------------------------------
# Expand environment references in the workspace copy of settings.local.json.
# The overlay source keeps them as $CONDUCTOR_WORKSPACE_PATH placeholders so it
# stays machine-independent, but Claude Code does not expand variables in
# settings env values — it hands them to tools verbatim, so an unexpanded
# placeholder becomes a literal path segment and everything reading the
# variable silently gets a nonexistent path.
#
# Only this file is expanded: CLAUDE.local.md deliberately shows the same
# variables as literal shell examples and must not be rewritten.
# ---------------------------------------------------------------------------
SETTINGS="$REPO_ROOT/.claude/settings.local.json"

if [[ -f "$SETTINGS" ]]; then
  # Also makes a manual run work, where Conductor has not set this itself.
  CONDUCTOR_WORKSPACE_PATH="$REPO_ROOT" python3 - "$SETTINGS" <<'PY'
import json, os, re, sys

path = sys.argv[1]
with open(path) as fh:
    doc = json.load(fh)

missing = set()
pattern = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")

def expand(text):
    def sub(match):
        name = match.group(1) or match.group(2)
        value = os.environ.get(name)
        if value is None:
            missing.add(name)
            return match.group(0)
        return value
    return pattern.sub(sub, text)

def walk(node):
    if isinstance(node, dict):
        return {k: walk(v) for k, v in node.items()}
    if isinstance(node, list):
        return [walk(v) for v in node]
    if isinstance(node, str):
        return expand(node)
    return node

expanded = walk(doc)

if missing:
    sys.exit("setup: unset variable(s) referenced by settings.local.json: "
             + ", ".join(sorted(missing)))

with open(path, "w") as fh:
    json.dump(expanded, fh, indent=2)
    fh.write("\n")
PY
  echo "setup: expanded environment references in $SETTINGS"
fi

# ---------------------------------------------------------------------------
# .local/ itself arrives with the overlay above (project-overlay/.local/). What
# is left is the machine-specific CLI config that cannot be checked in.
# ---------------------------------------------------------------------------
MODERNE_CLI_HOME="$REPO_ROOT/.local/.moderne/cli"
export MODERNE_CLI_HOME

# Only moderne.yml is copied. It carries machine-level configuration this workspace cannot
# reconstruct -- licence, tenant, and the artifact repositories recipe resolution needs
# (including file:$HOME/.m2/repository, which is what lets locally pTML'd SNAPSHOTs resolve).
#
# recipes-v5.csv is deliberately NOT copied any more. It is a recipe CATALOGUE holding absolute
# DLL paths into whichever worktree registered them last, so a copy of the global one seeded
# every workspace with rows pointing at other people's worktrees -- routinely deleted ones,
# which `mod run` then dies resolving. The catalogue is now built from scratch, from the
# artifacts this workspace actually has, by the :devRecipesRegister Gradle task.
mkdir -p "$MODERNE_CLI_HOME"
for f in moderne.yml; do
  src="$HOME/.moderne/cli/$f"
  if [[ -f "$src" ]]; then
    cp "$src" "$MODERNE_CLI_HOME/$f"
    echo "setup: copied $f to $MODERNE_CLI_HOME"
  else
    echo "setup: $src not found — skipping" >&2
  fi
done
