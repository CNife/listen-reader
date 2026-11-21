import { readFileSync } from "node:fs";
import path from "node:path";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const EXTRACT_DIR = path.join(ROOT, "app/src/main/assets/extract");
const FIXTURE_DIR = path.join(ROOT, "app/src/androidTest/assets/fixtures");

const SHIM = `
(function () {
  if (window.__listenReaderShim) return;
  window.__listenReaderShim = true;

  function hidden(el) {
    var node = el;
    while (node && node.nodeType === 1) {
      var style = window.getComputedStyle ? window.getComputedStyle(node) : null;
      if (style) {
        if (style.display === "none" || style.visibility === "hidden" ||
            style.visibility === "collapse") return true;
        var opacity = parseFloat(style.opacity);
        if (!isNaN(opacity) && opacity === 0) return true;
      }
      node = node.parentElement;
    }
    return false;
  }

  var zeroRect = { x: 0, y: 0, width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0 };

  Element.prototype.getBoundingClientRect = function () {
    if (hidden(this)) return zeroRect;
    return { x: 0, y: 0, width: 120, height: 32, top: 0, left: 0, right: 120, bottom: 32 };
  };
  Element.prototype.getClientRects = function () {
    return hidden(this) ? [] : [this.getBoundingClientRect()];
  };

  window.__listenReaderFailScrollIntoView = false;
  window.__listenReaderFailScroll = false;
  window.__listenReaderScrollCalls = [];

  Element.prototype.scrollIntoView = function () {
    if (window.__listenReaderFailScrollIntoView || window.__listenReaderFailScroll) {
      throw new Error("scrollIntoView blocked");
    }
  };
  window.scrollTo = function (x, y) {
    if (window.__listenReaderFailScroll) throw new Error("scrollTo blocked");
    window.__listenReaderScrollCalls.push([x, y]);
  };

  Object.defineProperty(Element.prototype, "innerText", {
    configurable: true,
    get: function () { return this.textContent; },
    set: function (value) { this.textContent = value; },
  });
})();
`;

function parseArgs(argv) {
  const args = { fixture: "", url: "" };
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (key === "--fixture") args.fixture = argv[++i] ?? "";
    else if (key === "--url") args.url = argv[++i] ?? "";
    else throw new Error(`未知参数：${key}`);
  }
  if (!args.fixture) throw new Error("缺少 --fixture");
  if (!args.url) throw new Error("缺少 --url");
  return args;
}

async function loadJsdom() {
  try {
    return await import("jsdom");
  } catch {
    process.stderr.write(
      "缺少 jsdom 依赖：请在仓库根目录执行 `npm ci`（package.json 已声明 devDependency）。\n",
    );
    process.exit(3);
  }
}

const args = parseArgs(process.argv.slice(2));
const { JSDOM, VirtualConsole } = await loadJsdom();

const fixturePath = path.join(FIXTURE_DIR, args.fixture);
const html = readFileSync(fixturePath, "utf8");
const templates = JSON.parse(readFileSync(path.join(EXTRACT_DIR, "templates.json"), "utf8"));

const consoleErrors = [];
const virtualConsole = new VirtualConsole();
virtualConsole.on("jsdomError", (error) => {
  const message = String(error && error.message ? error.message : error);
  const navigationUnsupported = message.includes("Not implemented") && message.includes("navigation");
  if (!navigationUnsupported) consoleErrors.push(message);
});

const dom = new JSDOM(html, {
  url: args.url,
  runScripts: "dangerously",
  pretendToBeVisual: true,
  virtualConsole,
});
const { window } = dom;
window.eval(SHIM);

function loadScript(name) {
  try {
    window.eval(readFileSync(path.join(EXTRACT_DIR, name), "utf8"));
  } catch (error) {
    throw new Error(`注入 ${name} 失败：${error && error.message ? error.message : error}`);
  }
}

loadScript("readability.js");
loadScript("l1.js");
loadScript("l3.js");

function callL1(options) {
  const raw = window.__listenReaderL1(
    JSON.stringify({ url: args.url, domStable: true, templates, ...options }),
  );
  const parsed = JSON.parse(raw);
  if (parsed && parsed.error) throw new Error(`L1 返回错误：${parsed.error}`);
  return parsed;
}

function callCollect(phrases) {
  return JSON.parse(window.__listenReaderL3Collect(JSON.stringify(phrases ?? [])));
}

function readabilityMeta() {
  const Readability = window.Readability;
  if (typeof Readability !== "function") {
    return { present: false, textLength: 0, title: null };
  }
  const article = new Readability(window.document.cloneNode(true)).parse();
  return {
    present: true,
    textLength: article?.textContent ? article.textContent.length : 0,
    title: article?.title ?? null,
  };
}

const TEMPLATE_SELECTOR_FIELDS = [
  "titleSelector",
  "contentSelector",
  "removeSelectors",
  "revealSelectors",
  "waitForSelector",
];

function validateTemplateSelectors(templateSet) {
  const list = Array.isArray(templateSet) ? templateSet : templateSet.templates ?? [];
  const invalid = [];
  for (const template of list) {
    for (const field of TEMPLATE_SELECTOR_FIELDS) {
      for (const selector of template[field] ?? []) {
        try {
          window.document.querySelectorAll(selector);
        } catch (error) {
          invalid.push({ template: template.name, field, selector, message: String(error.message) });
        }
      }
    }
  }
  return invalid;
}

function handle(command) {
  switch (command.op) {
    case "meta":
      return { ok: true, readability: readabilityMeta(), consoleErrors };
    case "validate-templates":
      return { ok: true, invalid: validateTemplateSelectors(command.templates ?? templates) };
    case "extract":
      return { ok: true, result: callL1(command.options ?? {}) };
    case "collect":
      return { ok: true, candidates: callCollect(command.phrases) };
    case "click":
      return {
        ok: true,
        clicked: window.__listenReaderL3Click(command.index),
        scrollCalls: window.__listenReaderScrollCalls.length,
      };
    case "fail-scroll-into-view":
      window.__listenReaderFailScrollIntoView = true;
      return { ok: true };
    case "fail-scroll-all":
      window.__listenReaderFailScrollIntoView = true;
      window.__listenReaderFailScroll = true;
      return { ok: true };
    case "close":
      return { ok: true };
    default:
      throw new Error(`未知命令：${command.op}`);
  }
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

for await (const line of rl) {
  if (!line.trim()) continue;
  let response;
  try {
    const command = JSON.parse(line);
    response = handle(command);
    if (command.op === "close") {
      process.stdout.write(`${JSON.stringify(response)}\n`);
      break;
    }
  } catch (error) {
    response = { ok: false, error: error && error.message ? error.message : String(error) };
  }
  process.stdout.write(`${JSON.stringify(response)}\n`);
}

try {
  window.close();
} catch (error) {
  process.stderr.write(`关闭 jsdom 失败：${error && error.message ? error.message : error}\n`);
  process.exit(1);
}
process.exit(0);
