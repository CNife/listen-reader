## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues at `CNife/listen-reader`. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context domain docs: root `GLOSSARY.md` and `docs/adr/`. See `docs/agents/domain.md`.

## No-mistakes 发布流程

发布分支时：

1. 在功能分支上提交改动。
2. 运行 `no-mistakes axi run --intent "<本次要达成的目标>"`：依次执行 intent → review → test → document → lint → push → PR。
3. PR 由潭渊审阅批准后合并。

`.no-mistakes.yaml` 里会执行命令或改变门禁的字段（`no_ci`、`commands.*`、`test.*`、`ci.*`）只从 `main` 的可信副本读取，合入 `main` 后才生效；在 `no_ci` 进入 `main` 前的分支运行需要 `--skip ci`。`commands.test` / `commands.lint` 暂不设置的理由：还没有 Gradle wrapper 与 `app` 模块，命令必然失败；构建可运行后再钉成 Gradle 命令。
