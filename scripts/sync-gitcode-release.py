#!/usr/bin/env python3
"""Upload a local signed release APK to GitCode and verify its public download."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen


class SyncError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def download_sha256(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != "gitcode.com":
        raise SyncError("The APK download must start at https://gitcode.com")
    for attempt in range(3):
        digest = hashlib.sha256()
        try:
            with urlopen(Request(url, headers={"User-Agent": "RuanCunDrink-Release"}), timeout=180) as response:
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    digest.update(chunk)
            return digest.hexdigest()
        except HTTPError as error:
            if error.code < 500:
                raise SyncError(f"Public GitCode APK download returned HTTP {error.code}") from None
        except (URLError, TimeoutError):
            pass
        if attempt < 2:
            print("Retrying the public GitCode APK download", flush=True)
            time.sleep(2)
    raise SyncError("Cannot download the public GitCode APK for verification")


class GitCodeApi:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository, flags=re.ASCII):
            raise SyncError("Set GITCODE_REPOSITORY to owner/repository")
        if not token:
            raise SyncError("Set GITCODE_TOKEN with GitCode repository write permission")
        self.repository = repository
        self.token = token
        self.prefix = "/repos/" + repository

    def request(self, method, path="", fields=None, public=False):
        url = "https://api.gitcode.com/api/v5" + self.prefix + path
        headers = {"Accept": "application/json", "User-Agent": "RuanCunDrink-Release"}
        if not public:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if method == "GET":
            if fields:
                url += "?" + urlencode(fields)
        else:
            data = json.dumps(fields or {}, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        attempts = 3 if method in ("GET", "PATCH") else 1
        for attempt in range(attempts):
            try:
                with urlopen(Request(url, data=data, headers=headers, method=method), timeout=30) as response:
                    return json.load(response)
            except HTTPError as error:
                if error.code >= 500 and attempt + 1 < attempts:
                    time.sleep(2)
                    continue
                # GitCode reports a missing release as HTTP 400, not HTTP 404.
                if method == "GET" and path.startswith("/releases/") and error.code == 400:
                    try:
                        missing = json.load(error).get("error_message") == "Release not found"
                    except (ValueError, AttributeError):
                        missing = False
                    if missing:
                        raise SyncError("GitCode release not found", 404) from None
                # Do not expose request headers, signed URLs or response bodies.
                raise SyncError(f"GitCode {method} {self.prefix + path}: HTTP {error.code}", error.code) from None
            except (URLError, TimeoutError) as error:
                if attempt + 1 < attempts:
                    print(f"Retrying GitCode {method} {self.prefix + path}", flush=True)
                    time.sleep(2)
                    continue
                reason = getattr(error, "reason", error)
                raise SyncError(f"Cannot connect to GitCode for {method} {self.prefix + path} ({type(reason).__name__})") from None
            except (ValueError, UnicodeError):
                raise SyncError(f"GitCode returned invalid JSON for {method} {self.prefix + path}") from None

    def upload_attachment(self, tag, apk):
        """Upload by PUT to GitCode's signed URL using all returned OBS headers."""
        target = self.request("GET", "/releases/" + quote(tag, safe="") + "/upload_url",
                              fields={"file_name": apk.name})
        parsed = urlsplit(target.get("url", ""))
        headers = target.get("headers")
        if parsed.scheme != "https" or not parsed.netloc or parsed.username or not isinstance(headers, dict):
            raise SyncError("GitCode returned an invalid attachment upload target")
        config = ['url = ' + json.dumps(target["url"])]
        for name, value in headers.items():
            if not isinstance(value, str) or any(c in name + value for c in "\r\n"):
                raise SyncError("GitCode returned an invalid attachment upload header")
            config.append('header = ' + json.dumps(name + ': ' + value))
        print(f"Uploading {apk.name} to GitCode", flush=True)
        with tempfile.TemporaryDirectory() as directory:
            command = ["curl", "--ipv4", "--silent", "--show-error", "--connect-timeout", "20",
                       "--max-time", "300", "--request", "PUT", "--upload-file", str(apk),
                       "--output", str(Path(directory) / "response"), "--write-out", "%{http_code}",
                       "--config", "-"]
            try:
                result = subprocess.run(command, input="\n".join(config), text=True, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, timeout=330)
            except subprocess.TimeoutExpired:
                raise SyncError("GitCode APK upload timed out") from None
            if result.returncode:
                raise SyncError(f"GitCode APK upload failed (curl {result.returncode})")
            status = int(result.stdout.strip())
            if not 200 <= status < 300:
                raise SyncError(f"GitCode APK upload returned HTTP {status}", status)

    def check_repository(self):
        repository = self.request("GET")
        if repository.get("private") not in (False, 0) or repository.get("public") not in (True, 1):
            raise SyncError("The GitCode download repository must be public")
        # Repository details require authentication even for public projects.
        # Releases are anonymous: verify the same public surface the app uses.
        if not isinstance(self.request("GET", "/releases", public=True), list):
            raise SyncError("Cannot access public GitCode releases")
        return repository

    def prepare_source_repository(self, repository, commit):
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise SyncError("The release source commit is missing")
        mirrored = self.request("GET", "/commits/" + commit)
        if not isinstance(mirrored, dict) or mirrored.get("sha") != commit:
            raise SyncError("Synchronize the source to GitCode main before publishing")
        if repository.get("default_branch") != "main":
            self.request("PATCH", fields={"name": repository["name"], "default_branch": "main"})
        return commit


def select_apk(metadata, directory):
    if metadata.get("isDraft") or metadata.get("isPrerelease"):
        raise SyncError("Only stable releases can be synchronized")
    assets = [asset for asset in metadata.get("assets", []) if asset.get("name", "").lower().endswith(".apk")]
    if len(assets) != 1:
        raise SyncError("The release must contain exactly one APK")
    asset = assets[0]
    name = asset["name"]
    if not re.fullmatch(r"[\w.-]+\.apk", name, re.IGNORECASE | re.ASCII):
        raise SyncError("Invalid APK asset filename")
    path = directory / name
    if not path.is_file() or path.stat().st_size != asset.get("size"):
        raise SyncError("The local APK does not match the release asset size")
    digest = sha256(path.read_bytes())
    if asset.get("digest") and asset["digest"] != "sha256:" + digest:
        raise SyncError("The local APK does not match the release SHA-256 digest")
    return path, digest


def release_apks(release):
    return [asset for asset in (release or {}).get("assets", [])
            if asset.get("name", "").lower().endswith(".apk")]


def sync_release(api, metadata, apk, expected_digest):
    tag = metadata.get("tagName", "")
    if not re.fullmatch(r"v\d+(?:\.\d+)+", tag):
        raise SyncError("Invalid stable release tag")
    repository = api.check_repository()
    target = api.prepare_source_repository(repository, metadata.get("sourceCommit"))
    tag_path = "/releases/tags/" + quote(tag, safe="")
    try:
        release = api.request("GET", tag_path)
    except SyncError as error:
        if error.status != 404:
            raise
        release = None
    if release is None:
        release = api.request("POST", "/releases", fields={
            "tag_name": tag,
            "name": metadata.get("name") or tag,
            "body": metadata.get("body") or "软村饮水 Android 安装包。",
            "release_status": "latest",
            "target_commitish": target,
        })
    if (not isinstance(release, dict) or release.get("tag_name") != tag or release.get("prerelease")
            or release.get("release_status") == "pre" or release.get("target_commitish") != target):
        raise SyncError("The existing GitCode release does not match the stable release source")
    attachments = release_apks(release)
    if any(asset.get("name") != apk.name for asset in attachments):
        raise SyncError("The GitCode release already contains a different APK; refusing to overwrite it")
    if len(attachments) > 1:
        raise SyncError("The GitCode release contains duplicate APK attachments")
    if not attachments:
        for attempt in range(2):
            try:
                api.upload_attachment(tag, apk)
                break
            except SyncError as error:
                if error.status is not None and error.status < 500:
                    raise
                # A timed out response can follow a successful upload. Check before retrying.
                current = release_apks(api.request("GET", tag_path))
                if any(asset.get("name") == apk.name for asset in current):
                    break
                if attempt == 1:
                    raise
                print("Retrying the APK upload after checking GitCode attachments", flush=True)
                time.sleep(2)

    # Resolve the download anonymously, exactly as the Android application will.
    for attempt in range(5):
        public_release = api.request("GET", tag_path, public=True)
        urls = [asset.get("browser_download_url") for asset in release_apks(public_release)
                if asset.get("name") == apk.name and asset.get("browser_download_url")]
        if len(urls) == 1:
            break
        if attempt == 4:
            raise SyncError("The uploaded APK is not visible in the public GitCode release")
        time.sleep(2)
    if download_sha256(urls[0]) != expected_digest:
        raise SyncError("The public GitCode APK SHA-256 differs from the local APK; refusing to overwrite the release")
    return {"tag": tag, "download_url": urls[0], "sha256": expected_digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITCODE_REPOSITORY", ""))
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--source-commit")
    parser.add_argument("--release-json", type=Path)
    parser.add_argument("--apk-dir", type=Path)
    args = parser.parse_args()
    api = GitCodeApi(args.repository, os.environ.get("GITCODE_TOKEN", ""))
    if args.source_commit:
        api.prepare_source_repository(api.check_repository(), args.source_commit)
        print(f"GitCode main source verified: {args.source_commit}")
        return
    if args.check:
        api.check_repository()
        print(f"GitCode download repository verified: {args.repository}")
        return
    if args.release_json is None or args.apk_dir is None:
        parser.error("--release-json and --apk-dir are required unless --check is used")
    metadata = json.loads(args.release_json.read_text())
    apk, digest = select_apk(metadata, args.apk_dir)
    print(json.dumps(sync_release(api, metadata, apk, digest), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (SyncError, OSError, ValueError) as error:
        print(f"GitCode APK synchronization failed: {error}", file=sys.stderr)
        sys.exit(1)
