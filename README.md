# 山软喝水

GitHub 仓库：<https://github.com/Leenkee/ShanRuanDrinkWater>

这是一个从 `Duoheshui` 通信流程中独立出来的精简 Android 应用，只实现以下功能：

- 手机验证码登录或手机号密码登录
- 扫码或手动绑定热水、冷水设备
- 远程发送热水、冷水出水指令
- 桌面小组件快捷进入冷热水控制和临时设备扫码
- 查询并刷新余额
- 从 GitHub Releases 检查并下载新版本

应用使用 Kotlin、Jetpack Compose 和 Material Design 3。业务状态由 `ViewModel` 管理，登录会话与设备码仅保存在本机，密码不会持久化；退出登录会清除登录信息，但保留设备绑定。

## 打开与构建

用 `/home/young/Downloads/android-studio/bin/studio.sh` 打开本目录：

```text
/home/young/ShanRuanDrinkWater
```

也可以在终端中构建：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug \
  -Dorg.gradle.java.home=/home/young/Downloads/android-studio/jbr
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 协议说明

应用兼容参考项目使用的小天同学旧接口与 DES/CBC 加密格式。旧服务目前使用 HTTP，因此网络安全配置只对以下两个指定域名开放明文流量，其他域名仍默认禁止：

- `newxiaotian.tianji-inc.com`
- `iot.tianji-inc.com`

UI 会在每次发送指令前要求二次确认。

## GitHub Actions 发布

工作流 `.github/workflows/android-release.yml` 会在推送到 `main` 时测试并生成签名 APK；推送 `v*` 标签时，还会自动创建 GitHub Release 并附加 APK。仓库需要配置以下 Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

应用内更新源由构建参数 `GITHUB_REPOSITORY=owner/repository` 注入。GitHub Actions 会自动使用当前仓库名；本地构建时可手动传入：

```bash
./gradlew assembleRelease -PGITHUB_REPOSITORY=Leenkee/ShanRuanDrinkWater
```

发布新版本时，先更新 `versionName` 和 `versionCode`，再创建与版本一致的标签，例如：

```bash
git tag v1.3.8
git push origin main v1.3.8
```
