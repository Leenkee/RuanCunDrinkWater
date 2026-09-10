# 手动发布

推送到 `main` 只运行测试、编译及签名，并将 APK 存入该次 Actions 的 Artifacts；不会发布 GitHub Release。推送标签也不会自动发布。

需要发布时：

1. 确认 `app/build.gradle.kts` 的 `versionName`、`versionCode` 已递增，并推送代码。
2. 打开仓库的 **Actions → Android Release → Run workflow**。
3. 选择待发布分支，勾选 **publish_release**，在 **release_version** 输入该分支的版本号（例如 `1.3.14`，不要加 `v`）。
4. 点击 **Run workflow**。测试、lint、签名构建通过后，工作流会为此次检出的提交创建标签及 Release，并附加签名 APK。

只想获取安装包时，不勾选 `publish_release`；构建结束后下载该次运行的 Artifacts。

工作流会验证输入版本与源码一致，并拒绝覆盖已存在的 Release，或使用指向其他提交的同名标签。发布时使用仓库已配置的签名 Secrets，与现有安装包保持同一证书。

也可通过 GitHub CLI 手动触发：

```bash
gh workflow run android-release.yml \
  --repo Leenkee/ShanRuanDrinkWater --ref main \
  -f publish_release=true -f release_version=1.3.14
```

只有发布正式 Release 后，应用内检查更新才会发现新版本；Actions Artifacts 不会被视为已发布版本。
