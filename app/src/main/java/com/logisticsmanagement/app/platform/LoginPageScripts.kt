package com.logisticsmanagement.app.platform

internal object LoginPageScripts {
    private const val VISIBLE = "(function(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';})"

    val probe = """
        (function() {
          var visible = $VISIBLE;
          var inputs = Array.prototype.map.call(document.querySelectorAll('input'), function(e, index) {
            return {index: index, placeholder: e.placeholder || '', type: e.type || 'text', filled: !!e.value, visible: visible(e)};
          }).filter(function(e) { return e.visible; });
          var buttons = Array.prototype.map.call(document.querySelectorAll('a,button,div,span'), function(e) {
            return visible(e) ? (e.innerText || '').trim() : '';
          }).filter(function(t) { return t.length > 0 && t.length <= 12; });
          var checkboxes = Array.prototype.map.call(document.querySelectorAll('input[type=checkbox]'), function(e, index) {
            var holder = e.closest('label') || e.parentElement;
            return {index: index, checked: !!e.checked, label: holder ? (holder.innerText || '').trim().slice(0, 60) : '', visible: visible(e)};
          });
          var agree = '';
          var nodes = Array.prototype.filter.call(document.querySelectorAll('div,span,label'), function(e) {
            return visible(e) && (e.innerText || '').indexOf('已阅读并同意') >= 0;
          });
          if (nodes.length) {
            nodes.sort(function(a, b) { return (a.innerText || '').length - (b.innerText || '').length; });
            if (nodes[0].parentElement) agree = nodes[0].parentElement.outerHTML.slice(0, 800);
          }
          return JSON.stringify({url: location.href, text: document.body ? document.body.innerText : '', buttons: buttons.filter(function(t, i) { return buttons.indexOf(t) === i; }), inputs: inputs, checkboxes: checkboxes, agreeHtml: agree});
        })();
    """.trimIndent()

    fun click(label: String): String {
        val safe = label.js()
        return """
        (function() {
          var visible = $VISIBLE;
          var matches = Array.prototype.filter.call(document.querySelectorAll('a,button,div,span'), function(e) {
            return visible(e) && (e.innerText || '').trim() === '$safe';
          });
          if (!matches.length) return 'missing';
          matches.sort(function(a, b) { var x = a.getBoundingClientRect(); var y = b.getBoundingClientRect(); return x.width * x.height - y.width * y.height; });
          var target = matches[0];
          var rect = target.getBoundingClientRect();
          var cx = rect.left + rect.width / 2;
          var cy = rect.top + rect.height / 2;
          ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(type) {
            var event = type.indexOf('pointer') === 0
              ? new PointerEvent(type, {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, button: 0, pointerType: 'touch'})
              : new MouseEvent(type, {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, button: 0});
            target.dispatchEvent(event);
          });
          return target.tagName + '|' + String(target.className || '').slice(0, 40) + '|' + (target.innerText || '').trim().slice(0, 16);
        })();
    """
    }

    fun locate(label: String): String {
        val safe = label.js()
        return """
        (function() {
          var visible = $VISIBLE;
          var matches = Array.prototype.filter.call(document.querySelectorAll('a,button,div,span'), function(e) {
            return visible(e) && (e.innerText || '').trim() === '$safe';
          });
          if (!matches.length) return 'missing';
          matches.sort(function(a, b) { var x = a.getBoundingClientRect(); var y = b.getBoundingClientRect(); return x.width * x.height - y.width * y.height; });
          var target = matches[0];
          try { target.scrollIntoView({block: 'center'}); } catch (e) {}
          var rect = target.getBoundingClientRect();
          return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, w: document.documentElement.clientWidth, h: document.documentElement.clientHeight, tag: target.tagName, cls: String(target.className || '').slice(0, 40)});
        })();
    """
    }

    fun fill(index: Int, value: String): String {
        val safe = value.js()
        return """
        (function() {
          var input = document.querySelectorAll('input')[$index];
          if (!input) return 'missing';
          var proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
          var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
          setter.call(input, '$safe');
          input.dispatchEvent(new Event('input', {bubbles: true}));
          input.dispatchEvent(new Event('change', {bubbles: true}));
          input.blur();
          return 'filled';
        })();
    """
    }

    fun agreeCircle(): String = """
        (function() {
          var visible = $VISIBLE;
          var labels = Array.prototype.filter.call(document.querySelectorAll('span,div,label,em,p,b'), function(e) {
            var t = (e.innerText || '').trim();
            return visible(e) && (t === '同意' || (t.indexOf('同意') === 0 && t.length <= 8));
          }).sort(function(a, b) { var x = a.getBoundingClientRect(); var y = b.getBoundingClientRect(); return x.width * x.height - y.width * y.height; });
          if (!labels.length) return 'missing-label';
          var label = labels[0];
          var rect = label.getBoundingClientRect();
          var circles = Array.prototype.filter.call(document.querySelectorAll('i,span,div,em,label,button'), function(e) {
            if (!visible(e)) return false;
            var r = e.getBoundingClientRect();
            return r.width >= 10 && r.width <= 48 && r.height >= 10 && r.height <= 48 &&
              r.right <= rect.left + 4 && r.left >= rect.left - 96 &&
              r.top < rect.bottom && r.bottom > rect.top;
          }).sort(function(a, b) { var x = a.getBoundingClientRect(); var y = b.getBoundingClientRect(); return y.right - x.right; });
          var target = circles[0] || label;
          ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(type) {
            target.dispatchEvent(new MouseEvent(type, {bubbles: true, cancelable: true, view: window}));
          });
          return target.tagName + '|' + String(target.className || '').slice(0, 80);
        })();
    """.trimIndent()

    fun agreeText(label: String = "同意"): String {
        val safe = label.js()
        return """
        (function() {
          var visible = $VISIBLE;
          var all = Array.prototype.filter.call(document.querySelectorAll('a,button,div,span,label,i'), function(e) {
            return visible(e) && (e.innerText || '').trim() === '$safe';
          });
          if (!all.length) return 'missing';
          all.sort(function(a, b) { var x = a.getBoundingClientRect(); var y = b.getBoundingClientRect(); return x.width * x.height - y.width * y.height; });
          var target = all[0];
          ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(type) {
            target.dispatchEvent(new MouseEvent(type, {bubbles: true, cancelable: true, view: window}));
          });
          return target.tagName + '|' + String(target.className || '').slice(0, 60);
        })();
    """.trimIndent()
    }

    val agree = """
        (function() {
          var box = document.getElementById('fm-agreement-checkbox');
          if (!box) {
            box = Array.prototype.filter.call(document.querySelectorAll('input[type=checkbox]'), function(e) { return !e.checked; })[0];
          }
          if (box) {
            if (!box.checked) box.click();
            return box.checked ? 'checked' : 'clicked-unchecked';
          }
          return 'missing';
        })();
    """.trimIndent()

    private fun String.js(): String = replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
}
