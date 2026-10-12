# 手动发布

推送到 `main` 只运行测试、编译及签名，并将 APK 存入该次 Actions 的 Artifacts；不会发布 GitHub Release。推送标签也不会自动发布。

需要发布时，可在项目根目录直接运行：

```bash
./scripts/publish-release.sh
```

脚本会自动读取源码中的 `versionName`，检查工作区、`main` 分支及远端同步状态，并在一次确认后触发 GitHub Actions，无需打开网页或手填版本号。自动化环境可使用 `./scripts/publish-release.sh --yes` 跳过确认。

发布前请确认：

1. 确认 `app/build.gradle.kts` 的 `versionName`、`versionCode` 已递增，并推送代码。
2. 本地 `main` 没有未提交修改，并且已经与 `origin/main` 同步。
3. 运行上述脚本。测试、lint、签名构建通过后，工作流会为该提交创建标签及 Release，并附加签名 APK。

只想获取安装包时，不勾选 `publish_release`；构建结束后下载该次运行的 Artifacts。

工作流会验证输入版本与源码一致，并拒绝覆盖已存在的 Release，或使用指向其他提交的同名标签。发布时使用仓库已配置的签名 Secrets，与现有安装包保持同一证书。

也可直接使用 GitHub CLI 触发，但此方式需要自行填写版本号：

```bash
gh workflow run android-release.yml \
  --repo Leenkee/ShanRuanDrinkWater --ref main \
  -f publish_release=true -f release_version=1.3.16
```

只有发布正式 Release 后，应用内检查更新才会发现新版本；Actions Artifacts 不会被视为已发布版本。
