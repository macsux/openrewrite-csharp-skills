#!/usr/bin/env bash
# Conductor workspace setup for moderne-cli.
# Configured in Conductor: Settings → Repos → moderne-cli → Run script.
#
# This script lives in the rewrite-skills repo alongside everything it copies,
# so every source below is resolved relative to the script's own location and
# nothing outside the repo is hardcoded:
#
#   scripts/moderne-cli-setup.sh  this script
#   scripts/.local/               copied verbatim to <workspace>/.local/
#   scripts/moderne-cli/          tracked-file overlays, keyed by repo-relative path
#   project-overlay/              copied to <workspace>/ and locally excluded
#
# Idempotent: safe to run on a workspace that's already been set up.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILLS_REPO="$(cd "$SCRIPT_DIR/.." && pwd)"

OVERLAY_SRC="$SCRIPT_DIR/moderne-cli"
LOCAL_SRC="$SCRIPT_DIR/.local"
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

TOML="$REPO_ROOT/gradle/libs.versions.toml"

if [[ ! -f "$TOML" ]]; then
  echo "setup: $TOML not found — skipping" >&2
  exit 0
fi

# If skip-worktree is already set, clear it so sed can write cleanly,
# then re-set it at the end. (sed -i works either way, but this keeps
# git's view of the index consistent during the edit.)
cd "$REPO_ROOT"
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
    echo "$MARKER_END"
  } >> "$EXCLUDE_FILE"

  echo "setup: copied project-overlay and added entries to .git/info/exclude"
fi

# ---------------------------------------------------------------------------
# Copy the shared .local overlay into the workspace so the CLI in this worktree
# starts from a known-good local config (e.g. gradle init scripts, Moderne CLI
# home).
# ---------------------------------------------------------------------------
MODERNE_CLI_HOME="$REPO_ROOT/.local/.moderne/cli"
export MODERNE_CLI_HOME

if [[ -d "$LOCAL_SRC" ]]; then
  mkdir -p "$REPO_ROOT/.local"
  cp -R "$LOCAL_SRC/." "$REPO_ROOT/.local/"
  echo "setup: copied $LOCAL_SRC into $REPO_ROOT/.local"
fi

mkdir -p "$MODERNE_CLI_HOME"
for f in moderne.yml recipes-v5.csv; do
  src="$HOME/.moderne/cli/$f"
  if [[ -f "$src" ]]; then
    cp "$src" "$MODERNE_CLI_HOME/$f"
    echo "setup: copied $f to $MODERNE_CLI_HOME"
  else
    echo "setup: $src not found — skipping" >&2
  fi
done
