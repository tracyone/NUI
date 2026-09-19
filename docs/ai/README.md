# docs/ai/ — 项目 AI 开发 Skill 库

这个目录放**给 AI 编码助手看的高频场景操作指南**。
入口文件是项目根目录的 [`AGENTS.md`](../../AGENTS.md)；这里是它的展开。

## 已有 Skill

| 文件 | 解决什么场景 |
|---|---|
| [amap-broadcast.md](amap-broadcast.md) | 改导航/巡航 HUD、高德广播状态机、昼夜跟随、超速/拥堵阈值 |
| [build-and-deploy.md](build-and-deploy.md) | 出 APK、装机、抓日志、常见报错排错 |
| [adding-feature.md](adding-feature.md) | 新增设置项/页面/语音场景/音乐 App 支持的标准流程 |

## 怎么用

- AI 工具（Claude Code / Cursor / Copilot 等）读到根目录 `AGENTS.md` 后，会在任务命中上述场景时自动读本目录对应文件。
- 人也可以直接读——它们是带"红线"的开发 checklist。

## 怎么新增 / 维护

1. 当某个场景 AI 反复"踩坑"或反复要问同类问题时，把它沉淀成一个新 `.md`。
2. 命名：用任务名，小写连字符，如 `theme-refactor.md`。
3. 格式保持一致：触发场景 → 关键文件表 → 步骤/规则 → 验证 → 红线。
4. 在本文件和根目录 `AGENTS.md` 第 9 节加一行索引。
5. **项目演进后要同步更新**——命令、类名、路径变了就改，别让它过时误导人。
