## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues at `CNife/listen-reader`. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context domain docs: root `GLOSSARY.md` and `docs/adr/`. See `docs/agents/domain.md`.

## No-mistakes 验证管道

分支发布统一走本地 no-mistakes gate，不直接 `git push origin`：

1. 在功能分支上提交改动。
2. 运行 `no-mistakes axi run --intent "<本次要达成的目标>"`，由它驱动 intent → review → test → document → lint → push → PR。
3. PR 合并前必须由潭渊批准。

仓库配置在 `.no-mistakes.yaml`：

- `no_ci: true`：仓库目前没有 CI workflow，CI 步骤把「零检查」判为通过而不是空等；真实 workflow 出现后应删除该声明。
- `commands.test` / `commands.lint` 暂不设置：还没有 Gradle wrapper 和 `app` 模块，固定命令必然失败；构建可运行后再钉成 canonical 的 Gradle 命令。
- 会执行命令或改变门禁策略的字段（`no_ci`、`commands.*`、`test.*`、`ci.*`、`document.instructions`、`review.path_instructions` 等）只从默认分支的可信副本读取，因此这类配置合入 `main` 后才生效；首次合并前的分支运行可能需要 `--skip ci`。
