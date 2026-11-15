import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


SPEC = importlib.util.spec_from_file_location(
    "sync_gitcode_release", Path(__file__).parents[1] / "sync-gitcode-release.py"
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
        release = {"tag_name": "v1.3.27", "prerelease": False,
                   "release_status": "latest", "target_commitish": "a" * 40}
        attachment = {"name": "app.apk", "browser_download_url": "https://gitcode.com/owner/downloads/releases/download/v1.3.27/app.apk"}
        if path == "/releases/tags/v1.3.27":
            if kwargs.get("public"):
                return dict(release, assets=[attachment] if self.attached else [])
            if not self.existing:
                raise sync.SyncError("not found", 404)
            return dict(release, assets=[attachment] if self.attached else [])
        if method == "POST" and path == "/releases":
            self.existing = True
            return release
        raise AssertionError((method, path, kwargs))

    def upload_attachment(self, tag, apk):
        self.calls.append(("PUT", "/signed-upload", {"tag": tag, "apk": apk}))
        self.attached = True


class GitCodeReleaseSyncTest(unittest.TestCase):
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
        writes = [(path, kwargs) for method, path, kwargs in api.calls if method in ("POST", "PUT")]
        self.assertEqual(["/releases", "/signed-upload"], [path for path, _ in writes])
        self.assertEqual("a" * 40, writes[0][1]["fields"]["target_commitish"])
        self.assertEqual("latest", writes[0][1]["fields"]["release_status"])
        self.assertEqual(self.apk, writes[1][1]["apk"])
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

    def test_another_apk_filename_cannot_be_added_to_the_same_release(self):
        api = FakeApi(existing=True, attached=True)
        original_request = api.request

        def request(method, path="", **kwargs):
            release = original_request(method, path, **kwargs)
            if method == "GET" and path.startswith("/releases/tags/"):
                release["assets"] = [{"name": "other.apk"}]
            return release

        api.request = request
        with self.assertRaisesRegex(sync.SyncError, "different APK"):
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
        api = sync.GitCodeApi("owner/downloads", "test-token")
        commit = "a" * 40
        with patch.object(api, "request", side_effect=[{"sha": commit}, {}]) as request:
            self.assertEqual(commit, api.prepare_source_repository({"name": "Downloads", "default_branch": "master"}, commit))
        self.assertEqual(("GET", "/commits/" + commit), request.call_args_list[0].args)
        self.assertEqual({"name": "Downloads", "default_branch": "main"}, request.call_args_list[1].kwargs["fields"])

    def test_missing_source_commit_cannot_publish_a_new_release(self):
        api = sync.GitCodeApi("owner/downloads", "test-token")
        with patch.object(api, "request", return_value=None):
            with self.assertRaisesRegex(sync.SyncError, "Synchronize"):
                api.prepare_source_repository({}, "a" * 40)

    def test_permission_failure_is_not_mistaken_for_a_missing_release(self):
        api = FakeApi()
        api.request = lambda *args, **kwargs: (_ for _ in ()).throw(sync.SyncError("forbidden", 403))
        with self.assertRaisesRegex(sync.SyncError, "forbidden"):
            sync.sync_release(api, self.metadata, self.apk, self.digest)

    def test_http_errors_do_not_expose_the_token(self):
        api = sync.GitCodeApi("owner/downloads", "secret-value")
        failure = HTTPError("https://gitcode.com?access_token=secret-value", 403, "secret-value", {}, None)
        with patch.object(sync, "urlopen", side_effect=failure):
            with self.assertRaises(sync.SyncError) as caught:
                api.request("GET")
        self.assertNotIn("secret-value", str(caught.exception))
        self.assertEqual(403, caught.exception.status)

    def test_transient_gitcode_connection_failure_is_retried_for_reads(self):
        api = sync.GitCodeApi("owner/downloads", "test-token")
        with patch.object(sync, "urlopen", side_effect=[TimeoutError(), io.BytesIO(b'{"public":true}')]) as opened:
            with patch.object(sync.time, "sleep"):
                self.assertEqual({"public": True}, api.request("GET"))
        self.assertEqual(2, opened.call_count)

    def test_upload_uses_signed_put_and_returned_headers_without_api_credentials(self):
        api = sync.GitCodeApi("owner/downloads", "test-token")
        target = {"url": "https://bucket.obs.cn-south-1.myhuaweicloud.com/apk?signature=private-value",
                  "headers": {"x-obs-acl": "public-read", "x-obs-meta-project-id": "42",
                              "x-obs-callback": "callback-data", "Content-Type": "application/octet-stream"}}

        def curl(command, **kwargs):
            self.assertNotIn("test-token", " ".join(command))
            self.assertNotIn("private-value", " ".join(command))
            self.assertNotIn("test-token", kwargs["input"])
            self.assertIn('url = "' + target['url'] + '"', kwargs["input"])
            self.assertIn("PUT", command)
            self.assertEqual(str(self.apk), command[command.index("--upload-file") + 1])
            for key, value in target["headers"].items():
                self.assertIn(key + ": " + value, kwargs["input"])
            return sync.subprocess.CompletedProcess(command, 0, "201", "")

        with patch.object(api, "request", return_value=target) as request:
            with patch.object(sync.subprocess, "run", side_effect=curl):
                api.upload_attachment("v1.3.27", self.apk)
        request.assert_called_once_with("GET", "/releases/v1.3.27/upload_url", fields={"file_name": "app.apk"})

    def test_timed_out_upload_is_not_duplicated_if_attachment_already_exists(self):
        api = FakeApi(existing=True)
        uploads = []

        def upload(tag, apk):
            uploads.append(apk)
            api.attached = True
            raise sync.SyncError('Response timed out')

        api.upload_attachment = upload
        with patch.object(sync, "download_sha256", return_value=self.digest):
            sync.sync_release(api, self.metadata, self.apk, self.digest)
        self.assertEqual(1, len(uploads))

    def test_download_requires_a_public_gitcode_url(self):
        for url in ["http://gitcode.com/app.apk", "https://example.com/app.apk", "https://gitcode.com.example.com/app.apk"]:
            with self.assertRaises(sync.SyncError):
                sync.download_sha256(url)

    def test_public_download_retries_transient_connection_failures(self):
        payload = self.apk.read_bytes()
        with patch.object(sync, "urlopen", side_effect=[TimeoutError(), io.BytesIO(payload)]):
            with patch.object(sync.time, "sleep"):
                self.assertEqual(self.digest, sync.download_sha256("https://gitcode.com/owner/repo/app.apk"))

    def test_missing_release_http_400_is_normalized_but_other_400_errors_are_not(self):
        api = sync.GitCodeApi("owner/downloads", "secret-value")
        for message, expected in [("Release not found", 404), ("Invalid tag", 400)]:
            body = io.BytesIO(('{"error_message":"' + message + '"}').encode())
            error = HTTPError("https://api.gitcode.com", 400, "Bad Request", {}, body)
            with patch.object(sync, "urlopen", side_effect=error):
                with self.assertRaises(sync.SyncError) as caught:
                    api.request("GET", "/releases/tags/v1.3.27")
            self.assertEqual(expected, caught.exception.status)

    def test_api_authentication_uses_a_header_and_public_requests_have_no_token(self):
        api = sync.GitCodeApi("owner/downloads", "secret-value")
        for public in (False, True):
            with patch.object(sync, "urlopen", return_value=io.BytesIO(b'{}')) as opened:
                api.request("GET", "/releases", public=public)
            request = opened.call_args.args[0]
            self.assertNotIn("secret-value", request.full_url)
            self.assertEqual(None if public else "Bearer secret-value", request.get_header("Authorization"))

    def test_create_release_uses_a_json_body_and_bearer_header(self):
        api = sync.GitCodeApi("owner/downloads", "secret-value")
        fields = {"tag_name": "v1.3.30", "release_status": "latest", "body": "软村饮水"}
        with patch.object(sync, "urlopen", return_value=io.BytesIO(b'{}')) as opened:
            api.request("POST", "/releases", fields=fields)
        request = opened.call_args.args[0]
        self.assertEqual("POST", request.get_method())
        self.assertEqual("application/json", request.get_header("Content-type"))
        self.assertEqual(fields, json.loads(request.data))
        self.assertNotIn("secret-value", request.full_url)
        self.assertNotIn("secret-value", request.data.decode())

    def test_a_release_with_a_different_source_commit_is_not_modified(self):
        api = FakeApi(existing=True)
        original_request = api.request

        def request(method, path="", **kwargs):
            release = original_request(method, path, **kwargs)
            release["target_commitish"] = "b" * 40
            return release

        api.request = request
        with self.assertRaisesRegex(sync.SyncError, "stable release source"):
            sync.sync_release(api, self.metadata, self.apk, self.digest)
        self.assertFalse(any(method in ("POST", "PUT") for method, _, _ in api.calls))

    def test_repository_validation_uses_anonymous_releases_instead_of_private_details_endpoint(self):
        api = sync.GitCodeApi("owner/downloads", "secret-value")
        repository = {"private": False, "public": True}
        with patch.object(api, "request", side_effect=[repository, []]) as request:
            self.assertEqual(repository, api.check_repository())
        self.assertEqual(("GET", "/releases"), request.call_args_list[1].args)
        self.assertTrue(request.call_args_list[1].kwargs["public"])


if __name__ == "__main__":
    unittest.main()
