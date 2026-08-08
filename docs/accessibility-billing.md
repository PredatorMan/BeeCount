# 无障碍记账维护说明

## 仓库关系

- 自定义应用仓库：`https://github.com/PredatorMan/BeeCount`
- BeeCount 官方上游：`https://github.com/TNT-Likely/BeeCount`
- 在线适配规则：`https://github.com/PredatorMan/BeeCount-Accessibility-Rules`

本地 Git remote 约定：

```text
origin    https://github.com/PredatorMan/BeeCount.git
upstream  https://github.com/TNT-Likely/BeeCount.git
```

合并官方更新时：

```bash
git fetch upstream
git switch main
git pull --ff-only origin main
git merge upstream/main
git push origin main
```

如果官方更新与无障碍模块修改了同一入口文件，需要正常解决 Git 冲突并重新运行测试。无障碍功能主体分别隔离在 Android 的 `accessibilitybilling` 包和 Flutter 的 `features/accessibility_billing` 目录中，以降低后续冲突范围。

## 两类更新

### APK 更新

涉及 Flutter UI、Android 服务、权限、规则 schema 或识别引擎能力时，需要构建并安装新版 APK。

### 适配规则更新

同一规则 schema 内新增 App、页面锚点或字段提取方式时，只修改独立规则仓库的 `rules.json` 并递增 `rulesVersion`。用户在 BeeCount 的无障碍记账设置中点击“更新适配规则”即可，不需要重装 APK。

远程规则只描述包名、页面匹配和字段提取，不能执行代码、点击支付页面、输入内容或申请权限。下载失败、校验失败或版本回退时继续使用上一次有效缓存；没有有效缓存时使用 APK 内置的微信和支付宝规则。

远程新增 App 必须默认关闭，用户需要在“已适配的 App”中主动开启。

## 诊断快照

调试包最多保存五份脱敏 JSON：

```text
/data/user/0/com.tntlikely.beecount.dev.debug/files/accessibility_billing/diagnostics/
```

适配流程：

1. 停留在目标 App 的账单详情或支付结果页面。
2. 通过最近任务切换到 BeeCount，不要先返回到聊天页或首页。
3. 在无障碍记账设置中点击“采集界面诊断快照”。
4. 根据 `packageName`、`activityName`、节点文本、父子关系和坐标编写规则。
5. 使用脱敏测试数据补充 Android 单元测试。
6. 递增远程 `rulesVersion`，发布后用真机手动更新并验证。

真实姓名、完整卡号、订单号和未脱敏账单不得提交到 GitHub。
