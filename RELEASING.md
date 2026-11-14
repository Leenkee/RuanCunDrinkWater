# 手动发布

GitHub 保存应用源码并运行签名构建；Gitee 的 [下载仓库](https://gitee.com/young-patronum/ruan-cun-drink-water)仅保存下载说明和发行版 APK，不同步应用源码、分支或源码标签。应用内手动和自动检查更新均读取 Gitee 的正式发行版，下载按钮使用其实际 APK 附件地址。

首次使用前，在 GitHub 仓库的 Actions 设置中配置：

- Repository variable `GITEE_REPOSITORY`：`young-patronum/ruan-cun-drink-water`。
- Repository secret `GITEE_TOKEN`：具有该仓库发布权限、勾选 `projects` 权限的 Gitee 私人令牌。令牌仅在 Actions 中使用，不会写入 APK。

Gitee 仓库必须公开。同步脚本会为尚未初始化的空仓库创建一份下载说明 README，发行版标签指向 Gitee 自身的 README 分支，不使用 GitHub 源码提交。

发布工作流只在手动触发时运行。推送到 `main` 或推送标签均不会自动触发该工作流，每次运行发布脚本只触发一次 Actions。

需要发布时，可在项目根目录直接运行：

```bash
./scripts/publish-release.sh
```

脚本会自动读取源码中的 `versionName` 和最新提交标题，检查工作区、`main` 分支及远端同步状态，并在一次确认后触发 GitHub Actions，无需打开网页或手填版本号。Actions 的运行名称显示为“发布 v版本号 · 最新提交标题”，便于直接查看本次修改。自动化环境可使用 `./scripts/publish-release.sh --yes` 跳过确认。

发布前请确认：

1. 确认 `app/build.gradle.kts` 的 `versionName`、`versionCode` 已递增，并推送代码。
2. 本地 `main` 没有未提交修改，并且已经与 `origin/main` 同步。
3. 运行上述脚本。同步脚本测试、Android 单元测试、lint、签名构建通过后，工作流会为该提交创建 GitHub 标签及 Release，附加签名 APK，再将同一个 APK 上传到 Gitee，并核对公开下载文件的 SHA-256。

只想获取安装包时，不勾选 `publish_release`；构建结束后下载该次运行的 Artifacts。

工作流会验证输入版本与源码一致，并拒绝覆盖已存在的 Release，或使用指向其他提交的同名标签。发布时使用仓库已配置的签名 Secrets，与现有安装包保持同一证书。

也可直接使用 GitHub CLI 触发，但此方式需要自行填写版本号：

```bash
gh workflow run android-release.yml \
  --repo Leenkee/RuanCunDrinkWater --ref main \
  -f publish_release=true -f release_version=1.3.21 \
  -f change_summary="本次修改说明"
```

只有 Gitee 正式发行版及其 APK 附件可公开下载后，应用内检查更新才会发现新版本；Actions Artifacts 不会被视为已发布版本。

## 单独同步已有安装包

同步失败时，无需重新构建或再次发布 GitHub Release。可手动触发专用流程同步任意已发布版本：

```bash
gh workflow run sync-gitee-release.yml \
  --repo Leenkee/RuanCunDrinkWater --ref main \
  -f tag=v1.3.26
```

`tag=latest` 同步 GitHub 最新正式版本。该流程只下载现有 APK、复制发行说明、上传到 Gitee 发行版，不推送源码。重复运行会校验同名附件；若文件一致则跳过上传，若不同则报错而不覆盖。

本地编译默认使用上述 Gitee 仓库，可通过 `-PGITEE_REPOSITORY=owner/repository` 或同名环境变量覆盖。已有旧版应用需要安装切换后的新版本，才能使用 Gitee 检查更新和下载；升级后会忽略之前缓存的 GitHub 下载链接。

接口依据：[Gitee API v5](https://gitee.com/api/v5/swagger)、[发行版附件下载路由](https://blog.gitee.com/2022/08/18/update/)。
