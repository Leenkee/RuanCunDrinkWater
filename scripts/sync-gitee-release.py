#!/usr/bin/env python3
"""Copy a GitHub release APK to Gitee without copying the source repository."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen
import uuid


class SyncError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def download_sha256(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != "gitee.com":
        raise SyncError("The APK download must start at https://gitee.com")
    digest = hashlib.sha256()
    try:
        with urlopen(Request(url, headers={"User-Agent": "RuanCunDrink-Release"}), timeout=60) as response:
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                digest.update(chunk)
    except (HTTPError, URLError, TimeoutError):
        raise SyncError("Cannot download the public Gitee APK for verification") from None
    return digest.hexdigest()


class GiteeApi:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository, flags=re.ASCII):
            raise SyncError("Set GITEE_REPOSITORY to owner/repository")
        if not token:
            raise SyncError("Set the GitHub Actions secret GITEE_TOKEN (Gitee projects permission)")
        self.repository = repository
        self.token = token
        self.prefix = "/repos/" + repository

    def request(self, method, path="", fields=None, upload=None, public=False):
        url = "https://gitee.com/api/v5" + self.prefix + path
        headers = {"Accept": "application/json", "User-Agent": "RuanCunDrink-Release"}
        data = None
        values = dict(fields or {})
        if not public:
            values["access_token"] = self.token
        if method == "GET":
            if values:
                url += "?" + urlencode(values)
        elif upload is None:
            data = urlencode(values).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            boundary = uuid.uuid4().hex
            chunks = []
            for name, value in values.items():
                chunks.append(
                    f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
                )
            chunks.extend([
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{upload.name}"\r\n'
                'Content-Type: application/vnd.android.package-archive\r\n\r\n'.encode(),
                upload.read_bytes(),
                f"\r\n--{boundary}--\r\n".encode(),
            ])
            data = b"".join(chunks)
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        try:
            with urlopen(Request(url, data=data, headers=headers, method=method), timeout=60) as response:
                return json.load(response)
        except HTTPError as error:
            # Never print request URLs or response bodies containing credentials.
            raise SyncError(f"Gitee {method} {self.prefix + path}: HTTP {error.code}", error.code) from None
        except (URLError, TimeoutError):
            raise SyncError(f"Cannot connect to Gitee for {method} {self.prefix + path}") from None
        except (ValueError, UnicodeError):
            raise SyncError(f"Gitee returned invalid JSON for {method} {self.prefix + path}") from None

    def check_repository(self):
        repository = self.request("GET")
        public_repository = self.request("GET", public=True)
        if repository.get("private") is not False or public_repository.get("public") is not True:
            raise SyncError("The Gitee download repository must be public")
        return repository

    def initialize_download_repository(self, repository):
        branch = repository.get("default_branch")
        if branch:
            return branch
        branches = self.request("GET", "/branches", fields={"per_page": 1})
        if branches:
            return branches[0]["name"]
        readme = (
            "# 软村饮水安装包\n\n"
            "本仓库仅用于分发签名 APK，不同步应用源码。\n\n"
            "请在[发行版](../../releases)中下载 Android 安装包。\n\n"
            "项目源码：https://github.com/Leenkee/RuanCunDrinkWater\n"
        )
        created = self.request("POST", "/contents/README.md", fields={
            "content": base64.b64encode(readme.encode()).decode(),
            "message": "Initialize APK download repository",
        })
        # This is the Gitee README commit, never a commit from the source repository.
        commit = created.get("commit", {}).get("sha")
        branch = self.request("GET").get("default_branch")
        if not branch and not commit:
            raise SyncError("Initialize the Gitee download repository with a README before retrying")
        return branch or commit


def select_apk(metadata, directory):
    if metadata.get("isDraft") or metadata.get("isPrerelease"):
        raise SyncError("Only published stable GitHub releases can be synchronized")
    assets = [asset for asset in metadata.get("assets", []) if asset.get("name", "").lower().endswith(".apk")]
    if len(assets) != 1:
        raise SyncError("The GitHub release must contain exactly one APK")
    asset = assets[0]
    name = asset["name"]
    if not re.fullmatch(r"[\w.-]+\.apk", name, re.IGNORECASE | re.ASCII):
        raise SyncError("Invalid APK asset filename")
    path = directory / name
    if not path.is_file() or path.stat().st_size != asset.get("size"):
        raise SyncError("The downloaded APK does not match the GitHub asset size")
    digest = sha256(path.read_bytes())
    if asset.get("digest") and asset["digest"] != "sha256:" + digest:
        raise SyncError("The downloaded APK does not match the GitHub SHA-256 digest")
    return path, digest


def sync_release(api, metadata, apk, expected_digest):
    tag = metadata.get("tagName", "")
    if not re.fullmatch(r"v\d+(?:\.\d+)+", tag):
        raise SyncError("Invalid stable release tag")
    repository = api.check_repository()
    target = api.initialize_download_repository(repository)
    tag_path = "/releases/tags/" + quote(tag, safe="")
    try:
        release = api.request("GET", tag_path)
    except SyncError as error:
        if error.status != 404:
            raise
        release = None
    # Gitee can report a missing tag with HTTP 200 and a JSON null body.
    if release is None:
        release = api.request("POST", "/releases", fields={
            "tag_name": tag,
            "name": metadata.get("name") or tag,
            "body": metadata.get("body") or "软村饮水 Android 安装包。",
            "prerelease": "false",
            "target_commitish": target,
        })
    if not isinstance(release, dict) or release.get("tag_name") != tag or release.get("prerelease"):
        raise SyncError("The existing Gitee release does not match the stable release tag")
    attachments_path = f'/releases/{int(release["id"])}/attach_files'
    attachments = []
    page = 1
    while True:
        batch = api.request("GET", attachments_path, fields={"page": page, "per_page": 100})
        attachments.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    matches = [asset for asset in attachments if asset.get("name") == apk.name]
    if len(matches) > 1:
        raise SyncError("The Gitee release contains duplicate APK attachments")
    if not matches:
        api.request("POST", attachments_path, upload=apk)

    # Resolve the link anonymously, exactly as the Android application will.
    for attempt in range(3):
        public_release = api.request("GET", tag_path, public=True)
        urls = [asset.get("browser_download_url") for asset in public_release.get("assets", [])
                if asset.get("name") == apk.name and asset.get("browser_download_url")]
        if len(urls) == 1:
            break
        if attempt == 2:
            raise SyncError("The uploaded APK is not visible in the public Gitee release")
        time.sleep(2)
    if download_sha256(urls[0]) != expected_digest:
        raise SyncError("The public Gitee APK SHA-256 differs from GitHub; refusing to overwrite the release")
    return {"tag": tag, "download_url": urls[0], "sha256": expected_digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITEE_REPOSITORY", ""))
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--release-json", type=Path)
    parser.add_argument("--apk-dir", type=Path)
    args = parser.parse_args()
    api = GiteeApi(args.repository, os.environ.get("GITEE_TOKEN", ""))
    if args.check:
        api.check_repository()
        print(f"Gitee download repository verified: {args.repository}")
        return
    if args.release_json is None or args.apk_dir is None:
        parser.error("--release-json and --apk-dir are required unless --check is used")
    metadata = json.loads(args.release_json.read_text())
    apk, digest = select_apk(metadata, args.apk_dir)
    result = sync_release(api, metadata, apk, digest)
    print(json.dumps(result, ensure_ascii=False))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f'Gitee APK: [{apk.name}]({result["download_url"]})\n\nSHA-256: `{digest}`\n')


if __name__ == "__main__":
    try:
        main()
    except (SyncError, OSError, ValueError) as error:
        print(f"Gitee APK synchronization failed: {error}", file=sys.stderr)
        sys.exit(1)
