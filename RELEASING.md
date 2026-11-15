# 本地发布

源码同时维护在 [GitHub](https://github.com/Leenkee/RuanCunDrinkWater) 和 [Gitee](https://gitee.com/young-patronum/ruan-cun-drink-water)。所有测试、lint、签名编译和 APK 上传均从本机执行，不使用 GitHub Actions。两端发布同一次本地构建的 APK，并验证下载文件的 SHA-256。

应用的手动检查和每日自动检查均匿名请求 `https://gitee.com/api/v5/repos/young-patronum/ruan-cun-drink-water/releases/latest`，读取正式版标签中的版本号，并打开该发行版的 Gitee APK 附件下载地址。旧版升级后会忽略缓存的 GitHub 下载链接；令牌只用于本机发布，不进入 APK。

## 本机准备

- 安装 Git、Python 3、curl、GitHub CLI，并执行 `gh auth login`。
- 安装 JDK 17 或更高版本、Android SDK（含 cmdline-tools、build-tools、platforms;android-35），通过 `local.properties` 中的 `sdk.dir` 或 `ANDROID_HOME` 指定 SDK。此电脑的 Android Studio JBR 会自动用于构建，也可设置 `JAVA_HOME`。
- 将 Gitee 私人令牌单独一行放入项目根目录的 `gitee_key.md`，执行 `chmod 600 gitee_key.md`。该文件已被 Git 忽略。令牌需有目标仓库的 `projects` 权限。也可使用 `GITEE_TOKEN`、`GITEE_TOKEN_FILE` 或 `--gitee-token-file /path/to/token`，优先级依次为环境令牌、命令行文件、环境文件、默认文件。
- 签名必须与已安装版本一致。此电脑的 `~/.android/debug.keystore` 与历史版本证书一致，脚本会验证证书指纹后使用它。其他电脑需要复制原密钥，并完整配置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。不要生成新密钥替代原密钥。

Gitee 仓库必须公开。默认目标为 `young-patronum/ruan-cun-drink-water`。如需变更，可设置 `GITEE_REPOSITORY=owner/repository`；构建配置和上传脚本使用同一个值。

## 发布新版本

1. 递增 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`，将修改提交到本地 `main`。
2. 在干净的工作区运行：

```bash
./scripts/publish-release.sh
```

脚本先运行发布脚本测试、Android 单元测试、`lintRelease` 和 `assembleRelease`，核对 APK 包名、版本、非调试属性及历史签名证书。随后显示版本、源码提交、APK 路径和 SHA-256，在确认后把本地 `main` 同步至 GitHub 和 Gitee，再创建两端正式发行版、上传相同 APK 并核对下载内容。标签指向 APK 对应的源码提交。

两端都使用普通快进推送。若任何一端的 `main` 有本地尚未合入的提交，脚本会在推送前报错；先合并这些提交再重试。发布中途失败时，两端可能暂时不同步，修复后可以重复运行同步或复用原安装包继续上传。脚本不强制覆盖分支、标签或同版本的不同 APK。

自动化调用可加 `--yes` 跳过上传确认；已缓存构建依赖时可加 `--offline`。

## 只编译或复用本地 APK

只测试和生成签名包，不连接发布平台：

```bash
./scripts/publish-release.sh --prepare-only
```

产物保存到 `app/build/local-releases/v版本号/`，包含 APK、`release.json` 和发行说明。上传失败后应复用这些文件，避免重新编译产生不同内容：

```bash
./scripts/publish-release.sh --apk app/build/local-releases/v1.3.29/RuanCunDrink-1.3.29-release.apk --yes
```

`--apk` 不重新构建，仍会验证签名、版本及源码提交，并从同目录 `release.json` 核对原始哈希。没有该元数据的现有签名包可通过 `--source-commit 完整提交SHA` 指定来源提交。已有同名安装包会验证哈希；相同则跳过上传，不同则报错。复用旧版本 APK 时同步当前 `main`，发行标签仍指向旧版 APK 的源码提交。

## 只同步源码

每次提交后使用：

```bash
bash scripts/sync-gitee-source.sh
```

此命令从本地读取同一个令牌文件，将当前干净的 `main` 推送至 GitHub 和 Gitee，并确认两端指向同一提交，不编译或发布 APK。也可运行 `./scripts/publish-release.sh --sync-only`。单独执行 `git push origin` 只推送 GitHub，需要接着运行上述同步命令。
