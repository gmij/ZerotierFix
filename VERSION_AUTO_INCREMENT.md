# 自动版本号配置 / Auto Version Configuration

## 概述 / Overview

构建脚本已配置为自动使用GitHub Actions构建号来递增版本号。

The build script has been configured to automatically increment version numbers using the GitHub Actions build number.

## 配置详情 / Configuration Details

### 版本号生成规则 / Version Number Rules

**versionCode（版本代码）:**
- 使用GitHub Actions的`GITHUB_RUN_NUMBER`环境变量
- 本地构建时默认使用14
- 每次CI构建自动递增

**versionName（版本名称）:**
- 格式：`主版本.次版本.构建号`
- 当前：`1.0.{buildNumber}`
- 示例：
  - GitHub Actions build #15 → `1.0.15`
  - GitHub Actions build #100 → `1.0.100`
  - 本地构建 → `1.0.14`

### 代码实现 / Implementation

**文件 / File:** `app/build.gradle`

```groovy
defaultConfig {
    applicationId "net.kaaass.zerotierfix"
    minSdkVersion rootProject.ext.minSdkVersion
    targetSdkVersion rootProject.ext.targetSdkVersion
    
    // 自动递增版本号：使用GitHub Actions的构建号
    // 本地构建时使用默认值
    def buildNumber = System.getenv('GITHUB_RUN_NUMBER') ?: '14'
    versionCode buildNumber.toInteger()
    
    // 版本名称格式：主版本.次版本.构建号
    def majorVersion = 1
    def minorVersion = 0
    versionName "${majorVersion}.${minorVersion}.${buildNumber}"
    
    multiDexEnabled true
    testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
}
```

## 工作原理 / How It Works

### 在GitHub Actions中 / In GitHub Actions

1. GitHub Actions为每次工作流运行设置`GITHUB_RUN_NUMBER`环境变量
2. 构建脚本读取此变量作为versionCode
3. 版本名称自动生成为`1.0.{GITHUB_RUN_NUMBER}`
4. 每次推送/合并后，新的构建会有更大的版本号

### 本地构建 / Local Builds

1. 如果没有`GITHUB_RUN_NUMBER`环境变量
2. 使用默认值`14`作为后备
3. 版本名称为`1.0.14`
4. 可以正常构建和测试

## 使用示例 / Usage Examples

### 查看当前版本 / Check Current Version

在GitHub Actions构建日志中查看：
```
> Task :app:assembleRelease
Building APK with versionCode=15 and versionName=1.0.15
```

### 本地测试版本生成 / Test Version Locally

```bash
# 默认版本
./gradlew assembleRelease
# 输出: versionCode=14, versionName=1.0.14

# 模拟GitHub Actions构建号
export GITHUB_RUN_NUMBER=100
./gradlew assembleRelease
# 输出: versionCode=100, versionName=1.0.100
```

### 手动设置构建号 / Manually Set Build Number

如果需要从特定构建号开始（例如从50开始）：

1. 在GitHub Actions中设置环境变量
2. 或者修改`build.gradle`中的默认值：
   ```groovy
   def buildNumber = System.getenv('GITHUB_RUN_NUMBER') ?: '50'
   ```

## 版本升级策略 / Version Upgrade Strategy

### 何时更新主版本/次版本 / When to Update Major/Minor Version

当需要更新主版本或次版本时，修改`build.gradle`：

```groovy
// 次版本更新（新功能）
def majorVersion = 1
def minorVersion = 1  // 从0改为1
// 结果：1.1.{buildNumber}

// 主版本更新（重大变更）
def majorVersion = 2  // 从1改为2
def minorVersion = 0
// 结果：2.0.{buildNumber}
```

## 优势 / Advantages

✅ **自动化** - 无需手动更新版本号
✅ **唯一性** - 每个构建都有唯一的版本号
✅ **可追溯** - 版本号对应GitHub Actions构建号
✅ **兼容性** - 本地开发仍可正常构建
✅ **简单** - 不需要额外的脚本或工具

## 注意事项 / Notes

1. **构建号不会重置** - GitHub Actions的run number是递增的，不会重置
2. **本地构建版本** - 本地构建始终使用默认版本号(14)
3. **版本冲突** - 确保不要手动发布与CI相同版本号的APK
4. **Play Store** - Google Play Store要求每次上传的versionCode必须大于之前的版本

## 兼容性 / Compatibility

- ✅ GitHub Actions
- ✅ 本地Gradle构建
- ✅ Android Studio
- ✅ 命令行构建

---

**配置日期 / Configuration Date:** 2025-12-26  
**状态 / Status:** ✅ 已实施 / Implemented  
**影响范围 / Impact:** 构建系统 / Build System Only
