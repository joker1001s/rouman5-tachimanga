# 肉漫屋 Tachimanga 单源仓库

这是一个只包含 **肉漫屋（rouman5.com）** 的 Tachimanga/Mihon 扩展私有仓库。

## 结构

仓库 `main` 分支只保存扩展源码和 GitHub Actions。

GitHub Actions 会：

1. 拉取最新 `keiyoushi/extensions-source`
2. 注入 `src/zh/rouman5`
3. 编译 Release APK
4. 使用你自己的 Android 签名密钥重新签名
5. 生成只有一个扩展的 `index.min.json`
6. 推送到 `repo` 分支

Tachimanga 最终添加：

```text
https://raw.githubusercontent.com/你的用户名/rouman5-tachimanga/repo/index.min.json
```

Tachimanga 的扩展仓库 URL 通常以 `index.min.json` 结尾，仓库索引同时提供 APK 文件名、包名、版本和 source 元数据。

## GitHub Secrets

在 GitHub：

`Settings -> Secrets and variables -> Actions -> New repository secret`

创建：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

生成签名密钥时使用：

```powershell
keytool -genkeypair -v `
  -keystore rouman5-release.jks `
  -alias rouman5 `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

把 JKS 转 Base64：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\rouman5-release.jks"))
```

把输出复制到 `ANDROID_KEYSTORE_BASE64`。

## 第一次发布

推送到 `main` 后：

`Actions -> Build Rouman5 -> Run workflow`

成功后，Action 会创建/更新 `repo` 分支。

然后在 Tachimanga 添加：

```text
https://raw.githubusercontent.com/你的用户名/rouman5-tachimanga/repo/index.min.json
```

## 更新

修改 `Rouman5.kt` 后必须把：

```kotlin
versionCode = 1
```

增加为：

```kotlin
versionCode = 2
```

工作流会自动把版本名从 `1.6.1` 变成 `1.6.2`，并同步写入 `index.min.json`。

然后 push 到 `main`。

不要更换签名密钥，否则 Android 会把它当成不同签名的 APK。

## 当前站点适配

肉漫屋当前页面包含 18+ 进入页；浏览器环境可以进入后看到漫画列表、搜索结果、漫画详情和章节页面。阅读页的图片使用懒加载，因此阅读器部分使用 WebView，并在页面滚动过程中收集图片 URL。

仅适配 `https://rouman5.com`。
