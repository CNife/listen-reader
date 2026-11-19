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
