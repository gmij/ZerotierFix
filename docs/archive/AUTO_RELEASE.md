# 自动发布功能 / Auto Release Feature

## 概述 / Overview

当代码推送到 `master` 分支时，GitHub Actions 会自动创建 Release，包含构建的 APK 和变更历史。

When code is pushed to the `master` branch, GitHub Actions will automatically create a Release with the built APK and changelog.

## 功能特性 / Features

### ✅ 自动创建 Release / Automatic Release Creation

- **触发条件**: 仅在 `master` 分支推送时触发
- **版本标签**: 自动使用 `v1.0.{BUILD_NUMBER}` 格式
- **发布名称**: `ZerotierFix v1.0.{BUILD_NUMBER}`

### ✅ APK 文件管理 / APK File Management

- **文件命名**: `ZerotierFix-v1.0.{BUILD_NUMBER}.apk`
- **自动上传**: APK 文件自动上传到 Release
- **签名**: 使用配置的密钥库进行签名

### ✅ 变更日志生成 / Changelog Generation

自动生成的变更日志包含：

The automatically generated changelog includes:

- 📦 构建信息（版本号、构建号、构建日期、提交哈希）
- 📝 自上次 release 以来的所有 commit 记录
- 📥 下载说明

## 工作流程 / Workflow

```
Push to master
    ↓
Build APK (assembleRelease)
    ↓
Rename APK with version
    ↓
Generate Changelog
    ↓
Create GitHub Release
    ↓
Upload APK to Release
```

## 版本号规则 / Version Numbering

- **格式 / Format**: `v{major}.{minor}.{build}`
- **示例 / Example**: 
  - Build #15 → `v1.0.15`
  - Build #100 → `v1.0.100`
- **来源 / Source**: 使用 `GITHUB_RUN_NUMBER` 环境变量

## Changelog 格式 / Changelog Format

```markdown
# ZerotierFix v1.0.15

## 📦 构建信息 / Build Information
- **版本号 / Version:** v1.0.15
- **构建号 / Build Number:** 15
- **构建日期 / Build Date:** 2025-12-31 01:30:00 UTC
- **提交 / Commit:** abc123def456...

## 📝 更新内容 / Changelog

Changes since v1.0.14:

- Fix Per-App routing issues (a1b2c3d)
- Update dependencies (e4f5g6h)
- Improve UI responsiveness (i7j8k9l)

## 📥 下载 / Download

下载 `ZerotierFix-v1.0.15.apk` 文件并安装到您的Android设备。

Download `ZerotierFix-v1.0.15.apk` and install it on your Android device.
```

## 其他分支行为 / Other Branch Behavior

- **非 master 分支**: 只构建 APK 并上传为 Artifact，不创建 Release
- **Pull Request**: 只构建 APK 进行测试，不创建 Release

## Release 位置 / Release Location

所有自动创建的 Release 都可以在以下位置找到：

All automatically created Releases can be found at:

https://github.com/gmij/ZerotierFix/releases

## 手动触发 / Manual Trigger

如果需要手动触发构建和发布，可以使用 GitHub Actions 的 `workflow_dispatch` 功能：

To manually trigger a build and release, use the `workflow_dispatch` feature in GitHub Actions:

1. 访问 Actions 页面 / Visit the Actions page
2. 选择 "Build APP" 工作流 / Select the "Build APP" workflow
3. 点击 "Run workflow" / Click "Run workflow"
4. 选择 `master` 分支 / Select the `master` branch
5. 点击运行 / Click run

## 配置要求 / Configuration Requirements

### 必需的 Secrets / Required Secrets

为了成功创建签名的 APK 和 Release，需要配置以下 GitHub Secrets：

To successfully create signed APKs and Releases, configure the following GitHub Secrets:

- `KEYSTORE_BASE64`: Base64 编码的密钥库文件
- `KEYSTORE_PASSWORD`: 密钥库密码
- `KEY_ALIAS`: 密钥别名
- `KEY_PASSWORD`: 密钥密码
- `GITHUB_TOKEN`: 自动提供，用于创建 Release

> **注意**: `GITHUB_TOKEN` 由 GitHub Actions 自动提供，无需手动配置。

## 技术实现 / Technical Implementation

### 使用的 Actions / Actions Used

1. **actions/checkout@v4**: 检出代码，`fetch-depth: 0` 获取完整历史
2. **actions/setup-java@v3**: 设置 JDK 17 环境
3. **actions/upload-artifact@v4**: 上传 APK Artifact
4. **softprops/action-gh-release@v1**: 创建 GitHub Release

### 关键配置 / Key Configurations

```yaml
# 仅在 master 分支触发 Release 创建
if: github.ref == 'refs/heads/master' && success()

# 使用环境变量传递版本号
echo "VERSION=${VERSION}" >> $GITHUB_ENV

# 生成 changelog
git log ${PREV_TAG}..HEAD --pretty=format:"- %s (%h)" --no-merges
```

## 升级版本号 / Upgrading Version Numbers

### 更新主版本或次版本 / Update Major or Minor Version

要更新主版本或次版本，编辑 `app/build.gradle`:

To update the major or minor version, edit `app/build.gradle`:

```groovy
// 当前 / Current
def majorVersion = 1
def minorVersion = 0

// 更新到 1.1.x / Update to 1.1.x
def majorVersion = 1
def minorVersion = 1

// 更新到 2.0.x / Update to 2.0.x
def majorVersion = 2
def minorVersion = 0
```

同时更新工作流中的版本号生成脚本：

Also update the version generation in the workflow:

```yaml
VERSION="1.1.${{ github.run_number }}"  # 或 "2.0.${{ github.run_number }}"
```

## 故障排除 / Troubleshooting

### Release 创建失败 / Release Creation Failed

**可能原因 / Possible Causes:**

1. **Tag 已存在**: 如果 tag 已存在，创建会失败
   - 解决方案: 删除旧 tag 或使用新版本号

2. **权限不足**: GITHUB_TOKEN 没有足够权限
   - 解决方案: 检查仓库设置中的 Actions 权限

3. **APK 文件不存在**: 构建失败或路径错误
   - 解决方案: 检查构建日志，确认 APK 生成成功

### Changelog 为空 / Empty Changelog

**可能原因 / Possible Causes:**

1. **没有上一个 tag**: 首次 release
   - 预期行为: 会显示 "Initial release"

2. **没有新 commits**: 没有新的提交
   - 预期行为: Changelog 可能为空

## 最佳实践 / Best Practices

1. **定期发布**: 在 master 分支上的每次重要更新后推送
2. **清晰的提交信息**: 使用描述性的 commit message，因为它们会出现在 changelog 中
3. **测试后再合并**: 在其他分支测试完成后再合并到 master
4. **语义化版本**: 根据变更类型适当更新主版本和次版本号

## 相关文档 / Related Documentation

- [VERSION_AUTO_INCREMENT.md](VERSION_AUTO_INCREMENT.md) - 版本号配置详细说明 (包含 GITHUB_RUN_NUMBER 的使用)
- [GitHub Actions 文档](https://docs.github.com/en/actions)
- [softprops/action-gh-release](https://github.com/softprops/action-gh-release)
- [GitHub Releases 文档](https://docs.github.com/en/repositories/releasing-projects-on-github)

---

**实施日期 / Implementation Date:** 2025-12-31  
**状态 / Status:** ✅ 已实施 / Implemented  
**维护者 / Maintainer:** GitHub Actions Workflow
