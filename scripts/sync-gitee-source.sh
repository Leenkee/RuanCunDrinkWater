#!/usr/bin/env bash
set -euo pipefail

: "${GITEE_REPOSITORY:?Set GITEE_REPOSITORY}"
: "${GITEE_TOKEN:?Set GITEE_TOKEN}"
if [[ ! "$GITEE_REPOSITORY" =~ ^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$ ]]; then
    echo "GITEE_REPOSITORY must be owner/repository" >&2
    exit 1
fi

ASKPASS_FILE=$(mktemp)
trap 'rm -f "$ASKPASS_FILE"' EXIT
cat > "$ASKPASS_FILE" <<'ASKPASS'
#!/usr/bin/env bash
case "$1" in
    *Username*) printf '%s\n' "${GITEE_REPOSITORY%%/*}" ;;
    *Password*) printf '%s\n' "$GITEE_TOKEN" ;;
esac
ASKPASS
chmod 700 "$ASKPASS_FILE"
export GIT_ASKPASS="$ASKPASS_FILE"
export GIT_TERMINAL_PROMPT=0

git fetch origin main --no-tags
SOURCE_COMMIT=$(git rev-parse refs/remotes/origin/main)
if ! git -c credential.helper= push "https://gitee.com/$GITEE_REPOSITORY.git" \
    refs/remotes/origin/main:refs/heads/main; then
    # Two workflows can create/update the same branch concurrently.
    REMOTE_COMMIT=$(git -c credential.helper= ls-remote "https://gitee.com/$GITEE_REPOSITORY.git" refs/heads/main | cut -f1)
    if [[ "$REMOTE_COMMIT" != "$SOURCE_COMMIT" ]]; then
        echo "Gitee main differs from GitHub main; refusing to overwrite it" >&2
        exit 1
    fi
    echo "Gitee main was already synchronized by another workflow"
fi
python3 scripts/sync-gitee-release.py --source-commit "$SOURCE_COMMIT"
