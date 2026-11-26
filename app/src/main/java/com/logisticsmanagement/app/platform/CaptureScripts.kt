package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.Platform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal object CaptureScripts {
    fun install(platform: Platform): String {
        val patterns = when (platform) {
            Platform.JD -> listOf("order_list_m", "order_detail_m", "order_common_order_detail", "get_logistics_list_by_order_m", "deal_wuliu", "orderTrack")
            Platform.PDD -> listOf("order_list_v4", "order_detail", "logistics", "goods_express", "login")
            Platform.TAOBAO -> listOf("mtop.taobao.order.queryboughtlistv2", "mtop.taobao.logisticstracedetailservice.queryalltrace")
            Platform.DOUYIN -> emptyList()
        }
        val filter = JsonArray(patterns.map(::JsonPrimitive)).toString()
        return """
            (function() {
              'use strict';
              if (window !== window.top || window.__logisticsInstalled) return;
              window.__logisticsInstalled = true;
              const patterns = $filter;
              const limit = 1048576;
              function send(kind, url, body, status, requestType, requestPage) {
                const value = String(body == null ? '' : body);
                if (value.length > limit) {
                  LogisticsCapture.postMessage(JSON.stringify({kind:'capacity',url:String(url),pageUrl:location.href,body:'响应超过采集容量',status:0,characters:value.length}));
                  return;
                }
                LogisticsCapture.postMessage(JSON.stringify({kind:kind,url:String(url || location.href),pageUrl:location.href,body:value,status:Number(status || 0),requestType:String(requestType || ''),requestPage:Number(requestPage || 0)}));
              }
              function relevant(url, body) {
                const u = String(url).toLowerCase();
                return patterns.some(function(p) { return u.indexOf(p.toLowerCase()) >= 0; });
              }
              const originalOpen = XMLHttpRequest.prototype.open;
              const originalSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(method, url) {
                this.__logisticsUrl = new URL(String(url), location.href).href;
                return originalOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function() {
                let requestType = '';
                let requestPage = 0;
                if (this.__logisticsUrl.indexOf('order_list_v4') >= 0 && typeof arguments[0] === 'string') {
                  try { const parsed = JSON.parse(arguments[0]); requestType = parsed.type || ''; requestPage = Number(parsed.page || 0); } catch (e) {}
                }
                this.addEventListener('load', function() {
                  if (this.responseType && this.responseType !== 'text' && this.responseType !== 'json') return;
                  const body = this.responseType === 'json' ? JSON.stringify(this.response) : this.responseText;
                  if (relevant(this.__logisticsUrl, body)) send('response', this.__logisticsUrl, body, this.status, requestType, requestPage);
                });
                return originalSend.apply(this, arguments);
              };
              if (window.fetch) {
                const originalFetch = window.fetch;
                window.fetch = function(input, init) {
                  const url = new URL(String(input && input.url || input), location.href).href;
                  let requestType = '';
                  let requestPage = 0;
                  if (url.indexOf('order_list_v4') >= 0 && init && typeof init.body === 'string') {
                    try { const parsed = JSON.parse(init.body); requestType = parsed.type || ''; requestPage = Number(parsed.page || 0); } catch (e) {}
                  }
                  const result = originalFetch.apply(this, arguments);
                  result.then(function(response) {
                    const type = response.headers.get('content-type') || '';
                    if (!relevant(response.url, '')) return;
                    response.clone().text().then(function(body) {
                      if (relevant(response.url, body)) send('response', response.url, body, response.status, requestType, requestPage);
                    }).catch(function(error) { send('error', response.url, String(error), 0); });
                  }, function(error) { if (relevant(url, '')) send('network', url, String(error), 0); });
                  return result;
                };
              }
              const callbacks = new Set();
              function observeJsonp(src) {
                const url = new URL(String(src), location.href);
                if (!relevant(url.href, '')) return;
                const callback = url.searchParams.get('callback');
                if (!callback || callbacks.has(callback)) return;
                if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(callback)) {
                  send('error', url.href, 'JSONP 回调格式尚未支持', 0); return;
                }
                callbacks.add(callback);
                let original = window[callback];
                const wrapped = function(value) {
                  send('response', url.href, JSON.stringify(value), 200);
                  if (typeof original === 'function') return original.apply(this, arguments);
                };
                Object.defineProperty(window, callback, {
                  configurable:true, get:function() { return wrapped; }, set:function(value) { original = value; }
                });
              }
              const descriptor = Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype, 'src');
              Object.defineProperty(HTMLScriptElement.prototype, 'src', {
                configurable:descriptor.configurable, enumerable:descriptor.enumerable,
                get:descriptor.get,
                set:function(value) { observeJsonp(value); return descriptor.set.call(this, value); }
              });
              const originalAttribute = Element.prototype.setAttribute;
              Element.prototype.setAttribute = function(name, value) {
                if (this.tagName === 'SCRIPT' && String(name).toLowerCase() === 'src') observeJsonp(value);
                return originalAttribute.apply(this, arguments);
              };
              let lastDom = '';
              let lastSsr = '';
              setInterval(function() {
                if (!document.body) return;
                if (/deal_wuliu|goods_express|logisticsV2/.test(location.href)) {
                  const text = document.body.innerText;
                  if (text !== lastDom) { lastDom = text; send('dom', location.href, text, 200); }
                }
                if (/logisticsV2/.test(location.href)) {
                  const loader = window.__ICE_SUSPENSE_LOADER__;
                  const value = loader && loader['undefined'];
                  if (value) {
                    const text = JSON.stringify(value);
                    if (text !== lastSsr) { lastSsr = text; send('ssr', location.href, text, 200); }
                  }
                }
                const text = document.body.innerText;
                if (/(?:安全验证|请完成验证|滑动验证|请拖动滑块|访问过于频繁)/.test(text)) send('challenge', location.href, '平台要求完成安全验证', 0);
                if (/(?:没有更多了|没有更多订单|全部订单已加载)/.test(text)) send('end', location.href, '', 200);
              }, 1500);
            })();
        """.trimIndent()
    }

    val moreOrders = """
        (function() {
          const elements = document.querySelectorAll('a,button,div,span');
          for (const e of elements) {
            const text = (e.innerText || '').trim();
            if (e.getBoundingClientRect().height > 0 && ['加载更多','查看更多订单','查看全部','下一页'].includes(text)) {
              e.click(); return true;
            }
          }
          return false;
        })();
    """.trimIndent()

    val pddTracking = """
        (function() {
          if (!/\/order\.html(?:\?|$)/.test(location.href)) return false;
          const matches = Array.from(document.querySelectorAll('a,button,div,span')).filter(function(e) {
            const text = (e.innerText || '').trim();
            return ['查看物流','查物流','物流详情','物流信息'].includes(text) && e.getBoundingClientRect().height > 0;
          }).sort(function(a,b) { return a.getBoundingClientRect().height - b.getBoundingClientRect().height; });
          const express = document.getElementById('orderExpressBlock');
          if (!matches.length && express && express.getBoundingClientRect().height > 0) {
            const entry = Array.from(express.querySelectorAll('div,span')).find(function(e) { return !e.children.length && (e.innerText||'').trim() && e.getBoundingClientRect().height>0; });
            if (entry) matches.push(entry);
          }
          const text = document.body.innerText;
          LogisticsCapture.postMessage(JSON.stringify({kind:'dom',url:location.href,pageUrl:location.href,body:text,status:200}));
          LogisticsCapture.postMessage(JSON.stringify({kind:'trackingAction',url:location.href,pageUrl:location.href,body:JSON.stringify({clicked:matches.length>0,matches:matches.length,targetId:matches.length ? matches[0].id : ''}),status:200}));
          if (!matches.length) {
            const orderId = new URL(location.href).searchParams.get('order_sn');
            const ready = orderId && text.includes(orderId) && text.includes('订单编号') && ['查看更多订单信息','查看更多订单和优惠信息'].some(function(label) { return text.includes(label); });
            const prior = window.__pddTrackingMissing;
            if (ready && prior && prior.text === text && Date.now() - prior.since >= 3000) {
              LogisticsCapture.postMessage(JSON.stringify({kind:'trackingUnavailable',url:location.href,pageUrl:location.href,body:text,status:200}));
            } else {
              window.__pddTrackingMissing = ready ? {text:text,since:Date.now()} : null;
            }
            return false;
          }
          window.__pddTrackingMissing = null;
          matches[0].click(); return true;
        })();
    """.trimIndent()

    val pddOrders = """
        (function() {
          const elements = Array.from(document.querySelectorAll('a,button,div,span,li'));
          const matches = elements.filter(function(e) {
            const text = (e.innerText || '').trim();
            return ['全部','全部订单','查看全部'].includes(text) && e.getBoundingClientRect().height > 0;
          }).sort(function(a,b) { return a.getBoundingClientRect().height - b.getBoundingClientRect().height; });
          if (!matches.length) return false;
          matches[0].click(); return true;
        })();
    """.trimIndent()

    val pddScroll = """
        (function() {
          try {
            var all = document.querySelectorAll('*');
            var best = null;
            var bestSh = 0;
            for (var i = 0; i < all.length; i++) {
              var n = all[i];
              var sh = n.scrollHeight || 0;
              if (sh > bestSh && sh > n.clientHeight + 100 && n.clientHeight > 100) { best = n; bestSh = sh; }
            }
            if (!best) best = document.scrollingElement || document.documentElement;
            var before = best.scrollTop || 0;
            var step = Math.floor((best.clientHeight || 900) * 0.85);
            var target = before + step;
            try { if (typeof best.scrollTo === 'function') best.scrollTo(0, target); } catch (e) {}
            try { best.scrollTop = target; } catch (e) {}
            try { document.scrollingElement.scrollTop = target; } catch (e) {}
            try { window.scrollTo(0, best.scrollTop || target); } catch (e) {}
            try { best.dispatchEvent(new Event('scroll', {bubbles: true})); } catch (e) {}
            var after = best.scrollTop || 0;
            var rect = best.getBoundingClientRect();
            return JSON.stringify({tag: best.tagName || '', sh: bestSh, ch: best.clientHeight || 0, top: after, moved: after > before + 8,
              x: rect.left, y: rect.top, w: rect.width, h: rect.height,
              vw: document.documentElement.clientWidth, vh: document.documentElement.clientHeight});
          } catch (e) {
            return JSON.stringify({error: String(e), moved: false, vw: document.documentElement.clientWidth, vh: document.documentElement.clientHeight});
          }
        })();
    """.trimIndent()

    fun pddTab(tab: String) = """
        (function() {
          const elements = Array.from(document.querySelectorAll('a,button,div,span,li'));
          const matches = elements.filter(function(e) {
            const text = (e.innerText || '').trim();
            return text === '$tab' && e.getBoundingClientRect().height > 0;
          });
          if (!matches.length) return false;
          matches[0].click(); return true;
        })();
    """.trimIndent()

    val jdTracking = """
        (function() {
          const matches = Array.from(document.querySelectorAll('a,button,div,span')).filter(function(e) {
            const text = (e.innerText || '').trim();
            return ['查看物流','查物流','物流详情','订单跟踪'].includes(text) && e.getBoundingClientRect().height > 0;
          }).sort(function(a,b) { return a.getBoundingClientRect().height - b.getBoundingClientRect().height; });
          if (!matches.length) return false;
          matches[0].click(); return true;
        })();
    """.trimIndent()

    val douyinOrders = """
        (function() {
          const matches = Array.from(document.querySelectorAll('a,button,div,span')).filter(function(e) {
            return ['我的订单','全部订单','订单'].includes((e.innerText || '').trim()) && e.getBoundingClientRect().height > 0;
          }).sort(function(a,b) { return a.getBoundingClientRect().height - b.getBoundingClientRect().height; });
          if (!matches.length) return false;
          matches[0].click(); return true;
        })();
    """.trimIndent()
}
