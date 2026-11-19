/*
 * Listen Reader L1 extractor.
 *
 * Runs inside the WebView page context after the DOM has become stable.
 * Strategy: site template selectors first, then a competition between
 * Mozilla Readability and a text/sentence density picker. The winner is
 * converted to clean Markdown and checked against quality gates.
 *
 * This file must stay in sync with `capture/webview/WebViewPageSession.kt`
 * (which concatenates readability.js + l1.js and calls `__listenReaderL1`).
 */
(function () {
  "use strict";

  var MIN_PROSE_CHARS = 300;
  var MAX_LINK_DENSITY = 0.25;
  var FOLD_PHRASES = ["展开全文", "展开阅读全文", "继续阅读", "登录后查看", "阅读全文", "显示更多"];

  var NOISE_TAGS = [
    "script", "style", "noscript", "iframe", "form", "input", "textarea",
    "select", "button", "svg", "canvas", "audio", "video", "object", "embed"
  ];

  var NOISE_SELECTORS = [
    "nav", "header", "footer", "aside",
    "[class*='comment']", "[id*='comment']",
    "[class*='sidebar']", "[id*='sidebar']",
    "[class*='related']", "[id*='related']",
    "[class*='recommend']", "[id*='recommend']",
    "[class*='advert']", "[class~='ad']", "[id*='advert']",
    "[class*='newsletter']", "[class*='subscribe']",
    "[class*='share']", "[class*='social']",
    "[class*='qrcode']", "[class*='qr-code']", "[id*='qrcode']",
    "[class*='copyright']", "[class*='friend-link']", "[class*='toolbar']"
  ];

  var PROMO_CLASS_RE = /(advert|sponsor|promo|qrcode|qr-code|copyright|related|recommend|share|social|comment|newsletter|subscribe)/i;
  var COPYRIGHT_RE = /(版权所有|版权归|©|Copyright\s|All rights reserved|转载请注明|转载请|扫码关注|关注公众号|长按识别)/i;
  var QR_ALT_RE = /(二维码|扫码|qr\s*code)/i;
  var LAZY_IMAGE_ATTRS = ["data-src", "data-original", "data-actualsrc", "_src", "data-lazy-src", "data-url"];

  function textOf(el) {
    return ((el && (el.innerText || el.textContent)) || "").replace(/\u00a0/g, " ").trim();
  }

  function normalizeBodyText(text) {
    return (text || "")
      .replace(/\u00a0/g, " ")
      .replace(/\r/g, "")
      .replace(/[ \t]+/g, " ")
      .replace(/ ?\n ?/g, "\n")
      .replace(/\n{3,}/g, "\n\n")
      .trim();
  }

  function normalizeInline(text) {
    var s = (text || "").replace(/\u00a0/g, " ").replace(/[ \t]+/g, " ");
    s = s.replace(/ *\n */g, "\n");
    // Chinese paragraphs wrap across visual lines; join them without spaces.
    s = s.replace(
      /([\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef])\n([\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef])/g,
      "$1$2"
    );
    return s.trim();
  }

  function inlineText(el) {
    return normalizeInline(textOf(el));
  }

  function isElement(node) {
    return node && node.nodeType === 1;
  }

  function hasVisibleBox(el) {
    if (!isElement(el)) return false;
    return (el.innerText || "").trim().length > 0;
  }

  function removeSelectorAll(root, selector) {
    try {
      var nodes = root.querySelectorAll(selector);
      for (var i = nodes.length - 1; i >= 0; i--) {
        var node = nodes[i];
        if (node.parentNode) node.parentNode.removeChild(node);
      }
    } catch (e) {
      /* ignore invalid selectors from templates */
    }
  }

  function applyRemoveSelectors(root, selectors) {
    if (!selectors) return;
    for (var i = 0; i < selectors.length; i++) removeSelectorAll(root, selectors[i]);
  }

  function removeNoiseTags(root) {
    for (var i = 0; i < NOISE_TAGS.length; i++) removeSelectorAll(root, NOISE_TAGS[i]);
  }

  function liftLazyImages(root) {
    var images = root.querySelectorAll("img");
    for (var i = 0; i < images.length; i++) {
      var img = images[i];
      var current = img.getAttribute("src") || "";
      var candidate = "";
      for (var j = 0; j < LAZY_IMAGE_ATTRS.length; j++) {
        var value = img.getAttribute(LAZY_IMAGE_ATTRS[j]);
        if (value && value.replace(/\s/g, "")) {
          candidate = value;
          break;
        }
      }
      if (candidate && (!current || current.indexOf("data:image") === 0)) {
        img.setAttribute("src", candidate);
      }
      img.removeAttribute("srcset");
      img.removeAttribute("loading");
    }
  }

  function removePromoElements(root) {
    var all = root.querySelectorAll("*");
    for (var i = all.length - 1; i >= 0; i--) {
      var el = all[i];
      if (!el.parentNode) continue;
      var cls = (el.getAttribute("class") || "") + " " + (el.getAttribute("id") || "");
      if (PROMO_CLASS_RE.test(cls)) {
        el.parentNode.removeChild(el);
        continue;
      }
      if (el.tagName.toLowerCase() === "img") {
        var alt = el.getAttribute("alt") || "";
        var src = el.getAttribute("src") || "";
        if (QR_ALT_RE.test(alt) || QR_ALT_RE.test(src)) el.parentNode.removeChild(el);
      }
    }
  }

  function removeCopyrightTail(root) {
    var blocks = root.querySelectorAll("p, div, section, li");
    for (var i = blocks.length - 1; i >= 0; i--) {
      var el = blocks[i];
      if (!el.parentNode) continue;
      var text = textOf(el);
      if (text.length > 0 && text.length <= 160 && COPYRIGHT_RE.test(text)) {
        // Only drop trailing boilerplate, never a long body paragraph.
        if (!el.querySelector("p, div > p, h1, h2, h3, img")) el.parentNode.removeChild(el);
      }
    }
  }

  function cleanContainer(holder, template) {
    removeNoiseTags(holder);
    applyRemoveSelectors(holder, NOISE_SELECTORS);
    if (template) applyRemoveSelectors(holder, template.removeSelectors);
    removePromoElements(holder);
    liftLazyImages(holder);
    removeCopyrightTail(holder);
    // Drop empty wrappers left behind by the removals (keep images/headings).
    var empty = holder.querySelectorAll("div, section, span, p");
    for (var i = empty.length - 1; i >= 0; i--) {
      var el = empty[i];
      if (!el.parentNode) continue;
      if (!textOf(el) && !el.querySelector("img, h1, h2, h3, code, pre")) {
        el.parentNode.removeChild(el);
      }
    }
  }

  function findFoldPhrase(text) {
    if (!text) return null;
    for (var i = 0; i < FOLD_PHRASES.length; i++) {
      if (text.indexOf(FOLD_PHRASES[i]) >= 0) return FOLD_PHRASES[i];
    }
    return null;
  }

  function linkDensity(el) {
    if (!el) return 0;
    var total = textOf(el).length;
    if (!total) return 0;
    var anchors = el.querySelectorAll("a");
    var linkChars = 0;
    for (var i = 0; i < anchors.length; i++) linkChars += textOf(anchors[i]).length;
    return Math.min(1, linkChars / total);
  }

  function metaContent(doc, selector) {
    var meta = doc.querySelector(selector);
    if (!meta) return null;
    var value = meta.getAttribute("content");
    return value ? normalizeInline(value) : null;
  }

  function firstText(doc, selectors) {
    if (!selectors) return null;
    for (var i = 0; i < selectors.length; i++) {
      var node = doc.querySelector(selectors[i]);
      if (node) {
        var text = normalizeInline(textOf(node));
        if (text) return text;
      }
    }
    return null;
  }

  function metaSelectors(attr, value) {
    return [
      "meta[" + attr + "='" + value + "']",
      'meta[' + attr + "=\"" + value + "\"]"
    ];
  }

  function pickMeta(doc, property) {
    var selectors = metaSelectors("property", property).concat(metaSelectors("name", property));
    for (var i = 0; i < selectors.length; i++) {
      var meta = doc.querySelector(selectors[i]);
      if (meta) {
        var content = meta.getAttribute("content");
        if (content) return normalizeInline(content);
      }
    }
    return null;
  }

  function articleTitle(doc, fallbackEl) {
    var title = null;
    if (fallbackEl) title = firstText(fallbackEl, ["h1", "h2"]);
    if (!title) title = pickMeta(doc, "og:title");
    if (!title) title = normalizeInline(doc.title || "");
    return title ? title.slice(0, 300) : "";
  }

  function publishedAt(doc) {
    var value = pickMeta(doc, "article:published_time");
    if (value) return value;
    var time = doc.querySelector("time[datetime]");
    if (time) {
      var dt = time.getAttribute("datetime");
      if (dt) return normalizeInline(dt);
    }
    var metaPub = doc.querySelector("meta[name='publishdate'], meta[name='pubdate'], meta[itemprop='datePublished']");
    if (metaPub) {
      var content = metaPub.getAttribute("content");
      if (content) return normalizeInline(content);
    }
    return null;
  }

  function byline(doc) {
    return pickMeta(doc, "author") || pickMeta(doc, "article:author");
  }

  function matchTemplate(templates, host, href) {
    var matched = null;
    for (var i = 0; i < templates.length; i++) {
      var template = templates[i];
      if (!template || template.enabled === false || !template.hostPattern) continue;
      if (template.name === "通用网页") continue;
      try {
        if (new RegExp(template.hostPattern, "i").test(host) || new RegExp(template.hostPattern, "i").test(href)) {
          matched = template;
        }
      } catch (e) {
        /* ignore invalid pattern */
      }
    }
    return matched;
  }

  function templateContent(doc, template) {
    if (!template || !template.contentSelector) return null;
    for (var i = 0; i < template.contentSelector.length; i++) {
      var node = doc.querySelector(template.contentSelector[i]);
      if (!node) continue;
      var text = textOf(node);
      if (text.length >= 120) return node;
    }
    return null;
  }

  function templateExtract(doc, template, work) {
    var content = templateContent(work, template);
    if (!content) return null;
    var holder = doc.createElement("div");
    holder.appendChild(content.cloneNode(true));
    cleanContainer(holder, template);
    liftLazyImages(holder);
    var text = textOf(holder);
    if (text.length < 120) return null;
    return {
      element: holder,
      extractor: "template:" + template.name,
      title: firstText(work, template.titleSelector) || articleTitle(work, holder),
      byline: byline(work),
      publishedAt: publishedAt(work),
      templateHit: true
    };
  }

  function readabilityExtract(doc, work) {
    if (typeof Readability !== "function" && typeof window.Readability !== "function") return null;
    var ctor = typeof Readability === "function" ? Readability : window.Readability;
    try {
      var clone = work.cloneNode(true);
      var article = new ctor(clone, {
        charThreshold: 250,
        maxElemsToParse: 30000,
        nbTopCandidates: 5,
        keepClasses: false
      }).parse();
      if (!article || !article.content) return null;
      var holder = doc.createElement("div");
      holder.innerHTML = article.content;
      cleanContainer(holder, null);
      var text = textOf(holder);
      if (!text) return null;
      return {
        element: holder,
        extractor: "readability",
        title: normalizeInline(article.title || "") || articleTitle(work, holder),
        byline: article.byline ? normalizeInline(article.byline) : byline(work),
        publishedAt: article.publishedTime ? normalizeInline(article.publishedTime) : publishedAt(work)
      };
    } catch (e) {
      return null;
    }
  }

  function findDensityCandidate(work) {
    var nodes = work.querySelectorAll("article, main, div, section, td");
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var text = textOf(el);
      if (text.length < 200) continue;
      var ld = linkDensity(el);
      if (ld > 0.5) continue;
      var sentences = (text.match(/[。！？!?；;]/g) || []).length;
      var headings = el.querySelectorAll("h1, h2, h3").length;
      var score = Math.min(text.length, 4000) * (1 - ld) + Math.min(sentences, 80) * 4 + headings * 6;
      if (score > bestScore) {
        bestScore = score;
        best = el;
      }
    }
    return best;
  }

  function densityExtract(doc, work) {
    var candidate = findDensityCandidate(work);
    if (!candidate) return null;
    var holder = doc.createElement("div");
    holder.appendChild(candidate.cloneNode(true));
    cleanContainer(holder, null);
    liftLazyImages(holder);
    var text = textOf(holder);
    if (text.length < 200) return null;
    return {
      element: holder,
      extractor: "density",
      title: articleTitle(work, holder),
      byline: byline(work),
      publishedAt: publishedAt(work)
    };
  }

  function articleScore(text, html, title, docTitle) {
    var score = 0;
    var len = text.length;
    if (len > 200) score += 20;
    if (len > 500) score += 10;
    if (len > 50000) score -= 10;
    var ratio = html.length ? text.length / html.length : 0;
    score += Math.min(Math.round(ratio * 50), 30);
    if (/<h[1-3]/i.test(html)) score += 10;
    if (/<pre/i.test(html)) score += 5;
    if (/<img/i.test(html)) score += 5;
    if (title && docTitle && title !== docTitle) score += 5;
    return Math.max(0, Math.min(100, score));
  }

  function isBlockTag(tag) {
    return (
      /^(p|div|section|article|main|aside|blockquote|ul|ol|li|dl|dt|dd|table|thead|tbody|tr|td|th|pre|figure|figcaption|h1|h2|h3|h4|h5|h6|hr|header|footer|nav)$/.test(tag)
    );
  }

  function inlineMarkdown(node) {
    var out = "";
    for (var i = 0; i < node.childNodes.length; i++) {
      var child = node.childNodes[i];
      if (child.nodeType === 3) {
        out += child.nodeValue;
        continue;
      }
      if (child.nodeType !== 1) continue;
      var tag = child.tagName.toLowerCase();
      if (tag === "br") {
        out += "\n";
        continue;
      }
      if (tag === "a") {
        var text = normalizeInline(textOf(child));
        var href = child.getAttribute("href") || "";
        if (!text) continue;
        if (!href || href.indexOf("javascript:") === 0 || href.charAt(0) === "#") out += text;
        else out += "[" + text.replace(/[\[\]]/g, "") + "](" + href + ")";
        continue;
      }
      if (tag === "img") {
        var src = child.getAttribute("src") || "";
        var alt = normalizeInline(child.getAttribute("alt") || "");
        if (src && src.indexOf("data:image") !== 0) out += "![" + alt.replace(/[\[\]]/g, "") + "](" + src + ")";
        continue;
      }
      if (tag === "code") {
        out += "`" + normalizeInline(textOf(child)).replace(/`/g, "") + "`";
        continue;
      }
      out += inlineMarkdown(child);
    }
    return normalizeInline(out);
  }

  function listMarkdown(node, depth) {
    var ordered = node.tagName.toLowerCase() === "ol";
    var items = [];
    var index = 1;
    for (var i = 0; i < node.children.length; i++) {
      var li = node.children[i];
      if (li.tagName.toLowerCase() !== "li") continue;
      var content = childrenMarkdown(li, depth + 1).trim();
      if (!content) continue;
      var prefix = ordered ? index + ". " : "- ";
      var indent = new Array(depth + 1).join("  ");
      items.push(indent + prefix + content.replace(/\n/g, "\n" + indent + "  "));
      index++;
    }
    return items.join("\n");
  }

  function tableMarkdown(node) {
    var rows = [];
    var trs = node.querySelectorAll("tr");
    for (var i = 0; i < trs.length; i++) {
      var cells = [];
      for (var j = 0; j < trs[i].children.length; j++) {
        var cell = trs[i].children[j];
        if (cell.tagName.toLowerCase() !== "td" && cell.tagName.toLowerCase() !== "th") continue;
        cells.push(normalizeInline(textOf(cell)) || " ");
      }
      if (cells.length) rows.push("| " + cells.join(" | ") + " |");
      if (i === 0 && cells.length) rows.push("|" + cells.map(function () { return " --- "; }).join("|") + "|");
    }
    return rows.join("\n");
  }

  function blockMarkdown(node, depth) {
    if (node.nodeType === 3) return normalizeInline(node.nodeValue);
    if (node.nodeType !== 1) return "";
    var tag = node.tagName.toLowerCase();
    if (tag === "h1" || tag === "h2" || tag === "h3" || tag === "h4" || tag === "h5" || tag === "h6") {
      var level = parseInt(tag.charAt(1), 10);
      var heading = inlineMarkdown(node);
      return heading ? new Array(level + 1).join("#") + " " + heading : "";
    }
    if (tag === "p") return inlineMarkdown(node);
    if (tag === "blockquote") {
      var inner = childrenMarkdown(node, depth + 1).trim();
      if (!inner) return "";
      return inner.split("\n").map(function (line) { return line ? "> " + line : ">"; }).join("\n");
    }
    if (tag === "ul" || tag === "ol") return listMarkdown(node, depth);
    if (tag === "pre") {
      var code = (node.textContent || "").replace(/\u00a0/g, " ").replace(/^\n+|\n+$/g, "");
      return code ? "```\n" + code + "\n```" : "";
    }
    if (tag === "table") return tableMarkdown(node);
    if (tag === "hr") return "---";
    if (tag === "img") {
      var src = node.getAttribute("src") || "";
      if (!src || src.indexOf("data:image") === 0) return "";
      return "![" + normalizeInline(node.getAttribute("alt") || "") + "](" + src + ")";
    }
    if (tag === "figure") {
      var image = node.querySelector("img");
      var caption = node.querySelector("figcaption");
      var figureSrc = image ? image.getAttribute("src") || "" : "";
      if (!figureSrc || figureSrc.indexOf("data:image") === 0) {
        return caption ? inlineMarkdown(caption) : "";
      }
      var alt = normalizeInline((image.getAttribute("alt") || "") || (caption ? textOf(caption) : ""));
      return "![" + alt + "](" + figureSrc + ")";
    }
    return childrenMarkdown(node, depth);
  }

  function childrenMarkdown(node, depth) {
    var blocks = [];
    var inlineBuffer = "";
    for (var i = 0; i < node.childNodes.length; i++) {
      var child = node.childNodes[i];
      if (child.nodeType === 3) {
        inlineBuffer += child.nodeValue;
        continue;
      }
      if (child.nodeType !== 1) continue;
      var tag = child.tagName.toLowerCase();
      if (isBlockTag(tag)) {
        var pending = normalizeInline(inlineBuffer);
        if (pending) blocks.push(pending);
        inlineBuffer = "";
        var block = blockMarkdown(child, depth).trim();
        if (block) blocks.push(block);
      } else {
        inlineBuffer += inlineMarkdown(child);
      }
    }
    var tail = normalizeInline(inlineBuffer);
    if (tail) blocks.push(tail);
    return blocks.join("\n\n");
  }

  function toMarkdown(holder) {
    var markdown = childrenMarkdown(holder, 0);
    return markdown
      .replace(/[ \t]+$/gm, "")
      .replace(/\n{3,}/g, "\n\n")
      .trim();
  }

  function markdownToText(markdown) {
    return normalizeBodyText(
      (markdown || "")
        .replace(/```[\s\S]*?```/g, function (block) { return block.replace(/`/g, ""); })
        .replace(/^#{1,6}\s+/gm, "")
        .replace(/^>\s?/gm, "")
        .replace(/^[-*+]\s+/gm, "")
        .replace(/^\d+\.\s+/gm, "")
        .replace(/!\[[^\]]*\]\([^)]*\)/g, "")
        .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
        .replace(/`/g, "")
        .replace(/\|.*\|/g, function (row) { return row.replace(/\s*\|/g, " ").trim(); })
    );
  }

  function finish(candidate, work, pageText, opts) {
    var element = candidate.element;
    var markdown = toMarkdown(element);
    var text = markdownToText(markdown);
    var html = element.innerHTML || "";
    var docTitle = normalizeInline(work.title || "");
    var title = candidate.title || articleTitle(work, element);
    var rawScore = articleScore(text, html, title, docTitle);
    var density = linkDensity(element);
    var foldPhrase = findFoldPhrase(pageText) || findFoldPhrase(text);
    var reasons = [];
    if (text.length < MIN_PROSE_CHARS) reasons.push("正文过短（" + text.length + " 字）");
    if (density > MAX_LINK_DENSITY) reasons.push("链接密度过高（" + Math.round(density * 100) + "%）");
    if (foldPhrase) reasons.push("页面出现「" + foldPhrase + "」，正文可能被折叠");
    var degraded = reasons.length > 0;

    var confidence = candidate.templateHit ? 0.95 : 0.6;
    if (candidate.templateHit) {
      confidence = 0.95;
    } else if (candidate.competitiveGap) {
      confidence += Math.min(candidate.competitiveGap / 40, 0.25);
    }
    if (candidate.extractor === "readability") confidence += 0.05;
    if (pageText.length < 500) confidence -= 0.1;
    if (opts.domStable === false) confidence -= 0.1;
    if (degraded) confidence = Math.min(confidence, 0.45);
    confidence = Math.max(0.05, Math.min(0.99, confidence));

    return {
      title: title || "",
      byline: candidate.byline || null,
      publishedAt: candidate.publishedAt || null,
      markdown: markdown,
      text: text,
      pageText: pageText,
      html: html,
      extractor: candidate.extractor,
      score: Math.round(rawScore) / 100,
      confidence: Math.round(confidence * 100) / 100,
      needsExpand: degraded,
      degradedReason: degraded ? reasons.join("；") : null
    };
  }

  function pageFallback(doc, work, pageText, opts) {
    var body = doc.createElement("div");
    body.innerHTML = (work.body && work.body.innerHTML) || "";
    cleanContainer(body, null);
    liftLazyImages(body);
    var text = textOf(body);
    if (!text) return null;
    return finish(
      {
        element: body,
        extractor: "page-fallback",
        title: articleTitle(work, body),
        byline: byline(work),
        publishedAt: publishedAt(work)
      },
      work,
      pageText,
      opts
    );
  }

  function extract(opts) {
    opts = opts || {};
    var doc = document;
    var work = doc.cloneNode(true);
    var pageText = normalizeBodyText(textOf(doc.body));
    liftLazyImages(work);

    var templateList = opts.templates && opts.templates.templates ? opts.templates.templates : (opts.templates || []);
    var template = matchTemplate(templateList, location.host || "", location.href || "");
    var genericTemplate = null;
    for (var i = 0; i < templateList.length; i++) {
      if (templateList[i] && templateList[i].name === "通用网页") genericTemplate = templateList[i];
    }
    applyRemoveSelectors(work, genericTemplate ? genericTemplate.removeSelectors : null);

    if (template) {
      var templateResult = templateExtract(doc, template, work);
      if (templateResult) {
        templateResult.competitiveGap = 0;
        return finish(templateResult, work, pageText, opts);
      }
    }

    var readabilityResult = readabilityExtract(doc, work);
    var densityResult = densityExtract(doc, work);

    var readScore = readabilityResult
      ? articleScore(markdownToText(toMarkdown(readabilityResult.element)), readabilityResult.element.innerHTML, readabilityResult.title, normalizeInline(work.title || ""))
      : -1;
    var densityScore = densityResult
      ? articleScore(markdownToText(toMarkdown(densityResult.element)), densityResult.element.innerHTML, densityResult.title, normalizeInline(work.title || ""))
      : -1;

    var winner = null;
    if (readabilityResult && densityResult) {
      winner = readScore >= densityScore ? readabilityResult : densityResult;
      winner.competitiveGap = Math.abs(readScore - densityScore);
    } else {
      winner = readabilityResult || densityResult;
      if (winner) winner.competitiveGap = 0;
    }

    if (!winner) return pageFallback(doc, work, pageText, opts);
    return finish(winner, work, pageText, opts);
  }

  window.__listenReaderL1 = function (optionsJson) {
    try {
      var opts = optionsJson ? JSON.parse(optionsJson) : {};
      return JSON.stringify(extract(opts));
    } catch (e) {
      return JSON.stringify({ error: String((e && e.message) || e) });
    }
  };
})();
