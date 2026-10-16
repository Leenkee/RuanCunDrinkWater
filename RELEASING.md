# 手动发布

发布工作流只在手动触发时运行。推送到 `main` 或推送标签均不会自动触发该工作流，每次运行发布脚本只触发一次 Actions。

需要发布时，可在项目根目录直接运行：

```bash
./scripts/publish-release.sh
```

脚本会自动读取源码中的 `versionName` 和最新提交标题，检查工作区、`main` 分支及远端同步状态，并在一次确认后触发 GitHub Actions，无需打开网页或手填版本号。Actions 的运行名称显示为“发布 v版本号 · 最新提交标题”，便于直接查看本次修改。自动化环境可使用 `./scripts/publish-release.sh --yes` 跳过确认。

发布前请确认：

1. 确认 `app/build.gradle.kts` 的 `versionName`、`versionCode` 已递增，并推送代码。
2. 本地 `main` 没有未提交修改，并且已经与 `origin/main` 同步。
3. 运行上述脚本。测试、lint、签名构建通过后，工作流会为该提交创建标签及 Release，并附加签名 APK。

只想获取安装包时，不勾选 `publish_release`；构建结束后下载该次运行的 Artifacts。

工作流会验证输入版本与源码一致，并拒绝覆盖已存在的 Release，或使用指向其他提交的同名标签。发布时使用仓库已配置的签名 Secrets，与现有安装包保持同一证书。

也可直接使用 GitHub CLI 触发，但此方式需要自行填写版本号：

```bash
gh workflow run android-release.yml \
  --repo Leenkee/RuanCunDrinkWater --ref main \
  -f publish_release=true -f release_version=1.3.20 \
  -f change_summary="本次修改说明"
```

只有发布正式 Release 后，应用内检查更新才会发现新版本；Actions Artifacts 不会被视为已发布版本。
