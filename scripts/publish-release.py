#!/usr/bin/env python3
"""Build, verify and upload the same local signed APK to GitHub and GitCode."""

import argparse
from contextlib import contextmanager
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
GITHUB_REPOSITORY = "Leenkee/RuanCunDrinkWater"
GITCODE_REPOSITORY = "weixin_62215579/RuanCunDrinkWater"
APPLICATION_ID = "com.young.waterremote"
CERTIFICATE_SHA256 = "402f7c6af57d5f18b8300ec75840bbbf180e04d05b8e0ce0e6514ef25896ffa5"
SPEC = importlib.util.spec_from_file_location("gitcode_release_sync", ROOT / "scripts/sync-gitcode-release.py")
gitcode = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gitcode)


class PublishError(Exception):
    pass


def run(command, env=None, capture=False, check=True):
    result = subprocess.run([str(part) for part in command], cwd=ROOT, env=env,
                            text=True, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if check and result.returncode:
        raise PublishError(f"命令执行失败：{Path(str(command[0])).name}（退出码 {result.returncode}）")
    return result.stdout.strip() if capture and check else result


def project_version(text):
    name = re.search(r'^\s*versionName = "(\d+(?:\.\d+)+)"', text, re.MULTILINE)
    code = re.search(r'^\s*versionCode = (\d+)', text, re.MULTILINE)
    if not name or not code:
        raise PublishError("无法读取正式版 versionName / versionCode")
    return name.group(1), int(code.group(1))


def require_clean_main():
    if run(["git", "branch", "--show-current"], capture=True) != "main":
        raise PublishError("请先切换到 main 分支")
    if run(["git", "status", "--porcelain"], capture=True):
        raise PublishError("工作区存在未提交修改，请先提交")
    return run(["git", "rev-parse", "HEAD"], capture=True)


def local_environment(require_signing=True):
    env = dict(os.environ)
    if not env.get("JAVA_HOME"):
        java_home = Path.home() / "AndroidStorage/android-studio/jbr"
        if java_home.is_dir():
            env["JAVA_HOME"] = str(java_home)
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    env.setdefault("GITCODE_REPOSITORY", GITCODE_REPOSITORY)
    if not require_signing:
        return env
    names = ["ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"]
    if any(env.get(name) for name in names):
        if not all(env.get(name) for name in names):
            raise PublishError("自定义签名需要完整配置 ANDROID_KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD")
    else:
        key = Path.home() / ".android/debug.keystore"
        if not key.is_file():
            raise PublishError("未找到本地签名密钥，请配置 ANDROID_KEYSTORE_* 和 ANDROID_KEY_*")
        env.update(ANDROID_KEYSTORE_PATH=str(key), ANDROID_KEYSTORE_PASSWORD="android",
                   ANDROID_KEY_ALIAS="androiddebugkey", ANDROID_KEY_PASSWORD="android")
    return env


def verify_signing_key(env):
    keytool = shutil.which("keytool", path=env.get("PATH"))
    if not keytool:
        raise PublishError("缺少 keytool，请配置 JAVA_HOME")
    result = subprocess.run([keytool, "-exportcert", "-keystore", env["ANDROID_KEYSTORE_PATH"],
                             "-alias", env["ANDROID_KEY_ALIAS"], "-storepass:env", "ANDROID_KEYSTORE_PASSWORD"],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise PublishError("无法读取签名证书，请检查本地密钥和密码配置")
    if hashlib.sha256(result.stdout).hexdigest() != CERTIFICATE_SHA256:
        raise PublishError("本地签名证书与已有版本不一致，无法发布覆盖升级包")


def android_tools(env):
    roots = [env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT")]
    properties = ROOT / "local.properties"
    if properties.is_file():
        match = re.search(r"^sdk.dir=(.+)$", properties.read_text(), re.MULTILINE)
        if match:
            roots.append(match.group(1).strip())
    roots.extend([Path.home() / "AndroidStorage/Android/Sdk", Path.home() / "Android/Sdk"])
    for root in roots:
        if not root:
            continue
        sdk = Path(root)
        analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
        candidates = sorted(sdk.glob("build-tools/*/apksigner"), reverse=True)
        if analyzer.is_file() and candidates:
            return analyzer, candidates[0]
    raise PublishError("未找到 apkanalyzer / apksigner，请配置本地 Android SDK")


def verify_apk(apk, env):
    analyzer, signer = android_tools(env)
    package = run([analyzer, "manifest", "application-id", apk], env=env, capture=True)
    version = run([analyzer, "manifest", "version-name", apk], env=env, capture=True)
    code = int(run([analyzer, "manifest", "version-code", apk], env=env, capture=True))
    debuggable = run([analyzer, "manifest", "debuggable", apk], env=env, capture=True)
    if package != APPLICATION_ID or debuggable != "false" or not re.fullmatch(r"\d+(?:\.\d+)+", version):
        raise PublishError("APK 包名、版本或 release 构建属性不正确")
    signed = run([signer, "verify", "--print-certs", apk], env=env, capture=True)
    fingerprints = re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", signed)
    if fingerprints != [CERTIFICATE_SHA256]:
        raise PublishError("APK 签名证书与已有版本不一致")
    return version, code


def prepare(args, env, head):
    stored = None
    if args.apk:
        apk = args.apk.resolve()
        if not apk.is_file():
            raise PublishError("指定的 APK 不存在")
        version, code = verify_apk(apk, env)
        stored_path = apk.parent / "release.json"
        stored = json.loads(stored_path.read_text()) if stored_path.is_file() else None
        if stored:
            stored_apk, _ = gitcode.select_apk(stored, apk.parent)
            if stored_apk.resolve() != apk or stored.get("tagName") != "v" + version:
                raise PublishError("已有 APK 与 release.json 不匹配")
        commit = args.source_commit or (stored or {}).get("sourceCommit") or head
    else:
        verify_signing_key(env)
        run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"], env=env)
        command = [ROOT / "gradlew", "--no-daemon"]
        if args.offline:
            command.append("--offline")
        run(command + ["testDebugUnitTest", "lintRelease", "assembleRelease"], env=env)
        apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
        version, code = verify_apk(apk, env)
        expected = project_version((ROOT / "app/build.gradle.kts").read_text())
        if (version, code) != expected:
            raise PublishError("构建出的 APK 版本与源码不一致")
        commit = head
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise PublishError("无效的 APK 源码提交")
    source = run(["git", "show", commit + ":app/build.gradle.kts"], capture=True)
    if project_version(source) != (version, code):
        raise PublishError("APK 版本与对应源码提交不一致，请指定正确的 --source-commit")
    if run(["git", "merge-base", "--is-ancestor", commit, head], check=False).returncode:
        raise PublishError("APK 源码提交不在当前 main 历史中")
    directory = ROOT / "app/build/local-releases" / ("v" + version)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"RuanCunDrink-{version}-release.apk"
    if apk.resolve() != target.resolve():
        if target.exists() and gitcode.sha256(target.read_bytes()) != gitcode.sha256(apk.read_bytes()):
            raise PublishError(f"该版本已有不同的本地产物，请使用 --apk {target} 重试原包，或递增版本号")
        shutil.copy2(apk, target)
    digest = gitcode.sha256(target.read_bytes())
    body = args.notes_file.read_text() if args.notes_file else (stored or {}).get("body")
    if body is None:
        body = run(["git", "log", "-1", "--pretty=%s", commit], capture=True)
    release = {"tagName": "v" + version, "name": "软村饮水 v" + version, "body": body,
               "sourceCommit": commit, "versionCode": code, "isDraft": False, "isPrerelease": False,
               "assets": [{"name": target.name, "size": target.stat().st_size, "digest": "sha256:" + digest}]}
    (directory / "release.json").write_text(json.dumps(release, ensure_ascii=False, indent=2) + "\n")
    (directory / "release-notes.txt").write_text(body + "\n")
    return release, target, digest


def read_gitcode_token(args, env):
    if env.get("GITCODE_TOKEN", "").strip():
        return env["GITCODE_TOKEN"].strip()
    path = args.gitcode_token_file or Path(env.get("GITCODE_TOKEN_FILE", ROOT / "gitCode_token"))
    if path.is_file():
        if stat.S_IMODE(path.stat().st_mode) & 0o077:
            raise PublishError("GitCode 令牌文件权限需设为 600")
        token = path.read_text().strip()
        if not token or len(token.splitlines()) != 1:
            raise PublishError("GitCode 令牌文件应只包含一行私人令牌")
        return token
    if args.gitcode_token_file or env.get("GITCODE_TOKEN_FILE"):
        raise PublishError("指定的 GitCode 令牌文件不存在")
    if not sys.stdin.isatty():
        raise PublishError("请配置 GITCODE_TOKEN 或 --gitcode-token-file；非交互模式无法输入令牌")
    return getpass.getpass("GitCode 私人令牌（输入不显示）: ").strip()


@contextmanager
def gitcode_git_environment(env, token):
    # Git reads the token from the environment, never from the remote URL or argv.
    with tempfile.TemporaryDirectory(prefix="ruancun-git-") as directory:
        askpass = Path(directory) / "askpass.sh"
        askpass.write_text('''#!/usr/bin/env sh
case "$1" in
    *Username*) printf '%s\\n' "${GITCODE_REPOSITORY%%/*}" ;;
    *Password*) printf '%s\\n' "$GITCODE_TOKEN" ;;
esac
''')
        askpass.chmod(0o700)
        yield dict(env, GIT_ASKPASS=str(askpass), GIT_TERMINAL_PROMPT="0", GITCODE_TOKEN=token)


def check_tag(metadata, remote="origin", env=None):
    tag = metadata["tagName"]
    command = ["git", "-c", "credential.helper="] if env else ["git"]
    lines = run(command + ["ls-remote", remote, "refs/tags/" + tag, "refs/tags/" + tag + "^{}"],
                env=env, capture=True)
    refs = dict(line.split("\t", 1)[::-1] for line in lines.splitlines() if "\t" in line)
    existing = refs.get("refs/tags/" + tag + "^{}") or refs.get("refs/tags/" + tag)
    if existing and existing != metadata["sourceCommit"]:
        raise PublishError(f"{tag} 已指向其他提交；请先递增版本号，不能覆盖已发布版本")


def sync_source(env, token, head, metadata=None):
    api = gitcode.GitCodeApi(env["GITCODE_REPOSITORY"], token)
    repository = api.check_repository()
    run(["git", "fetch", "origin", "main", "--no-tags"])
    if run(["git", "merge-base", "--is-ancestor", "origin/main", head], check=False).returncode:
        raise PublishError("GitHub main 包含本地尚未合入的提交，请先同步代码")
    remote = "https://gitcode.com/" + env["GITCODE_REPOSITORY"] + ".git"
    with gitcode_git_environment(env, token) as git_env:
        # Preflight both hosts before pushing either one. Never force a divergent branch.
        refs = run(["git", "-c", "credential.helper=", "ls-remote", remote, "refs/heads/main"],
                   env=git_env, capture=True)
        if refs:
            gitcode_head = refs.split()[0]
            run(["git", "-c", "credential.helper=", "fetch", "--no-tags", remote, "main"], env=git_env)
            if run(["git", "merge-base", "--is-ancestor", gitcode_head, head], check=False).returncode:
                raise PublishError("GitCode main 与本地历史分叉，请先合并代码；不会强制覆盖")
        if metadata:
            check_tag(metadata)
            check_tag(metadata, remote, git_env)
        run(["git", "push", "origin", head + ":refs/heads/main"])
        run(["git", "-c", "credential.helper=", "push", remote, head + ":refs/heads/main"], env=git_env)
        for command, command_env in [
            (["git", "ls-remote", "origin", "refs/heads/main"], None),
            (["git", "-c", "credential.helper=", "ls-remote", remote, "refs/heads/main"], git_env),
        ]:
            if run(command, env=command_env, capture=True).split()[0] != head:
                raise PublishError("远程 main 在同步期间发生变化，请重试")
    api.prepare_source_repository(repository, head)
    print(f"GitHub / GitCode main 已同步：{head}", flush=True)


def github_release(repository, tag):
    result = run(["gh", "release", "view", tag, "--repo", repository, "--json",
                  "tagName,isDraft,isPrerelease,assets,url"], capture=True, check=False)
    if result.returncode:
        if "release not found" in result.stderr.lower():
            return None
        raise PublishError("无法读取 GitHub 发行版，请检查 gh 登录和网络")
    return json.loads(result.stdout)


def github_asset_matches(release, apk, digest, repository):
    assets = [asset for asset in release.get("assets", []) if asset.get("name", "").lower().endswith(".apk")]
    if not assets:
        return False
    if len(assets) != 1 or assets[0].get("name") != apk.name or assets[0].get("size") != apk.stat().st_size:
        raise PublishError("GitHub 已有其他 APK，拒绝覆盖同版本安装包")
    asset_digest = assets[0].get("digest")
    if asset_digest:
        matches = asset_digest == "sha256:" + digest
    else:
        with tempfile.TemporaryDirectory() as directory:
            run(["gh", "release", "download", release["tagName"], "--repo", repository,
                 "--pattern", apk.name, "--dir", directory])
            matches = gitcode.sha256((Path(directory) / apk.name).read_bytes()) == digest
    if not matches:
        raise PublishError("GitHub 同版本 APK 哈希不同，拒绝覆盖")
    return True


def upload_github(repository, metadata, apk, digest):
    release = github_release(repository, metadata["tagName"])
    if release:
        if release.get("isDraft") or release.get("isPrerelease"):
            raise PublishError("同名 GitHub 发行版不是正式版，拒绝修改")
        if github_asset_matches(release, apk, digest, repository):
            print("GitHub APK 已存在且哈希一致，跳过上传", flush=True)
            return
        run(["gh", "release", "upload", metadata["tagName"], apk, "--repo", repository])
    else:
        run(["gh", "release", "create", metadata["tagName"], apk, "--repo", repository,
             "--target", metadata["sourceCommit"], "--title", metadata["name"],
             "--notes-file", apk.parent / "release-notes.txt"])
    uploaded = github_release(repository, metadata["tagName"])
    if not uploaded or not github_asset_matches(uploaded, apk, digest, repository):
        raise PublishError("未确认 GitHub APK 上传成功")


def publish(args, env, metadata, apk, digest):
    for command in ("git", "gh", "curl"):
        if not shutil.which(command, path=env.get("PATH")):
            raise PublishError(f"缺少命令：{command}")
    repository = env.get("GITHUB_REPOSITORY", GITHUB_REPOSITORY)
    check_tag(metadata)
    token = read_gitcode_token(args, env)
    api = gitcode.GitCodeApi(env["GITCODE_REPOSITORY"], token)
    api.check_repository()
    print(f'\n版本：{metadata["tagName"]}\n源码：{metadata["sourceCommit"]}\nAPK：{apk}\nSHA-256：{digest}', flush=True)
    if not args.yes and input("确认从本机上传到 GitHub 和 GitCode？[y/N] ").strip().lower() != "y":
        print("已取消上传，本地签名 APK 已保留")
        return
    sync_source(env, token, require_clean_main(), metadata)
    upload_github(repository, metadata, apk, digest)
    result = gitcode.sync_release(api, metadata, apk, digest)
    print(f'\n发布完成：\nGitHub：https://github.com/{repository}/releases/tag/{metadata["tagName"]}\nGitCode：{result["download_url"]}')


def main():
    parser = argparse.ArgumentParser(description="在本机测试、签名构建并上传到 GitHub 和 GitCode")
    parser.add_argument("--yes", action="store_true", help="跳过最终上传确认")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--prepare-only", action="store_true", help="只在本机测试和生成签名 APK，不上传")
    modes.add_argument("--apk", type=Path, help="直接上传已有签名 APK，跳过重新编译")
    modes.add_argument("--sync-only", action="store_true", help="只将本地 main 同步到 GitHub 和 GitCode")
    parser.add_argument("--source-commit", help="指定已有 APK 的源码提交；默认读取同目录 release.json")
    parser.add_argument("--gitcode-token-file", type=Path, help="读取本机 GitCode 令牌文件（权限 600）")
    parser.add_argument("--notes-file", type=Path, help="发行说明文件")
    parser.add_argument("--offline", action="store_true", help="使用 Gradle 离线缓存")
    args = parser.parse_args()
    head = require_clean_main()
    env = local_environment(require_signing=not (args.apk or args.sync_only))
    if args.sync_only:
        sync_source(env, read_gitcode_token(args, env), head)
        return
    metadata, apk, digest = prepare(args, env, head)
    print(f'\n本地签名包已准备：{apk}\nSHA-256：{digest}', flush=True)
    if args.prepare_only:
        return
    publish(args, env, metadata, apk, digest)


if __name__ == "__main__":
    try:
        main()
    except (PublishError, gitcode.SyncError, OSError, ValueError, EOFError) as error:
        print(f"本地发布失败：{error}", file=sys.stderr)
        sys.exit(1)
