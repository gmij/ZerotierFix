# Per-App VPN Routing Feature Implementation

## 概述 (Overview)

本次更新实现了"指定应用使用代理"的功能，允许用户选择哪些应用通过 ZeroTier VPN 路由，而不是所有流量都通过 VPN（全局路由）。

This update implements the "per-app VPN routing" feature, allowing users to select which applications route through ZeroTier VPN, instead of routing all traffic (global routing).

## 功能特性 (Features)

### 1. 两种路由模式 (Two Routing Modes)

- **全局路由模式** (Global Routing): 所有流量通过 ZeroTier VPN（原有功能）
- **Per-App 路由模式** (Per-App Routing): 只有选中的应用通过 ZeroTier VPN，其他应用直连

### 2. 应用列表 (App List)

- 罗列主机中所有应用（包括系统应用和用户应用）
- Lists all apps on the device (including system apps and user apps)
- 可以筛选显示/隐藏系统应用
- Can filter to show/hide system apps
- 显示应用图标、名称、包名和类型
- Shows app icon, name, package name, and type

### 3. 灵活选择 (Flexible Selection)

- 通过复选框选择哪些应用通过 VPN
- Use checkboxes to select which apps go through VPN
- 实时显示已选择的应用数量
- Real-time display of selected app count
- 设置自动保存到数据库
- Settings automatically saved to database

## 实现细节 (Implementation Details)

### 新增文件 (New Files)

#### 数据模型 (Data Models)
- `app/src/main/java/net/kaaass/zerotierfix/model/AppRouting.java`
  - 存储应用路由配置的实体类
  - Entity for storing per-app routing configuration
  
- `app/src/main/java/net/kaaass/zerotierfix/model/AppInfo.java`
  - UI 层使用的应用信息模型
  - App information model for UI layer

#### 工具类 (Utility Classes)
- `app/src/main/java/net/kaaass/zerotierfix/util/AppUtils.java`
  - 获取系统已安装应用的工具类
  - Utility for fetching installed applications

#### UI 组件 (UI Components)
- `app/src/main/java/net/kaaass/zerotierfix/ui/AppRoutingActivity.java`
  - 应用路由设置的 Activity
  - Activity for app routing settings
  
- `app/src/main/java/net/kaaass/zerotierfix/ui/AppRoutingFragment.java`
  - 主要的 UI 逻辑和业务处理
  - Main UI logic and business handling
  
- `app/src/main/java/net/kaaass/zerotierfix/ui/adapter/AppRoutingAdapter.java`
  - RecyclerView 适配器
  - RecyclerView adapter for app list

#### 布局文件 (Layout Files)
- `app/src/main/res/layout/fragment_app_routing.xml`
  - 主界面布局
  - Main screen layout
  
- `app/src/main/res/layout/list_item_app_routing.xml`
  - 应用列表项布局
  - App list item layout
  
- `app/src/main/res/menu/menu_network_detail.xml`
  - 网络详情菜单
  - Network detail menu

### 修改文件 (Modified Files)

1. **AndroidManifest.xml**
   - 添加 `QUERY_ALL_PACKAGES` 权限（Android 11+ 必需）
   - Added `QUERY_ALL_PACKAGES` permission (required for Android 11+)
   - 注册 `AppRoutingActivity`

2. **NetworkConfig.java**
   - 添加 `perAppRouting` 字段，用于标记是否启用 per-app 路由
   - Added `perAppRouting` field to track routing mode

3. **ZeroTierOneService.java**
   - 更新 `configureAllowedDisallowedApps()` 方法
   - Updated `configureAllowedDisallowedApps()` method
   - 实现 per-app VPN 路由逻辑
   - Implemented per-app VPN routing logic

4. **NetworkDetailActivity.java**
   - 添加菜单项，提供访问应用路由设置的入口
   - Added menu item for accessing app routing settings

5. **strings.xml**
   - 添加所有相关的字符串资源
   - Added all related string resources

## 使用说明 (Usage Instructions)

### 访问功能 (Accessing the Feature)

1. 连接到一个 ZeroTier 网络
2. 在网络列表中点击网络查看详情
3. 点击右上角菜单按钮
4. 选择"应用路由设置" (App Routing)

### 配置路由模式 (Configuring Routing Mode)

1. 使用顶部的开关切换"全局路由"或"Per-App 路由"模式
2. 在 Per-App 模式下：
   - 勾选"显示系统应用"可以看到系统应用
   - 点击应用项或复选框来选择/取消选择
   - 已选择的应用将通过 ZeroTier VPN 路由
   - 未选择的应用将直接连接（不经过 VPN）

## 技术实现 (Technical Implementation)

### Android VPN API

使用 Android 5.0+ (API 21+) 的 VPN API:
- `VpnService.Builder.addAllowedApplication(packageName)` - 允许应用通过 VPN
- `VpnService.Builder.addDisallowedApplication(packageName)` - 禁止应用通过 VPN

### 数据库设计 (Database Design)

#### AppRouting 表
```
- id (Long, Primary Key)
- networkId (Long) - 关联的网络 ID
- packageName (String) - 应用包名
- routeViaVpn (Boolean) - 是否通过 VPN
```

#### NetworkConfig 表新增字段
```
- perAppRouting (Boolean) - 是否启用 per-app 路由模式
```

### 工作流程 (Workflow)

1. 用户在 UI 中选择路由模式和应用
2. 设置保存到 SQLite 数据库（使用 GreenDAO ORM）
3. VPN 服务启动时，从数据库读取配置
4. 根据配置调用相应的 VPN API 来设置路由规则

## 构建说明 (Build Instructions)

### 前提条件 (Prerequisites)

由于 GreenDAO 需要生成 DAO 类，您需要：

1. 确保有网络连接以下载 Gradle 依赖
2. 运行以下命令生成 DAO 类：

```bash
./gradlew build
```

或者如果只需要生成 DAO 类：

```bash
./gradlew :app:compileDebugJava
```

### 生成的文件 (Generated Files)

GreenDAO 将自动生成以下文件：
- `AppRoutingDao.java` - AppRouting 实体的 DAO
- `DaoSession.java` 将被更新以包含 AppRoutingDao
- `DaoMaster.java` 将被更新以包含新的数据库表

## 注意事项 (Notes)

### Android 版本兼容性

- **Android 5.0+ (API 21+)**: 完整支持 per-app VPN 功能
- **Android 5.0 以下**: 仅支持全局路由模式

### 权限说明

- `QUERY_ALL_PACKAGES`: Android 11+ 需要此权限来查询所有已安装的应用
  - 此权限可能需要在 Google Play 上提交应用时进行额外说明

### 性能考虑

- 应用列表在后台线程异步加载
- 使用 SwipeRefreshLayout 支持下拉刷新
- RecyclerView 用于高效显示大量应用

## 未来改进 (Future Improvements)

可以考虑的增强功能：
1. 搜索和过滤应用
2. 批量选择/取消选择
3. 应用分组或收藏
4. 导入/导出配置
5. 每个应用的流量统计

## 测试建议 (Testing Recommendations)

1. 测试全局路由模式是否正常工作
2. 测试 per-app 路由模式下：
   - 选中的应用是否通过 VPN
   - 未选中的应用是否直连
3. 测试切换路由模式时的行为
4. 测试系统应用的显示/隐藏
5. 测试数据持久化（重启后设置是否保留）
6. 测试在不同 Android 版本上的兼容性

## 故障排查 (Troubleshooting)

### 构建错误

如果遇到 DAO 相关的编译错误：
1. 清理构建：`./gradlew clean`
2. 重新生成 DAO：`./gradlew :app:greendaoGenerator`
3. 重新构建：`./gradlew build`

### 运行时错误

如果应用崩溃或功能不工作：
1. 检查 logcat 日志
2. 确认 VPN 权限已授予
3. 确认 QUERY_ALL_PACKAGES 权限已声明
4. 检查数据库是否正确更新（schema version）

## 联系信息 (Contact)

如有问题或建议，请在 GitHub 上提交 Issue。

---

**版本**: 1.0.11 (待发布)
**日期**: 2025-12-26
