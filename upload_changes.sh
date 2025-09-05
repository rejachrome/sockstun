#!/usr/bin/env bash
set -euo pipefail

# Script to push local changed files to a new branch using gh API (no local git commit)
# Usage: ./upload_changes.sh hotspot-vpn-compat "commit message"

BRANCH=${1:-hotspot-vpn-compat}
COMMIT_MSG=${2:-"hotspot-vpn-compat: upload local edits via gh API"}

# Detect repo owner/name via gh
REPO_FULL=$(gh repo view --json nameWithOwner --jq .nameWithOwner) || { echo "gh repo view failed"; exit 1; }
OWNER=$(printf '%s' "$REPO_FULL" | cut -d'/' -f1)
REPO=$(printf '%s' "$REPO_FULL" | cut -d'/' -f2)

echo "Repo: $OWNER/$REPO"
echo "Target branch: $BRANCH"

# Get default branch and latest commit SHA
DEFAULT_BRANCH=$(gh api repos/"$OWNER"/"$REPO" --jq '.default_branch')
echo "Default branch: $DEFAULT_BRANCH"

# Get SHA of default branch
BASE_SHA=$(gh api repos/"$OWNER"/"$REPO"/git/ref/heads/"$DEFAULT_BRANCH" --jq '.object.sha')
echo "Base SHA: $BASE_SHA"

# Create new branch ref pointing to base SHA (if exists, continue)
BRANCH_REF="refs/heads/$BRANCH"
if gh api repos/"$OWNER"/"$REPO"/git/ref/heads/"$BRANCH" >/dev/null 2>&1; then
  echo "Branch $BRANCH already exists; will overwrite its ref (force update)."
  gh api -X PATCH repos/"$OWNER"/"$REPO"/git/refs/heads/"$BRANCH" -f sha="$BASE_SHA" >/dev/null
else
  echo "Creating branch $BRANCH"
  gh api repos/"$OWNER"/"$REPO"/git/refs -f ref="$BRANCH_REF" -f sha="$BASE_SHA" >/dev/null
fi

# Collect changed files relative to HEAD (staged or unstaged)
CHANGED_FILES=$(git status --porcelain | awk '{print $2}' | sed 's/^.\///' | sed '/^$/d')
if [ -z "$CHANGED_FILES" ]; then
  echo "No changed files detected by git status."
  exit 0
fi

echo "Changed files:"
printf '%s\n' $CHANGED_FILES

# Create blobs for each file and build tree entries
TREE_ENTRIES=()
for f in $CHANGED_FILES; do
  if [ ! -f "$f" ]; then
    echo "Skipping removed or non-regular file: $f"
    continue
  fi
  echo "Uploading blob for $f"
  # Create blob, get sha
  BLOB_SHA=$(gh api repos/"$OWNER"/"$REPO"/git/blobs -f "content=$(base64 -w 0 "$f")" -f encoding=base64 --jq .sha)
  # Determine file mode and type
  MODE="100644"
  TYPE="blob"
  # Create tree entry line (we'll build JSON array)
  TREE_ENTRIES+=("{\"path\":\"$f\",\"mode\":\"$MODE\",\"type\":\"$TYPE\",\"sha\":\"$BLOB_SHA\"}")
done

# Build JSON array for tree
TREE_JSON=$(printf '%s\n' "${TREE_ENTRIES[@]}" | jq -s '.')

# Create a new tree based on base SHA with our modified files
echo "Creating tree..."
TREE_RESPONSE=$(gh api repos/"$OWNER"/"$REPO"/git/trees -f "base_tree=$BASE_SHA" -F tree="$TREE_JSON")
NEW_TREE_SHA=$(echo "$TREE_RESPONSE" | jq -r .sha)
echo "New tree sha: $NEW_TREE_SHA"

# Create commit
echo "Creating commit..."
COMMIT_RESPONSE=$(gh api repos/"$OWNER"/"$REPO"/git/commits -f message="$COMMIT_MSG" -f tree="$NEW_TREE_SHA" -f parents[]="$BASE_SHA")
NEW_COMMIT_SHA=$(echo "$COMMIT_RESPONSE" | jq -r .sha)
echo "New commit sha: $NEW_COMMIT_SHA"

# Update branch ref to point to new commit
echo "Updating branch ref to new commit..."
gh api -X PATCH repos/"$OWNER"/"$REPO"/git/refs/heads/"$BRANCH" -f sha="$NEW_COMMIT_SHA" >/dev/null

echo "Done. Pushed changes to branch $BRANCH."
echo "Create a PR on GitHub if you want to merge into default branch."
