(function () {
  "use strict";

  var CANDIDATE_TAGS = "a,button,div,span,p,section,li,strong,em,b,i,label";
  var lastElements = [];
  var observer = null;
  var lastMutationAt = 0;
  var QUIET_MS = 400;

  function normalize(text) {
    return (text || "").replace(/\s+/g, "").trim();
  }

  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
    if (!style) return false;
    if (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse") return false;
    var opacity = parseFloat(style.opacity);
    if (!isNaN(opacity) && opacity === 0) return false;
    var rect = el.getBoundingClientRect();
    if (rect.width < 2 || rect.height < 2) return false;
    if (el.getClientRects && el.getClientRects().length === 0) return false;
    return true;
  }

  function containsPhrase(text, phrases) {
    for (var i = 0; i < phrases.length; i++) {
      if (text.indexOf(phrases[i]) >= 0) return true;
    }
    return false;
  }

  function hasMatchingDescendant(el, phrases) {
    var descendants = el.querySelectorAll(CANDIDATE_TAGS);
    for (var i = 0; i < descendants.length; i++) {
      var text = normalize(descendants[i].innerText || descendants[i].textContent || "");
      if (text && text.length <= 40 && containsPhrase(text, phrases)) return true;
    }
    return false;
  }

  function bringIntoView(el) {
    try {
      el.scrollIntoView({ block: "center", inline: "center" });
      return true;
    } catch (error) {
      return scrollWithRect(el);
    }
  }

  function scrollWithRect(el) {
    try {
      var rect = el.getBoundingClientRect();
      var viewport = window.innerHeight || 0;
      var offset = window.pageYOffset || 0;
      window.scrollTo(0, Math.max(0, offset + rect.top - viewport / 2));
      return true;
    } catch (error) {
      return false;
    }
  }

  window.__listenReaderL3Collect = function (phrasesJson) {
    var phrases = [];
    try {
      phrases = JSON.parse(phrasesJson || "[]");
    } catch (e) {
      phrases = [];
    }
    if (!phrases.length) return "[]";
    lastElements = [];
    var out = [];
    var nodes = document.querySelectorAll(CANDIDATE_TAGS);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var text = normalize(el.innerText || el.textContent || "");
      if (!text || text.length > 40) continue;
      if (!containsPhrase(text, phrases)) continue;
      var rect = el.getBoundingClientRect();
      var href = el.getAttribute ? el.getAttribute("href") : null;
      var type = el.getAttribute ? el.getAttribute("type") : null;
      out.push({
        tag: el.tagName.toLowerCase(),
        text: text,
        visible: isVisible(el),
        inForm: !!(el.closest && el.closest("form")),
        href: href || null,
        type: type || null,
        submit: (el.tagName === "BUTTON" && (type || "submit").toString().toLowerCase() === "submit") ||
          (el.tagName === "INPUT"),
        leaf: !hasMatchingDescendant(el, phrases),
        rectWidth: Math.round(rect.width),
        rectHeight: Math.round(rect.height)
      });
      lastElements.push(el);
    }
    return JSON.stringify(out);
  };

  window.__listenReaderL3Click = function (index) {
    var el = lastElements[index];
    if (!el) return "false";
    if (!bringIntoView(el)) return "scroll_failed";
    window.__listenReaderL3BeginWatch();
    try {
      if (typeof el.click === "function") {
        el.click();
      } else {
        el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window }));
      }
    } catch (e) {
      return "error";
    }
    return "true";
  };

  window.__listenReaderL3BeginWatch = function () {
    if (observer && observer.disconnect) observer.disconnect();
    lastMutationAt = Date.now();
    try {
      observer = new MutationObserver(function () {
        lastMutationAt = Date.now();
      });
      observer.observe(document.documentElement || document.body, {
        subtree: true,
        childList: true,
        characterData: true,
        attributes: true
      });
    } catch (e) {
      observer = null;
    }
    return "true";
  };

  window.__listenReaderL3Quiet = function () {
    if (!observer) return "true";
    return Date.now() - lastMutationAt >= QUIET_MS ? "true" : "false";
  };

  window.__listenReaderL3ScrollThrough = function () {
    var doc = document.documentElement || document.body;
    if (!doc) return "true";
    var height = Math.max(doc.scrollHeight || 0, document.body ? document.body.scrollHeight : 0);
    var step = Math.max(200, Math.floor(window.innerHeight * 0.8));
    var y = 0;
    var timer = setInterval(function () {
      y += step;
      if (y >= height) {
        window.scrollTo(0, height);
        clearInterval(timer);
      } else {
        window.scrollTo(0, y);
      }
    }, 60);
    return "true";
  };
})();
