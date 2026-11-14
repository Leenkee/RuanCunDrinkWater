from email.parser import BytesParser
from email.policy import default
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


SPEC = importlib.util.spec_from_file_location(
    "sync_gitee_release", Path(__file__).parents[1] / "sync-gitee-release.py"
)
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)


class FakeApi:
    def __init__(self, existing=False, attached=False):
        self.existing = existing
        self.attached = attached
        self.calls = []

    def check_repository(self):
        return {"public": True, "private": False, "default_branch": "downloads"}

    def prepare_source_repository(self, repository, commit):
        return commit

    def request(self, method, path="", **kwargs):
        self.calls.append((method, path, kwargs))
        release = {"id": 42, "tag_name": "v1.3.27", "prerelease": False}
        attachment = {"name": "app.apk", "browser_download_url": "https://gitee.com/owner/downloads/releases/download/v1.3.27/app.apk"}
        if path == "/releases/tags/v1.3.27":
            if kwargs.get("public"):
                return dict(release, assets=[attachment] if self.attached else [])
            if not self.existing:
                raise sync.SyncError("not found", 404)
            return release
        if method == "POST" and path == "/releases":
            self.existing = True
            return release
        if path == "/releases/42/attach_files":
            if method == "GET":
                return [attachment] if self.attached else []
            if method == "POST":
                self.attached = True
                return attachment
        raise AssertionError((method, path, kwargs))


class GiteeReleaseSyncTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "app.apk"
        self.apk.write_bytes(b"PK\x03\x04signed-apk-fixture")
        self.digest = sync.sha256(self.apk.read_bytes())
        self.metadata = {
            "tagName": "v1.3.27", "name": "Release", "body": "Notes", "sourceCommit": "a" * 40,
            "isDraft": False, "isPrerelease": False,
            "assets": [{"name": "app.apk", "size": self.apk.stat().st_size, "digest": "sha256:" + self.digest}],
        }

    def test_uploads_apk_against_the_mirrored_source_commit(self):
        api = FakeApi()
        with patch.object(sync, "download_sha256", return_value=self.digest):
            result = sync.sync_release(api, self.metadata, self.apk, self.digest)
        writes = [(path, kwargs) for method, path, kwargs in api.calls if method == "POST"]
        self.assertEqual(["/releases", "/releases/42/attach_files"], [path for path, _ in writes])
        self.assertEqual("a" * 40, writes[0][1]["fields"]["target_commitish"])
        self.assertEqual(self.apk, writes[1][1]["upload"])
        self.assertEqual(self.digest, result["sha256"])

    def test_rerun_verifies_existing_apk_without_uploading_again(self):
        api = FakeApi(existing=True, attached=True)
        with patch.object(sync, "download_sha256", return_value=self.digest):
            sync.sync_release(api, self.metadata, self.apk, self.digest)
        self.assertFalse(any(method == "POST" for method, _, _ in api.calls))

    def test_missing_tag_returned_as_json_null_creates_the_release(self):
        api = FakeApi()
        original_request = api.request

        def request(method, path="", **kwargs):
            if method == "GET" and path.startswith("/releases/tags/") and not kwargs.get("public"):
                return None
            return original_request(method, path, **kwargs)

        api.request = request
        with patch.object(sync, "download_sha256", return_value=self.digest):
            sync.sync_release(api, self.metadata, self.apk, self.digest)
        self.assertTrue(api.existing)
        self.assertTrue(api.attached)

    def test_different_existing_apk_is_never_overwritten(self):
        api = FakeApi(existing=True, attached=True)
        with patch.object(sync, "download_sha256", return_value="different"):
            with self.assertRaisesRegex(sync.SyncError, "SHA-256 differs"):
                sync.sync_release(api, self.metadata, self.apk, self.digest)
        self.assertFalse(any(method == "POST" for method, _, _ in api.calls))

    def test_missing_public_apk_is_not_reported_as_success(self):
        api = FakeApi(existing=True, attached=True)
        original_request = api.request

        def request(method, path="", **kwargs):
            if kwargs.get("public"):
                return {"assets": []}
            return original_request(method, path, **kwargs)

        api.request = request
        with patch.object(sync.time, "sleep"):
            with self.assertRaisesRegex(sync.SyncError, "not visible"):
                sync.sync_release(api, self.metadata, self.apk, self.digest)

    def test_selects_original_github_apk_and_checks_its_digest(self):
        self.assertEqual((self.apk, self.digest), sync.select_apk(self.metadata, Path(self.directory.name)))
        self.metadata["assets"][0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(sync.SyncError, "SHA-256 digest"):
            sync.select_apk(self.metadata, Path(self.directory.name))

    def test_rejects_asset_size_mismatch_and_extra_apks(self):
        self.metadata["assets"][0]["size"] += 1
        with self.assertRaisesRegex(sync.SyncError, "asset size"):
            sync.select_apk(self.metadata, Path(self.directory.name))
        self.metadata["assets"].append({"name": "extra.apk"})
        with self.assertRaisesRegex(sync.SyncError, "exactly one"):
            sync.select_apk(self.metadata, Path(self.directory.name))

    def test_rejects_drafts_and_prereleases(self):
        for flag in ["isDraft", "isPrerelease"]:
            with self.subTest(flag=flag):
                with self.assertRaisesRegex(sync.SyncError, "stable"):
                    sync.select_apk(dict(self.metadata, **{flag: True}), Path(self.directory.name))

    def test_verifies_source_commit_before_setting_main_as_default(self):
        api = sync.GiteeApi("owner/downloads", "test-token")
        commit = "a" * 40
        with patch.object(api, "request", side_effect=[{"sha": commit}, {}]) as request:
            self.assertEqual(commit, api.prepare_source_repository({"name": "Downloads", "default_branch": "master"}, commit))
        self.assertEqual(("GET", "/commits/" + commit), request.call_args_list[0].args)
        self.assertEqual({"name": "Downloads", "default_branch": "main"}, request.call_args_list[1].kwargs["fields"])

    def test_missing_source_commit_cannot_publish_a_new_release(self):
        api = sync.GiteeApi("owner/downloads", "test-token")
        with patch.object(api, "request", return_value=None):
            with self.assertRaisesRegex(sync.SyncError, "Synchronize"):
                api.prepare_source_repository({}, "a" * 40)

    def test_permission_failure_is_not_mistaken_for_a_missing_release(self):
        api = FakeApi()
        api.request = lambda *args, **kwargs: (_ for _ in ()).throw(sync.SyncError("forbidden", 403))
        with self.assertRaisesRegex(sync.SyncError, "forbidden"):
            sync.sync_release(api, self.metadata, self.apk, self.digest)

    def test_http_errors_do_not_expose_the_token(self):
        api = sync.GiteeApi("owner/downloads", "secret-value")
        failure = HTTPError("https://gitee.com?access_token=secret-value", 403, "secret-value", {}, None)
        with patch.object(sync, "urlopen", side_effect=failure):
            with self.assertRaises(sync.SyncError) as caught:
                api.request("GET")
        self.assertNotIn("secret-value", str(caught.exception))
        self.assertEqual(403, caught.exception.status)

    def test_upload_uses_the_documented_multipart_file_field(self):
        api = sync.GiteeApi("owner/downloads", "test-token")
        with patch.object(sync, "urlopen", return_value=io.BytesIO(b'{"id":1}')) as opened:
            api.request("POST", "/releases/42/attach_files", upload=self.apk)
        request = opened.call_args.args[0]
        message = BytesParser(policy=default).parsebytes(
            ("Content-Type: " + request.get_header("Content-type") + "\r\n\r\n").encode() + request.data
        )
        parts = {part.get_param("name", header="Content-Disposition"): part for part in message.iter_parts()}
        self.assertEqual({"access_token", "file"}, set(parts))
        self.assertEqual("app.apk", parts["file"].get_filename())
        self.assertEqual(self.apk.read_bytes(), parts["file"].get_payload(decode=True))

    def test_download_requires_a_public_gitee_url(self):
        for url in ["http://gitee.com/app.apk", "https://example.com/app.apk", "https://gitee.com.example.com/app.apk"]:
            with self.assertRaises(sync.SyncError):
                sync.download_sha256(url)


if __name__ == "__main__":
    unittest.main()
