#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
REPOSITORY=${GITHUB_REPOSITORY:-Leenkee/ShanRuanDrinkWater}
WORKFLOW=android-release.yml
ASSUME_YES=false

if [[ ${1:-} == "--yes" ]]; then
    ASSUME_YES=true
elif [[ $# -gt 0 ]]; then
    echo "用法：./scripts/publish-release.sh [--yes]" >&2
    exit 2
fi

for command_name in git gh; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "缺少命令：$command_name" >&2
        exit 1
    fi
done

cd "$PROJECT_DIR"

VERSION_NAME=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
if [[ -z "$VERSION_NAME" ]]; then
    echo "无法从 app/build.gradle.kts 读取 versionName。" >&2
    exit 1
fi

if [[ $(git branch --show-current) != "main" ]]; then
    echo "请先切换到 main 分支再发布。" >&2
    exit 1
fi

if [[ -n $(git status --porcelain) ]]; then
    echo "工作区存在未提交修改，请先提交。" >&2
    exit 1
fi

echo "正在确认 main 与远端同步……"
git fetch origin main
LOCAL_COMMIT=$(git rev-parse HEAD)
REMOTE_COMMIT=$(git rev-parse origin/main)
if [[ "$LOCAL_COMMIT" != "$REMOTE_COMMIT" ]]; then
    echo "本地 main 与 origin/main 不一致，请先推送或同步代码。" >&2
    exit 1
fi

TAG_NAME="v$VERSION_NAME"
if gh release view "$TAG_NAME" --repo "$REPOSITORY" >/dev/null 2>&1; then
    echo "GitHub Release $TAG_NAME 已存在，请先递增版本号。" >&2
    exit 1
fi

if [[ "$ASSUME_YES" != true ]]; then
    read -r -p "确认通过 GitHub Actions 发布 $TAG_NAME？[y/N] " ANSWER
    if [[ ! "$ANSWER" =~ ^[Yy]$ ]]; then
        echo "已取消。"
        exit 0
    fi
fi

echo "正在触发 $TAG_NAME 的发布工作流……"
CHANGE_SUMMARY=$(git log -1 --pretty=%s)
gh workflow run "$WORKFLOW" \
    --repo "$REPOSITORY" \
    --ref main \
    -f publish_release=true \
    -f release_version="$VERSION_NAME" \
    -f change_summary="$CHANGE_SUMMARY"

echo "发布任务已提交。可在本地运行以下命令查看进度："
echo "gh run list --repo $REPOSITORY --workflow $WORKFLOW --limit 1"
