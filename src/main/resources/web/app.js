'use strict';

/* ==========================================================================
 * Dubbo Lite —— 前端（无第三方依赖）
 * ========================================================================== */

const QS = new URLSearchParams(location.search);
const TOKEN = QS.get('t');
const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const MOD = IS_MAC ? '⌘' : 'Ctrl+';
const IS_WIN = /Win/.test(navigator.platform || navigator.userAgent);
const MAC_BRIDGE = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.dubbolite;
const WIN_BRIDGE = typeof window.dlNative === 'function' ? window.dlNative : null;
/** 原生外壳（macOS WKWebView / Windows WebView2）提供的桥；浏览器模式下为 null */
const NATIVE = MAC_BRIDGE || WIN_BRIDGE;
function nativePost(msg) {
  if (MAC_BRIDGE) MAC_BRIDGE.postMessage(msg);
  else if (WIN_BRIDGE) WIN_BRIDGE(JSON.stringify(msg));
}
const REVEAL_LABEL = IS_MAC ? '在 Finder 中显示' : '在资源管理器中显示';
const CAN_REVEAL = IS_MAC || IS_WIN;
function reveal(path) {
  return WIN_BRIDGE ? nativePost({ type: 'reveal', path }) : api('reveal', { path });
}

/* ------------------------------------------------------------------ dom helpers */

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'html') el.innerHTML = v;
      else if (k === 'value') el.value = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, kids);
  return el;
}
function append(el, kids) {
  for (const k of kids) {
    if (k == null || k === false) continue;
    if (Array.isArray(k)) append(el, k);
    else el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}
function esc(s) {
  return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

const ICONS = {
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  chev: '<path d="m9 6 6 6-6 6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  send: '<path d="M22 2 11 13"/><path d="m22 2-7 20-4-9-9-4z"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
  open: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v1"/><path d="M3 7v10a2 2 0 0 0 2 2h12.5a2 2 0 0 0 1.9-1.4L22 11H8.5a2 2 0 0 0-1.9 1.4L4 19"/>',
  save: '<path d="M5 3h11l5 5v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M7 3v5h8"/><path d="M7 21v-7h10v7"/>',
  more: '<circle cx="5" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="19" cy="12" r="1.2"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/>',
  trash: '<path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6"/>',
  wand: '<path d="m15 4 1 2 2 1-2 1-1 2-1-2-2-1 2-1zM5 20 16 9"/><path d="m19 13 .5 1 1 .5-1 .5-.5 1-.5-1-1-.5 1-.5z"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  plug: '<path d="M12 22v-5M9 8V2M15 8V2M18 8v5a6 6 0 0 1-12 0V8z"/>',
  refresh: '<path d="M21 12a9 9 0 0 1-15.5 6.2L3 16"/><path d="M3 21v-5h5"/><path d="M3 12a9 9 0 0 1 15.5-6.2L21 8"/><path d="M21 3v5h-5"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  braces: '<path d="M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5a2 2 0 0 0 2 2h1M16 21h1a2 2 0 0 0 2-2v-5a2 2 0 0 1 2-2 2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1"/>',
  text: '<path d="M4 6h16M4 12h16M4 18h10"/>',
  finder: '<path d="M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/>',
  power: '<path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/>',
  up: '<path d="m18 15-6-6-6 6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7"/>',
  download: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M5 21h14"/>',
  eraser: '<path d="m7 21-4.3-4.3a1 1 0 0 1 0-1.4l10-10a1 1 0 0 1 1.4 0l5.6 5.6a1 1 0 0 1 0 1.4L13 21"/><path d="M22 21H7M5 11l9 9"/>',
};
function I(name, cls = '') {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('class', 'i ' + cls);
  s.innerHTML = ICONS[name] || '';
  return s;
}

/* ------------------------------------------------------------------ api */

async function api(action, body = {}) {
  let res;
  try {
    res = await fetch('/api/' + action, {
      method: 'POST',
      headers: { 'X-Token': TOKEN, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (e) {
    throw new Error('无法连接到 Dubbo Lite 后台进程，请重新启动应用');
  }
  if (res.status !== 200) throw new Error('请求失败 (' + res.status + ')');
  const j = await res.json();
  if (!j.ok) throw new Error(j.error || '未知错误');
  return j.data;
}

/* ------------------------------------------------------------------ state */

const S = {
  docs: [],
  cur: null,
  sel: {},
  expanded: {},
  results: new Map(),
  sending: new Map(),
  /** 发送前（还在提交编辑时）就点了中断的请求 */
  cancelReq: new Set(),
  filter: '',
  reqTab: 'params',
  respTab: 'body',
  respView: localStorage.getItem('dl.respView') || 'tree',
  hideClass: localStorage.getItem('dl.hideClass') === '1',
  state: { recent: [], extPaths: [] },
};
const HIDDEN = new Set(['listener', 'other']);
/** 可以发送的请求 */
const SENDABLE = new Set(['dubbo', 'zms']);
const CONTAINER = new Set(['plan', 'group', 'controller']);
/** 只有配置元素可以启用/停用（请求本身、分组等不提供） */
const CONFIG_KINDS = new Set(['defaults', 'pre', 'post', 'csv', 'vars']);
/**
 * 在本应用中是否按“已启用”处理：配置元素看自身的启用状态；请求、分组等一律视为启用
 * （JMX 中的 enabled 属性原样保留，JMeter 读取不受影响）
 */
const isOn = n => !CONFIG_KINDS.has(n.kind) || n.enabled;
const CONN = [
  ['registryProtocol', '注册中心协议', 'zookeeper', ['zookeeper', 'nacos', 'redis', 'multicast', ['none', 'none（直连）']]],
  ['registryAddress', '注册中心地址', '10.0.0.1:2181,10.0.0.2:2181'],
  ['registryGroup', '注册中心分组', ''],
  ['rpcProtocol', 'RPC 协议', 'dubbo', ['dubbo', 'rmi', 'hessian', 'http']],
  ['timeout', '超时 (ms)', '120000'],
  ['retries', '重试次数', '0'],
  ['version', '服务版本', ''],
  ['group', '服务分组', ''],
  ['cluster', '集群容错', 'failfast', ['failfast', 'failover', 'failsafe', 'failback', 'forking']],
  ['loadbalance', '负载均衡', 'random', ['random', 'roundrobin', 'leastactive', 'consistenthash']],
  ['connections', '连接数（仅 JMeter 压测生效）', '100'],
];
const KIND_LABEL = {
  sampler: '其他类型请求（只读）',
  zms: 'ZMS 消息',
  plan: '测试计划', group: '分组', controller: '控制器', dubbo: 'Dubbo 请求', defaults: 'Dubbo 默认配置',
  pre: '前置脚本', post: '后置脚本', csv: 'CSV 数据', vars: '用户变量', other: '其他元素', listener: '监听器',
};
const PARAM_HINT = `参数格式与 JMeter yajad Dubbo 插件一致：每个参数写成 {类型: 值}
[
  {"com.example.dto.QueryReq": {"id": 1, "name": "\${name}"}},
  {"java.lang.String": "abc"},
  {"long": 100}
]
支持 \${变量} 与 \${__Random(1,9,)} 等 JMeter 函数`;

const doc = () => S.docs.find(d => d.id === S.cur) || null;
const resKey = (d, n) => d.id + ':' + n.id;

function indexDoc(d) {
  d.byId = new Map();
  d.parentOf = new Map();
  const walk = (n, p) => {
    d.byId.set(n.id, n);
    if (p) d.parentOf.set(n.id, p);
    n.children.forEach(c => walk(c, n));
  };
  walk(d.root, null);
}
function applyDoc(j) {
  let d = S.docs.find(x => x.id === j.id);
  if (!d) {
    d = { id: j.id };
    S.docs.push(d);
  }
  Object.assign(d, { name: j.name, path: j.path, dirty: j.dirty, root: j.root });
  indexDoc(d);
  if (!S.expanded[d.id]) {
    const saved = d.path && localStorage.getItem('dl.exp:' + d.path);
    if (saved) S.expanded[d.id] = new Set(JSON.parse(saved));
    else {
      S.expanded[d.id] = new Set();
      d.root.children.forEach(c => { if (c.kind === 'group') S.expanded[d.id].add(c.id); });
    }
    const sel = d.path && +localStorage.getItem('dl.sel:' + d.path);
    if (sel && d.byId.has(sel)) S.sel[d.id] = sel;
  }
  return d;
}
function persistUi(d) {
  if (!d || !d.path) return;
  localStorage.setItem('dl.exp:' + d.path, JSON.stringify([...(S.expanded[d.id] || [])]));
  if (S.sel[d.id]) localStorage.setItem('dl.sel:' + d.path, String(S.sel[d.id]));
}
const parentOf = (d, n) => d.parentOf.get(n.id);
function ancestors(d, n) {
  const out = [];
  for (let p = parentOf(d, n); p; p = parentOf(d, p)) out.unshift(p);
  return out;
}
function visibleKids(n) {
  return n.children.filter(c => !HIDDEN.has(c.kind));
}
function isContainer(n) {
  return CONTAINER.has(n.kind) || (SENDABLE.has(n.kind) && visibleKids(n).length > 0);
}
function samplersIn(n) {
  const out = [];
  const walk = x => x.children.forEach(c => { if (SENDABLE.has(c.kind)) out.push(c); else if (CONTAINER.has(c.kind)) walk(c); });
  walk(n);
  return out;
}
/** 与后端一致的作用域：每一层祖先的直接子元素 + 自身子元素 */
function scopeOf(d, n, includeDisabled) {
  const scope = [];
  for (const p of ancestors(d, n)) {
    for (const c of p.children) {
      if (c !== n && (includeDisabled || c.enabled) && !['dubbo', 'zms', 'sampler', 'group', 'controller', 'listener', 'other'].includes(c.kind)) scope.push(c);
    }
  }
  for (const c of n.children) if ((includeDisabled || c.enabled) && !HIDDEN.has(c.kind)) scope.push(c);
  return scope;
}
function effective(d, n) {
  const f = { ...n.props };
  const from = {};
  // JavaSampler(dandelion) 插件不读取 Dubbo 默认配置
  if (n.flavor === 'dandelion') return { f, from };
  const defs = scopeOf(d, n).filter(c => c.kind === 'defaults').reverse();
  for (const [k] of CONN) {
    if ((f[k] || '') !== '') continue;
    for (const df of defs) {
      if ((df.props[k] || '') !== '') {
        f[k] = df.props[k];
        from[k] = df;
        break;
      }
    }
  }
  return { f, from };
}

/** 用测试计划和作用域内的“用户定义的变量”解析简单的 ${name}（仅用于界面展示） */
function resolveVars(d, n, text) {
  if (!text || !text.includes('${')) return text;
  const vars = {};
  const add = x => (x.props.variables || []).forEach(v => { vars[v.name] = v.value; });
  add(d.root);
  scopeOf(d, n).filter(x => x.kind === 'vars').forEach(add);
  let out = text;
  for (let i = 0; i < 3 && out.includes('${'); i++) out = out.replace(/\$\{([\w.]+)\}/g, (m, k) => (k in vars ? vars[k] : m));
  return out;
}
const FLAVOR_LABEL = { yajad: 'yajad 插件', ningyu: 'ningyu 插件', dandelion: 'JavaSampler 插件' };
function samplerTag(tc) {
  const known = { 'com.zto.zmsjmeter.ZMSSampler': 'ZMS', HTTPSamplerProxy: 'HTTP', JavaSampler: 'JAVA', DebugSampler: 'DEBUG', JDBCSampler: 'JDBC', TCPSampler: 'TCP' };
  if (known[tc]) return known[tc];
  return tc.replace(/^.*\./, '').replace(/Sampler(Proxy)?$|Sample$/, '').slice(0, 6).toUpperCase() || 'REQ';
}

/* ------------------------------------------------------------------ pending edits */

const pending = new Map();
let flushTimer = null;
function queue(d, n, patch) {
  const key = d.id + ':' + n.id;
  const cur = pending.get(key) || { docId: d.id, nodeId: n.id, patch: {} };
  if (patch.props) cur.patch.props = Object.assign(cur.patch.props || {}, patch.props);
  for (const k of ['name', 'enabled']) if (k in patch) cur.patch[k] = patch[k];
  pending.set(key, cur);
  if (patch.props) Object.assign(n.props, patch.props);
  if ('name' in patch) n.name = patch.name;
  if ('enabled' in patch) n.enabled = patch.enabled;
  if (!d.dirty) {
    d.dirty = true;
    renderTabs();
  }
  clearTimeout(flushTimer);
  flushTimer = setTimeout(() => flush().catch(e => toast(e.message, true)), 350);
}
async function flush() {
  clearTimeout(flushTimer);
  const items = [...pending.values()];
  pending.clear();
  for (const it of items) {
    const r = await api('update', it);
    const d = S.docs.find(x => x.id === it.docId);
    if (d) d.dirty = r.dirty;
  }
}

/* ------------------------------------------------------------------ JSON（保留大数字与原始顺序） */

function parseLoose(text) {
  let i = 0;
  const s = text;
  const ws = () => { while (i < s.length && /\s/.test(s[i])) i++; };
  const fail = m => { const e = new Error(m + ' (位置 ' + i + ')'); e.pos = i; throw e; };
  function str() {
    let out = '';
    i++;
    while (i < s.length) {
      const c = s[i++];
      if (c === '"') return out;
      if (c === '\\') {
        const n = s[i++];
        if (n === 'u') { out += String.fromCharCode(parseInt(s.substr(i, 4), 16)); i += 4; }
        else out += { n: '\n', t: '\t', r: '\r', b: '\b', f: '\f' }[n] || n;
      } else out += c;
    }
    fail('字符串未结束');
  }
  function val() {
    ws();
    const c = s[i];
    if (c === '{') {
      i++;
      const e = [];
      ws();
      if (s[i] === '}') { i++; return { t: 'o', e }; }
      for (;;) {
        ws();
        if (s[i] !== '"') fail('需要 "键"');
        const k = str();
        ws();
        if (s[i++] !== ':') fail('需要 :');
        e.push([k, val()]);
        ws();
        if (s[i] === ',') { i++; continue; }
        if (s[i] === '}') { i++; return { t: 'o', e }; }
        fail('需要 , 或 }');
      }
    }
    if (c === '[') {
      i++;
      const e = [];
      ws();
      if (s[i] === ']') { i++; return { t: 'a', e }; }
      for (;;) {
        e.push(val());
        ws();
        if (s[i] === ',') { i++; continue; }
        if (s[i] === ']') { i++; return { t: 'a', e }; }
        fail('需要 , 或 ]');
      }
    }
    if (c === '"') return { t: 's', v: str() };
    if (c === '$' && s[i + 1] === '{') {
      const st = i;
      let depth = 0;
      while (i < s.length) {
        if (s[i] === '{') depth++;
        else if (s[i] === '}' && --depth === 0) { i++; break; }
        i++;
      }
      return { t: 'r', v: s.slice(st, i) };
    }
    const m = /^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(s.slice(i, i + 64));
    if (m) { i += m[0].length; return { t: 'n', v: m[0] }; }
    for (const w of ['true', 'false', 'null']) {
      if (s.startsWith(w, i)) { i += w.length; return w === 'null' ? { t: 'z' } : { t: 'b', v: w }; }
    }
    fail('无法识别的内容');
  }
  const v = val();
  ws();
  if (i < s.length) fail('多余的内容');
  return v;
}
const FULL_WIDTH = { '“': '"', '”': '"', '‘': "'", '’': "'", '，': ',', '：': ':', '［': '[', '］': ']', '｛': '{', '｝': '}' };
/** 字符串之外的全角标点（解析器可能不报错，但多半是输入法误输入） */
function lintFullWidth(text) {
  let inStr = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      if (c === '\\') i++;
      else if (c === '"') inStr = false;
    } else if (c === '"') inStr = true;
    else if (FULL_WIDTH[c]) return { pos: i, msg: '全角字符 ' + c + ' 可能是误输入，应为半角 ' + FULL_WIDTH[c] };
  }
  return null;
}
/** 在 [start, end) 范围内按 JSON 定位第一个错误；缺逗号时位置退回到上一个值的末尾 */
function locateJson(text, start, end) {
  try {
    parseLoose(text.slice(start, end));
    return null;
  } catch (e) {
    let pos = start + (e.pos || 0);
    const msg = e.message.replace(/ \(位置 \d+\)$/, '');
    if (/^需要 , 或/.test(msg)) while (pos > start && /\s/.test(text[pos - 1])) pos--;
    return { pos, msg: msg.replace(/^需要 , 或 (.)$/, '这里缺少 , 或 $1（上一个值之后需要逗号）') };
  }
}
/** 参数编辑器的即时校验：后台用发送时相同的解析器检查语法；没有语法错误时再提示全角标点 */
function paramsChecker(editor, dandelion) {
  let seq = 0, timer = 0;
  return delay => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const text = editor.ed.get();
      const my = ++seq;
      let r;
      try {
        r = await api('checkParams', { text, dandelion });
      } catch (e) {
        return;
      }
      if (my !== seq || editor.ed.get() !== text) return;
      if (r.valueStart != null) {
        const loc = locateJson(text, r.valueStart, r.valueEnd);
        if (loc) Object.assign(r, { pos: loc.pos, error: r.error.replace(/：.*$/, '：') + loc.msg });
      }
      const w = lintFullWidth(text);
      if (!r.error) editor.ed.diag(w && Object.assign({ level: 'warn' }, w));
      // 出错位置之前有全角标点时，多半就是它导致的
      else if (w && r.pos != null && w.pos <= r.pos) editor.ed.diag({ level: 'err', msg: w.msg, pos: w.pos });
      else editor.ed.diag({ level: 'err', msg: r.error, pos: r.pos });
    }, delay == null ? 400 : delay);
  };
}
function stringifyLoose(n, ind = '') {
  const nx = ind + '  ';
  switch (n.t) {
    case 'o': return n.e.length ? '{\n' + n.e.map(([k, v]) => nx + JSON.stringify(k) + ': ' + stringifyLoose(v, nx)).join(',\n') + '\n' + ind + '}' : '{}';
    case 'a': return n.e.length ? '[\n' + n.e.map(v => nx + stringifyLoose(v, nx)).join(',\n') + '\n' + ind + ']' : '[]';
    case 's': return JSON.stringify(n.v);
    case 'z': return 'null';
    default: return n.v;
  }
}

/* ------------------------------------------------------------------ highlight */

const TYPE_ALIASES = new Set(['int', 'long', 'short', 'byte', 'char', 'double', 'float', 'boolean', 'bool', 'string', 'str', 'list', 'List', 'set', 'Set', 'map', 'Map']);
const isTypeName = s => /^[A-Za-z_$][\w$]*(\.[\w$]+)+(\[\])*$/.test(s) || TYPE_ALIASES.has(s.replace(/(\[\])+$/, ''));
function withVars(raw, cls) {
  return raw.split(/(\$\{[^}\n]*\})/).map((p, i) => (i % 2 ? `<span class="t-var">${esc(p)}</span>` : p ? `<span class="${cls}">${esc(p)}</span>` : '')).join('');
}
function hlJson(src) {
  const re = /("(?:[^"\\\n]|\\.)*"?)(\s*:)?|(\$\{[^}\n]*\})|((?<![\w.$])-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[lLdDfF]?(?![\w]))|\b(true|false|null)\b|([{}[\],:])|((?:^|(?<=\s))#[^\n]*)|([A-Za-z_$][\w$.[\]-]*)(?=\s*:(?:\s|$))/gm;
  let out = '';
  let last = 0;
  let m;
  while ((m = re.exec(src))) {
    if (m[0] === '') { re.lastIndex++; continue; }
    out += esc(src.slice(last, m.index));
    last = re.lastIndex;
    if (m[1] !== undefined) {
      if (m[2]) out += withVars(m[1], isTypeName(m[1].slice(1, -1)) ? 't-type' : 't-key') + `<span class="t-punc">${m[2]}</span>`;
      else out += withVars(m[1], 't-str');
    } else if (m[3]) out += `<span class="t-var">${esc(m[3])}</span>`;
    else if (m[4]) out += `<span class="t-num">${m[4]}</span>`;
    else if (m[5]) out += `<span class="t-bool">${m[5]}</span>`;
    else if (m[6]) out += `<span class="t-punc">${esc(m[6])}</span>`;
    else if (m[7]) out += `<span class="t-com">${esc(m[7])}</span>`;
    else if (m[8]) out += `<span class="${isTypeName(m[8]) ? 't-type' : 't-key'}">${esc(m[8])}</span>`;
  }
  return out + esc(src.slice(last));
}
const JAVA_KW = new Set('import package new return if else for while do try catch finally throw throws class interface extends implements public private protected static final void var def instanceof this super break continue switch case default true false null'.split(' '));
const JAVA_TYPES = new Set('String int long double float boolean char byte short Object Integer Long Double Boolean Map HashMap List ArrayList Date System Math JSON JSONObject JSONArray LocalDateTime StringBuilder'.split(' '));
const JAVA_BI = new Set('vars props log prev sampler ctx Label FileName Parameters args bsh OUT'.split(' '));
function hlJava(src) {
  const re = /(\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$))|("(?:[^"\\\n]|\\.)*"?|'(?:[^'\\\n]|\\.)*'?)|(\$\{[^}\n]*\})|((?<![\w.$])\d+(?:\.\d+)?[lLfFdD]?)|([A-Za-z_$][\w$]*)/g;
  let out = '';
  let last = 0;
  let m;
  while ((m = re.exec(src))) {
    out += esc(src.slice(last, m.index));
    last = re.lastIndex;
    if (m[1]) out += `<span class="t-com">${esc(m[1])}</span>`;
    else if (m[2]) out += withVars(m[2], 't-str');
    else if (m[3]) out += `<span class="t-var">${esc(m[3])}</span>`;
    else if (m[4]) out += `<span class="t-num">${m[4]}</span>`;
    else if (m[5]) {
      const w = m[5];
      out += JAVA_KW.has(w) ? `<span class="t-kw">${w}</span>` : JAVA_TYPES.has(w) ? `<span class="t-type">${w}</span>` : JAVA_BI.has(w) ? `<span class="t-bi">${w}</span>` : esc(w);
    }
  }
  return out + esc(src.slice(last));
}

/* ------------------------------------------------------------------ code editor */

function insertText(ta, text) {
  ta.focus();
  if (!document.execCommand('insertText', false, text)) {
    ta.setRangeText(text, ta.selectionStart, ta.selectionEnd, 'end');
    ta.dispatchEvent(new Event('input'));
  }
}
function editorKeys(e, ta) {
  if (ta.readOnly) return;
  const v = ta.value;
  const s = ta.selectionStart;
  const t = ta.selectionEnd;
  if (e.key === 'Tab' && !e.metaKey && !e.ctrlKey && !e.altKey) {
    e.preventDefault();
    const ls = v.lastIndexOf('\n', s - 1) + 1;
    if (s !== t && v.slice(s, t).includes('\n')) {
      const block = v.slice(ls, t);
      const nb = e.shiftKey ? block.replace(/^ {1,2}/gm, '') : block.replace(/^/gm, '  ');
      ta.setSelectionRange(ls, t);
      insertText(ta, nb);
      ta.setSelectionRange(ls, ls + nb.length);
    } else if (e.shiftKey) {
      const n = v.startsWith('  ', ls) ? 2 : v.startsWith(' ', ls) ? 1 : 0;
      if (n) {
        ta.setSelectionRange(ls, ls + n);
        insertText(ta, '');
        ta.setSelectionRange(Math.max(ls, s - n), Math.max(ls, s - n));
      }
    } else insertText(ta, '  ');
  } else if (e.key === 'Enter' && !e.metaKey && !e.ctrlKey && !e.altKey && !e.isComposing && e.keyCode !== 229) {
    e.preventDefault();
    const ls = v.lastIndexOf('\n', s - 1) + 1;
    const line = v.slice(ls, s);
    const ind = line.match(/^[ \t]*/)[0];
    const lastCh = line.trimEnd().slice(-1);
    const extra = lastCh && '{[('.includes(lastCh) ? '  ' : '';
    const next = v[t];
    if (extra && next && '}])'.includes(next)) {
      insertText(ta, '\n' + ind + extra + '\n' + ind);
      const pos = s + 1 + ind.length + extra.length;
      ta.setSelectionRange(pos, pos);
    } else insertText(ta, '\n' + ind + extra);
  }
}
/**
 * 点分名称（如 com.zto.voice.dto.DistributeCmd）：双击只选中其中一段，三击选中整个名称；不含 . 的内容保持默认行为。
 * 在 mousedown 时直接设定选区并阻止默认选择，避免先闪一下整段再切换
 */
function dottedSelect(el) {
  const W = /[\w$]/, N = /[\w$.]/;
  let caret = -1;
  /** 第 clicks 次点击应选中的范围；不适用时返回 null */
  const range = clicks => {
    const v = el.value;
    const i = W.test(v[caret] || '') ? caret : W.test(v[caret - 1] || '') ? caret - 1 : -1;
    if (i < 0) return null;
    let a = i, b = i + 1;
    while (a > 0 && N.test(v[a - 1])) a--;
    while (b < v.length && N.test(v[b])) b++;
    while (v[a] === '.') a++;
    while (b > a && v[b - 1] === '.') b--;
    if (!v.slice(a, b).includes('.')) return null;
    if (clicks === 3) return [a, b];
    let x = i, y = i + 1;
    while (x > 0 && W.test(v[x - 1])) x--;
    while (y < v.length && W.test(v[y])) y++;
    return [x, y];
  };
  el.addEventListener('mousedown', e => {
    if (e.button !== 0 || (e.detail !== 2 && e.detail !== 3) || caret < 0) return;
    const r = range(e.detail);
    if (!r) return;
    e.preventDefault();
    el.focus();
    el.setSelectionRange(r[0], r[1]);
  });
  el.addEventListener('click', e => { if (e.detail === 1) caret = el.selectionStart; });
}
const measureCtx = document.createElement('canvas').getContext('2d');
function codeEditor({ value = '', lang = 'json', onChange, readOnly, hint, tools }) {
  const gutter = h('div', { class: 'gutter' });
  const pre = h('pre');
  const ta = h('textarea', { spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off', autocorrect: 'off', wrap: 'off' });
  ta.value = value;
  if (readOnly) ta.readOnly = true;
  dottedSelect(ta);
  const hintEl = hint ? h('div', { class: 'empty-hint' }, hint) : null;
  const stripe = h('div', { class: 'diag-line' });
  const root = h('div', { class: 'code' }, gutter, h('div', { class: 'body' }, stripe, pre, ta), hintEl, tools ? h('div', { class: 'float' }, tools) : null);
  /** 当前的错误/警告：{ level: 'err' | 'warn', pos?, line? }（只做视觉提示：出错行底色 + 行号标红；没有位置时行号栏边缘标红） */
  let diag = null;
  function placeDiag() {
    stripe.style.display = diag && diag.line ? '' : 'none';
    if (diag && diag.line) stripe.style.top = 10 + (diag.line - 1) * 20 - ta.scrollTop + 'px';
    [...gutter.children].forEach((el, i) => el.classList.toggle('mark', !!diag && diag.line === i + 1));
  }
  let lines = -1;
  const hl = lang === 'java' ? hlJava : lang === 'text' ? esc : hlJson;
  function sync() {
    pre.scrollTop = ta.scrollTop;
    pre.scrollLeft = ta.scrollLeft;
    gutter.scrollTop = ta.scrollTop;
    placeDiag();
  }
  function paint() {
    const v = ta.value;
    pre.innerHTML = (v.length > 400000 ? esc(v) : hl(v)) + '\n';
    let n = 1;
    for (let i = 0; i < v.length; i++) if (v.charCodeAt(i) === 10) n++;
    if (n !== lines) {
      lines = n;
      let g = '';
      for (let i = 1; i <= n; i++) g += '<div>' + i + '</div>';
      gutter.innerHTML = g;
    }
    if (hintEl) hintEl.style.display = /^\s*(\[\s*\])?\s*$/.test(v) ? '' : 'none';
    sync();
  }
  ta.addEventListener('scroll', sync);
  ta.addEventListener('input', () => {
    paint();
    if (onChange) onChange(ta.value);
  });
  /** 补全弹窗等先处理按键，返回 true 表示已处理 */
  const keyHooks = [];
  ta.addEventListener('keydown', e => { if (!keyHooks.some(f => f(e))) editorKeys(e, ta); });
  paint();
  root.ed = {
    get: () => ta.value,
    set: v => { ta.value = v; paint(); },
    /** 显示（或清除）一条错误/警告：pos 为字符位置，对应行高亮 */
    diag: dg => {
      diag = dg || null;
      if (diag && diag.pos != null) diag.line = ta.value.slice(0, diag.pos).split('\n').length;
      root.classList.toggle('warn', !!diag && diag.level === 'warn');
      root.classList.toggle('diag-nopos', !!diag && !diag.line);
      placeDiag();
    },
    /** 字符位置在编辑器内的坐标（行底部），用于定位补全弹窗 */
    caretXY: pos => {
      const v = ta.value;
      const ls = v.lastIndexOf('\n', pos - 1) + 1;
      const line = v.slice(0, ls).split('\n').length - 1;
      measureCtx.font = getComputedStyle(ta).font;
      return {
        x: gutter.offsetWidth + 6 + measureCtx.measureText(v.slice(ls, pos)).width - ta.scrollLeft,
        y: 10 + (line + 1) * 20 - ta.scrollTop,
      };
    },
    onKey: f => keyHooks.push(f),
    root,
    replaceAll: v => { ta.focus(); ta.select(); insertText(ta, v); },
    focus: () => ta.focus(),
    ta,
  };
  return root;
}

/* ------------------------------------------------------------------ json viewer */

function jsonViewer(root, hideClass) {
  let count = 0;
  const countNodes = n => { count++; if (count < 3000 && (n.t === 'o' || n.t === 'a')) n.e.forEach(x => countNodes(n.t === 'o' ? x[1] : x)); };
  countNodes(root);
  const expandDepth = count < 1500 ? 99 : 2;
  const punc = s => h('span', { class: 't-punc' }, s);
  function scalar(n) {
    switch (n.t) {
      case 's': return h('span', { class: 't-str s' }, JSON.stringify(n.v));
      case 'n': return h('span', { class: 't-num' }, n.v);
      case 'b': return h('span', { class: 't-bool' }, n.v);
      case 'z': return h('span', { class: 't-bool' }, 'null');
      default: return h('span', { class: 't-var' }, n.v);
    }
  }
  function node(key, n, depth, last) {
    const comma = last ? '' : ',';
    const keyEls = key == null ? [] : [h('span', { class: 't-key' + (key === 'class' ? ' cls' : '') }, JSON.stringify(key)), punc(': ')];
    if (n.t !== 'o' && n.t !== 'a') {
      return h('div', { class: 'ln' + (key === 'class' ? ' cls' : '') }, keyEls, scalar(n), punc(comma));
    }
    const entries = n.t === 'o' ? n.e.filter(([k]) => !(hideClass && k === 'class')) : n.e.map(v => [null, v]);
    const [o, c] = n.t === 'o' ? ['{', '}'] : ['[', ']'];
    if (!entries.length) return h('div', { class: 'ln' }, keyEls, punc(o + c + comma));
    const tg = h('span', { class: 'tg' });
    const folded = h('span', { class: 'sum' }, ` … ${c}${comma}  ${entries.length} ${n.t === 'o' ? '个字段' : '项'}`);
    const head = h('div', { class: 'ln' }, tg, keyEls, punc(o), folded);
    const kids = h('div', { class: 'kids' });
    const tail = h('div', { class: 'ln' }, punc(c + comma));
    let built = false;
    const set = collapsed => {
      if (!collapsed && !built) {
        built = true;
        entries.forEach(([k, v], i) => kids.append(node(k, v, depth + 1, i === entries.length - 1)));
      }
      kids.style.display = tail.style.display = collapsed ? 'none' : '';
      folded.style.display = collapsed ? '' : 'none';
      tg.textContent = collapsed ? '▸' : '▾';
      head.dataset.c = collapsed ? '1' : '';
    };
    tg.onclick = folded.onclick = () => set(head.dataset.c !== '1');
    set(depth >= expandDepth);
    return h('div', null, head, kids, tail);
  }
  return h('div', { class: 'jv' }, node(null, root, 0, true));
}

/* ------------------------------------------------------------------ overlays */

function toast(msg, isErr) {
  let box = document.querySelector('.toasts');
  if (!box) document.body.append(box = h('div', { class: 'toasts' }));
  const t = h('div', { class: 'toast' + (isErr ? ' err' : '') }, msg);
  box.append(t);
  setTimeout(() => t.remove(), isErr ? 5200 : 2200);
}
let menuEl = null;
/** 叠加在菜单之上的右键菜单（如“打开”菜单中最近文件的右键），关闭它不影响下面的菜单 */
let subMenuEl = null;
function closeSubMenu() {
  if (subMenuEl) { subMenuEl.remove(); subMenuEl = null; }
}
function closeMenu() {
  closeSubMenu();
  if (menuEl) { menuEl.remove(); menuEl = null; }
}
/** sub 为 true 时作为叠加的右键菜单显示，保留已打开的菜单 */
function showMenu(x, y, items, sub) {
  if (sub) closeSubMenu();
  else closeMenu();
  const m = h('div', { class: 'menu' + (sub ? ' sub' : '') });
  for (const it of items) {
    if (!it) continue;
    if (it === '-') m.append(h('div', { class: 'hr' }));
    else if (it.label && !it.run) m.append(h('div', { class: 'lbl' }, it.label));
    else {
      m.append(h('div', {
        class: 'mi' + (it.danger ? ' danger' : ''),
        title: it.title || null,
        onclick: () => { if (sub) closeSubMenu(); else closeMenu(); it.run(); },
        oncontextmenu: it.context ? e => { e.preventDefault(); it.context(e); } : null,
      }, it.icon ? I(it.icon, 's') : h('span', { style: { width: '14px' } }), it.text, it.key ? h('span', { class: 'k' }, it.key) : null));
    }
  }
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = Math.min(x, innerWidth - r.width - 8) + 'px';
  m.style.top = Math.min(y, innerHeight - r.height - 8) + 'px';
  if (sub) subMenuEl = m;
  else menuEl = m;
}
function modal({ title, body, buttons }) {
  return new Promise(resolve => {
    const close = v => { mask.remove(); document.removeEventListener('keydown', onKey, true); resolve(v); };
    const onKey = e => {
      // 叠加多个对话框时（如在设置里检查更新）只有最上面的响应按键
      const masks = document.querySelectorAll('.mask');
      if (masks[masks.length - 1] !== mask) return;
      if (e.key === 'Escape') { e.stopPropagation(); close(null); }
      if (e.key === 'Enter' && !(e.target instanceof HTMLTextAreaElement)) {
        const p = buttons.find(b => b.primary);
        if (p) { e.preventDefault(); close(typeof p.value === 'function' ? p.value() : p.value); }
      }
    };
    const mask = h('div', { class: 'mask', onmousedown: e => { if (e.target === mask) close(null); } },
      h('div', { class: 'modal' },
        h('div', { class: 'mh' }, title),
        h('div', { class: 'mb' }, body),
        h('div', { class: 'mf' }, buttons.map(b => h('button', {
          class: 'btn' + (b.primary ? ' primary' : '') + (b.danger ? ' danger' : ''),
          onclick: () => close(typeof b.value === 'function' ? b.value() : b.value),
        }, b.text)))));
    document.addEventListener('keydown', onKey, true);
    document.body.append(mask);
    const f = mask.querySelector('input,textarea');
    if (f) setTimeout(() => f.focus(), 30);
  });
}

function promptText(title, value, help) {
  const input = h('input', { class: 'input', value: value || '', spellcheck: 'false' });
  return modal({
    title,
    body: [input, help ? h('div', { class: 'help', style: { marginTop: '8px' } }, help) : null],
    buttons: [{ text: '取消', value: null }, { text: '确定', value: () => input.value, primary: true }],
  });
}
const CUSTOM_OPT = '\u0000custom';
/**
 * 下拉选择：opts 为值或 [值, 显示文字]；empty 不为 null 时提供“留空”选项。
 * 列表以外的值（如 ${变量}）会原样保留并显示，也可以通过“自定义…”填写。
 */
function selectInput({ value, opts, empty, onPick, cls }) {
  const sel = h('select', { class: cls || 'input ui' });
  let cur = value || '';
  const fill = () => {
    const list = opts.map(o => (Array.isArray(o) ? o : [o, o]));
    sel.innerHTML = '';
    if (empty != null) sel.append(h('option', { value: '' }, empty));
    list.forEach(([v, t]) => sel.append(h('option', { value: v }, t)));
    if (cur && !list.some(([v]) => v === cur)) sel.append(h('option', { value: cur }, cur + '（自定义）'));
    sel.append(h('option', { value: CUSTOM_OPT }, '自定义…'));
    sel.value = cur;
  };
  fill();
  sel.addEventListener('change', async () => {
    if (sel.value !== CUSTOM_OPT) {
      cur = sel.value;
      onPick(cur);
      return;
    }
    sel.value = cur;
    const v = await promptText('自定义值', cur, '可以填写列表以外的值，也可以使用 ${变量}');
    if (v == null) return;
    cur = v.trim();
    fill();
    onPick(cur);
  });
  return sel;
}

/* ------------------------------------------------------------------ native bridge */

let nativeSeq = 0;
const nativeWait = new Map();
window.__nativeReply = (id, value) => {
  const r = nativeWait.get(id);
  if (r) { nativeWait.delete(id); r(value || null); }
};
window.__hasDirty = () => S.docs.some(d => d.dirty) || pending.size > 0;
window.__command = cmd => { if (COMMANDS[cmd]) COMMANDS[cmd](); };
let closing = false;
window.__requestClose = async () => {
  nativePost({ type: 'closeAck' });
  if (closing) return;
  closing = true;
  try {
    if (await settleDirty('关闭')) nativePost({ type: 'close' });
  } finally {
    closing = false;
  }
};
/** 有未保存的修改时询问保存 / 不保存 / 取消；返回是否继续（verb 如“关闭”“更新”） */
async function settleDirty(verb) {
  await flush().catch(() => {});
  const dirty = S.docs.filter(d => d.dirty);
  if (!dirty.length) return true;
  const r = await modal({
    title: '有未保存的修改',
    body: dirty.map(d => '“' + d.name + '”').join('、') + ' 尚未保存，' + verb + '后修改会丢失。',
    buttons: [{ text: '取消', value: null }, { text: '不保存', value: 'discard', danger: true }, { text: '保存并' + verb, value: 'save', primary: true }],
  });
  if (!r) return false;
  if (r === 'save') {
    for (const d of dirty) if (!(await save(d))) return false;
  }
  return true;
}
function nativeCall(msg) {
  return new Promise(res => {
    const id = ++nativeSeq;
    nativeWait.set(id, res);
    nativePost(Object.assign({ id }, msg));
  });
}
async function pickPath(mode, name, dir) {
  if (NATIVE) return nativeCall({ type: mode === 'save' ? 'pickSave' : 'pickOpen', name: name || '', dir: dir || '' });
  return (await api('pick', { mode, name, dir })).path;
}
const dirOf = p => (p ? p.replace(/[\\/][^\\/]*$/, '') : '');
const baseName = p => p.replace(/^.*[\\/]/, '');

/* ------------------------------------------------------------------ commands */

async function openPath(path) {
  try {
    const d = applyDoc(await api('open', { path }));
    S.cur = d.id;
    S.state = await api('state');
    render();
  } catch (e) {
    toast(e.message, true);
    S.state = await api('state');
    render();
  }
}
async function openDialog() {
  const d = doc();
  const p = await pickPath('open', '', d && d.path ? dirOf(d.path) : (S.state.recent[0] ? dirOf(S.state.recent[0]) : ''));
  if (p) await openPath(p);
}
async function newDoc() {
  const d = applyDoc(await api('new'));
  S.cur = d.id;
  const first = samplersIn(d.root)[0];
  if (first) S.sel[d.id] = first.id;
  render();
}
async function save(d, as) {
  if (!d) return false;
  try {
    await flush();
    let path = d.path;
    if (as || !path) {
      path = await pickPath('save', d.name.endsWith('.jmx') ? d.name : d.name + '.jmx', dirOf(d.path || S.state.recent[0] || ''));
      if (!path) return false;
    }
    applyDoc(await api('save', { docId: d.id, path }));
    S.state = await api('state');
    renderTabs();
    renderSideFoot();
    toast('已保存 ' + baseName(path));
    return true;
  } catch (e) {
    toast('保存失败：' + e.message, true);
    return false;
  }
}
async function closeDoc(d) {
  await flush();
  if (d.dirty) {
    const r = await modal({
      title: '保存对 “' + d.name + '” 的修改？',
      body: '不保存的话，修改会丢失。',
      buttons: [{ text: '取消', value: null }, { text: '不保存', value: 'discard', danger: true }, { text: '保存', value: 'save', primary: true }],
    });
    if (!r) return;
    if (r === 'save' && !(await save(d))) return;
  }
  await api('close', { docId: d.id });
  persistUi(d);
  const i = S.docs.indexOf(d);
  S.docs.splice(i, 1);
  if (S.cur === d.id) S.cur = (S.docs[i] || S.docs[i - 1] || {}).id || null;
  S.state = await api('state');
  render();
}
async function structural(action, body, selectNew) {
  const d = doc();
  await flush();
  try {
    const j = await api(action, Object.assign({ docId: d.id }, body));
    applyDoc(j);
    if (selectNew && j.newId) {
      S.sel[d.id] = j.newId;
      for (let p = d.parentOf.get(j.newId); p; p = d.parentOf.get(p.id)) S.expanded[d.id].add(p.id);
    }
    if (!d.byId.has(S.sel[d.id])) delete S.sel[d.id];
    renderTabs();
    renderSide();
    renderContent();
    if (selectNew && j.newId) {
      const ti = document.querySelector('.title-input');
      if (ti) { ti.focus(); ti.select(); }
    }
  } catch (e) {
    toast(e.message, true);
  }
}
function addNode(parent, kind, index) {
  return structural('add', { parentId: parent.id, kind, index: index == null ? -1 : index }, true);
}
/** 新建请求：选中请求时插在其后，选中分组时加在分组末尾 */
async function newRequest(kind) {
  kind = typeof kind === 'string' ? kind : 'dubbo';
  const d = doc();
  if (!d) return newDoc();
  let n = d.byId.get(S.sel[d.id]);
  if (n && SENDABLE.has(n.kind)) {
    const p = parentOf(d, n);
    return addNode(p, kind, p.children.indexOf(n) + 1);
  }
  if (n && (n.kind === 'group' || n.kind === 'controller')) return addNode(n, kind);
  while (n && !CONTAINER.has(n.kind)) n = parentOf(d, n);
  let g = n && n.kind !== 'plan' ? n : d.root.children.find(c => c.kind === 'group');
  if (!g) {
    await addNode(d.root, 'group');
    g = d.byId.get(S.sel[d.id]);
  }
  return addNode(g, kind);
}
async function removeNode(n) {
  const d = doc();
  const kids = n.children.filter(c => !HIDDEN.has(c.kind)).length;
  const ok = await modal({
    title: '删除“' + n.name + '”？',
    body: kids ? `将同时删除其下的 ${kids} 个元素。保存前可以关闭文件不保存来撤销。` : '保存前可以关闭文件不保存来撤销。',
    buttons: [{ text: '取消', value: false }, { text: '删除', value: true, primary: true }],
  });
  if (!ok) return;
  const p = parentOf(d, n);
  const sib = p.children.filter(c => !HIDDEN.has(c.kind));
  const next = sib[sib.indexOf(n) + 1] || sib[sib.indexOf(n) - 1] || p;
  S.sel[d.id] = next.id;
  await structural('delete', { nodeId: n.id });
}
function toggleEnabled(n) {
  if (!CONFIG_KINDS.has(n.kind)) return;
  queue(doc(), n, { enabled: !n.enabled });
  renderSide();
  if (S.sel[S.cur] === n.id) renderContent();
}
async function sendCurrent() {
  const d = doc();
  const n = d && d.byId.get(S.sel[d.id]);
  if (n && SENDABLE.has(n.kind)) await send(d, n);
}
async function send(d, n) {
  const key = resKey(d, n);
  if (S.sending.has(key)) return;
  const started = performance.now();
  S.cancelReq.delete(key);
  S.sending.set(key, started);
  updateRowStatus(d, n);
  if (S.sel[d.id] === n.id) renderResp();
  try {
    await flush();
    const r = S.cancelReq.has(key)
      ? { ok: false, cancelled: true, error: { kind: '已中断', message: '调用已被手动中断' }, logs: [], request: null, elapsed: performance.now() - started, time: Date.now() }
      : await api('send', { docId: d.id, nodeId: n.id });
    S.results.set(key, r);
  } catch (e) {
    S.results.set(key, { ok: false, error: { kind: '发送失败', message: e.message }, logs: [], request: null, vars: null, time: Date.now() });
  } finally {
    S.sending.delete(key);
    S.cancelReq.delete(key);
    updateRowStatus(d, n);
    if (S.cur === d.id && S.sel[d.id] === n.id) renderResp();
  }
}
/** 主动中断正在进行的调用：后台立即返回“已中断”，并中断调用线程 */
async function cancelSend(d, n) {
  const key = resKey(d, n);
  if (!S.sending.has(key)) return;
  S.cancelReq.add(key);
  // 请求可能还没到达后台（正在提交编辑），稍后重试
  for (let i = 0; i < 5 && S.sending.has(key); i++) {
    try {
      if ((await api('cancel', { docId: d.id, nodeId: n.id })).cancelled) return;
    } catch (e) {
      toast(e.message, true);
      return;
    }
    await new Promise(r => setTimeout(r, 150));
  }
}
function cancelCurrent() {
  const d = doc();
  const n = d && d.byId.get(S.sel[d.id]);
  if (n && S.sending.has(resKey(d, n))) {
    cancelSend(d, n);
    return true;
  }
  return false;
}
/** 发送按钮：发送中变为“中断” */
function sendButton(d, n) {
  if (S.sending.has(resKey(d, n))) {
    return h('button', { class: 'send stop', title: '中断本次调用 (Esc)', onclick: () => cancelSend(d, n) },
      h('span', { class: 'spinner', style: { borderColor: 'rgba(255,255,255,.3)', borderTopColor: '#fff' } }), '中断', h('span', { class: 'kbd' }, 'Esc'));
  }
  return h('button', { class: 'send', title: '发送 (' + MOD + '↵)', onclick: () => send(d, n) }, I('send', 's'), '发送', h('span', { class: 'kbd' }, MOD + '↵'));
}
async function showSettings() {
  S.state = await api('state');
  const ta = h('textarea', { class: 'input', rows: 4, placeholder: '/Users/me/JMeter/apache-jmeter-5.6.3/lib/ext' }, S.state.extPaths.join('\n'));
  let auto = S.state.autoUpdate !== false;
  const sw = h('label', { class: 'switch' + (auto ? ' on' : '') }, h('span', { class: 'tr' }), h('span', null, '启动时自动检查更新'));
  sw.addEventListener('click', () => { auto = !auto; sw.classList.toggle('on', auto); });
  const r = await modal({
    title: '设置',
    body: [
      h('div', { class: 'field' },
        h('label', null, '脚本扩展 Jar（每行一个目录或 jar 文件）'),
        ta,
        h('div', { class: 'help' }, '前置/后置脚本里用到的第三方类（如公司工具包、groovy）放在这里。',
          h('br'), '也可以直接把 jar 放进 ', h('code', null, S.state.home + '/lib'))),
      h('div', { class: 'upd-row' }, sw,
        h('button', { class: 'btn sm', onclick: () => checkUpdate(true) }, I('download', 's'), '检查更新')),
      h('div', { class: 'help', style: { marginTop: '14px' } }, `Dubbo Lite ${S.state.version} · Java ${S.state.java} · Dubbo 2.6.8`),
    ],
    buttons: [{ text: '取消', value: null }, { text: '保存', value: () => ta.value, primary: true }],
  });
  if (r == null) return;
  S.state = await api('settings', {
    extPaths: r.split('\n').map(s => s.trim()).filter(Boolean),
    autoUpdate: auto,
  });
  toast('设置已保存');
}

/* ------------------------------------------------------------------ update */

/** 检查到的新版本（顶栏显示“新版本”按钮） */
let updateInfo = null;
/** manual：用户主动检查，结果都要提示；自动检查时只在有新版本且没被跳过时在顶栏提示 */
async function checkUpdate(manual) {
  let r;
  try {
    r = await api('updateCheck');
  } catch (e) {
    if (manual) toast(e.message, true);
    return;
  }
  updateInfo = r.newer ? r : null;
  renderTop();
  if (!manual) return;
  if (r.newer) showUpdate();
  else toast('已是最新版本（' + r.current + '）');
}
async function showUpdate() {
  const u = updateInfo;
  if (!u) return;
  const r = await modal({
    title: '发现新版本',
    body: [
      h('div', { class: 'upd-ver' }, '当前版本 ', h('b', null, u.current), h('span', { class: 'arrow' }, '→'), '最新版本 ', h('b', null, u.latest),
        u.date ? h('span', { class: 'help' }, ' · ' + u.date) : null, u.size ? h('span', { class: 'help' }, ' · ' + fmtSize(u.size)) : null),
      u.notes ? h('div', { class: 'upd-notes' }, u.notes) : null,
      u.canInstall ? h('div', { class: 'help', style: { marginTop: '10px' } }, '更新时会关闭并自动重新打开 Dubbo Lite。')
        : h('div', { class: 'note warn', style: { marginTop: '10px' } }, u.reason),
    ],
    buttons: [
      { text: '跳过此版本', value: 'skip' },
      { text: '稍后', value: null },
      u.canInstall ? { text: '立即更新', value: 'install', primary: true } : { text: '前往下载', value: 'page', primary: true },
    ],
  });
  try {
    if (r === 'skip') {
      await api('updateSkip', { version: u.latest });
      u.skipped = true;
      renderTop();
      toast('已跳过 ' + u.latest + '，有更新的版本时会再提示');
    } else if (r === 'page') {
      await api('updatePage');
    } else if (r === 'install') {
      await installUpdate(u);
    }
  } catch (e) {
    toast(e.message, true);
  }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
/** 下载 → 校验 → 处理未保存的修改 → 启动安装脚本 → 通知外壳退出（脚本替换后重新打开） */
async function installUpdate(u) {
  const fill = h('i');
  const text = h('div', { class: 'help' }, '正在连接…');
  const mask = h('div', { class: 'mask' }, h('div', { class: 'modal' },
    h('div', { class: 'mh' }, '正在更新到 ' + u.latest),
    h('div', { class: 'mb' }, h('div', { class: 'upd-bar' }, fill), text)));
  document.body.append(mask);
  try {
    let s = await api('updateDownload');
    while (s.phase === 'downloading') {
      const pct = s.total > 0 ? Math.min(100, s.done * 100 / s.total) : 0;
      fill.style.width = pct + '%';
      text.textContent = '正在下载… ' + fmtSize(s.done) + (s.total > 0 ? ' / ' + fmtSize(s.total) : '');
      await sleep(250);
      s = await api('updateStatus');
    }
    if (s.phase !== 'ready') throw new Error(s.error || '下载失败');
    fill.style.width = '100%';
    text.textContent = '下载完成';
  } catch (e) {
    mask.remove();
    toast('更新失败：' + e.message, true);
    return;
  }
  mask.remove();
  if (!(await settleDirty('更新'))) {
    toast('新版本已下载，下次点“立即更新”即可安装');
    return;
  }
  await api('updateInstall');
  document.body.append(h('div', { class: 'mask' }, h('div', { class: 'modal' },
    h('div', { class: 'mh' }, '正在安装 ' + u.latest),
    h('div', { class: 'mb' }, 'Dubbo Lite 即将关闭，安装完成后会自动重新打开。'))));
  await sleep(400);
  nativePost({ type: 'quit' });
}
async function resetConnections() {
  const r = await api('reset');
  toast(r.count ? `已断开 ${r.count} 个服务引用，下次发送会重新连接` : '当前没有已建立的连接');
}
async function clearVars() {
  const d = doc();
  if (!d) return;
  await api('clearVars', { docId: d.id });
  for (const [k, v] of S.results) if (k.startsWith(d.id + ':') && v.vars) v.vars = {};
  toast('会话变量已清空（用户定义变量会在下次发送时重新载入）');
  renderResp();
}
const COMMANDS = {
  open: openDialog,
  new: newDoc,
  save: () => save(doc()),
  saveAs: () => save(doc(), true),
  send: sendCurrent,
  newRequest,
  settings: showSettings,
  checkUpdate: () => checkUpdate(true),
  find: () => { const i = document.querySelector('.search input'); if (i) { i.focus(); i.select(); } },
};

/* ------------------------------------------------------------------ render: shell */

const els = {};
function render() {
  const app = document.getElementById('app');
  app.innerHTML = '';
  closeMenu();
  els.top = h('div', { class: 'top' });
  app.append(els.top);
  renderTop();
  const d = doc();
  if (!d) {
    app.append(welcome());
    return;
  }
  els.side = h('div', { class: 'side', style: { width: (localStorage.getItem('dl.sideW') || 300) + 'px' } });
  els.content = h('div', { class: 'content' });
  app.append(h('div', { class: 'main' }, els.side, vResizer(), els.content));
  buildSide();
  renderContent();
}
function renderTop() {
  const t = els.top;
  t.innerHTML = '';
  els.tabs = h('div', { class: 'tabs' });
  t.append(
    h('div', { class: 'brand' }, h('img', { src: 'icon.svg', alt: '' }), h('span', null, 'Dubbo Lite')),
    els.tabs,
    h('div', { class: 'actions' },
      updateInfo && !updateInfo.skipped && h('button', { class: 'btn upd-pill', title: '有新版本可以安装', onclick: showUpdate }, I('download', 's'), '新版本 ' + updateInfo.latest),
      h('button', { class: 'btn ghost', title: '打开最近的文件，或从文件夹打开 (' + MOD + 'O)', onclick: e => openMenu(e.currentTarget) }, I('open', 's'), '打开', I('down', 'xs')),
      h('button', { class: 'btn ghost', title: '新建测试计划', onclick: newDoc }, I('plus', 's'), '新建'),
      els.saveBtn = h('button', { class: 'btn', title: '保存 (' + MOD + 'S)', onclick: () => save(doc()) }, I('save', 's'), '保存'),
      h('button', { class: 'btn ghost icon', title: '更多', onclick: e => moreMenu(e.currentTarget) }, I('more'))));
  renderTabs();
}
function renderTabs() {
  if (!els.tabs) return;
  els.tabs.innerHTML = '';
  for (const d of S.docs) {
    els.tabs.append(h('div', {
      class: 'tab' + (d.id === S.cur ? ' on' : ''),
      title: d.path || d.name,
      onclick: () => { if (S.cur !== d.id) { S.cur = d.id; render(); } },
      onauxclick: e => { if (e.button === 1) closeDoc(d); },
    }, I('file', 'xs'), h('span', { class: 'nm' }, d.name),
      d.dirty ? h('span', { class: 'dot dirty-dot', title: '未保存' }) : null,
      h('span', { class: 'x', title: '关闭', onclick: e => { e.stopPropagation(); closeDoc(d); } }, I('x', 'xs'))));
  }
  const d = doc();
  if (els.saveBtn) {
    els.saveBtn.disabled = !d || (!d.dirty && !!d.path);
    els.saveBtn.className = 'btn' + (d && (d.dirty || !d.path) ? ' primary' : '');
  }
  const title = d ? (d.dirty ? '● ' : '') + d.name + ' — Dubbo Lite' : 'Dubbo Lite';
  if (document.title !== title) {
    document.title = title;
    if (WIN_BRIDGE) nativePost({ type: 'title', title });
  }
}
function moreMenu(btn) {
  const r = btn.getBoundingClientRect();
  const d = doc();
  showMenu(r.right - 220, r.bottom + 6, [
    d && { text: '另存为…', icon: 'save', key: '⇧' + MOD + 'S', run: () => save(d, true) },
    d && d.path && CAN_REVEAL && { text: REVEAL_LABEL, icon: 'finder', run: () => reveal(d.path) },
    d && '-',
    { text: '重置 Dubbo 连接', icon: 'refresh', run: resetConnections },
    d && { text: '清空会话变量', icon: 'eraser', run: clearVars },
    '-',
    { label: '主题' },
    ...THEMES.map(([v, text]) => ({ text, icon: (S.state.theme || 'system') === v ? 'check' : null, run: () => setTheme(v) })),
    '-',
    { text: '检查更新…', icon: 'download', run: () => checkUpdate(true) },
    { text: '设置…', icon: 'gear', run: showSettings },
  ]);
}
/** “打开”菜单：最近打开的文件 + 从文件夹打开 */
let openMenuBtn = null;
function openMenu(btn) {
  openMenuBtn = btn;
  const r = btn.getBoundingClientRect();
  const recent = (S.state.recent || []).slice(0, 10);
  const home = (S.state.home || '').replace(/[\\/]\.dubbo-lite$/, '');
  const where = p => {
    let dir = dirOf(p);
    if (home && dir.startsWith(home)) dir = '~' + dir.slice(home.length);
    return dir.length > 32 ? '…' + dir.slice(-31) : dir;
  };
  showMenu(r.left, r.bottom + 6, [
    recent.length && { label: '最近打开' },
    ...recent.map(p => ({ text: baseName(p), icon: 'file', key: where(p), title: p + '\n右键可从列表移除', run: () => openPath(p), context: e => recentMenu(e, p, true) })),
    recent.length && '-',
    { text: '从文件夹打开…', icon: 'folder', key: MOD + 'O', run: openDialog },
  ]);
}
/** 最近打开的右键菜单；inMenu 表示来自“打开”菜单：叠加显示，移除后原地刷新该菜单 */
function recentMenu(e, p, inMenu) {
  showMenu(e.clientX, e.clientY, [{
    text: '从最近打开中移除', icon: 'x',
    run: async () => {
      await forgetRecent(p);
      if (inMenu && menuEl && openMenuBtn) openMenu(openMenuBtn);
    },
  }], inMenu);
}
async function forgetRecent(p) {
  try {
    S.state = await api('removeRecent', { path: p });
  } catch (e) {
    return toast(e.message, true);
  }
  if (!doc()) render();
}
const THEMES = [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']];
/** 界面主题：system 跟随系统，light / dark 固定；macOS 外壳同步标题栏外观 */
function applyTheme(t) {
  const theme = t === 'light' || t === 'dark' ? t : 'system';
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
  if (MAC_BRIDGE) nativeCall({ type: 'theme', theme });
}
async function setTheme(t) {
  applyTheme(t);
  try {
    S.state = await api('settings', { theme: t });
  } catch (e) {
    toast(e.message, true);
  }
}
function welcome() {
  const recent = S.state.recent || [];
  return h('div', { class: 'welcome' },
    h('div', { class: 'wcard' },
      h('img', { src: 'icon.svg', alt: '' }),
      h('h1', null, 'Dubbo Lite'),
      h('div', { class: 'sub' }, '轻量、专注的 Dubbo 请求工具 · 直接读写 JMeter 的 .jmx 文件'),
      h('div', { class: 'acts' },
        h('button', { class: 'btn primary', onclick: openDialog }, I('open', 's'), '打开 JMX 文件', h('span', { class: 'kbd' }, MOD + 'O')),
        h('button', { class: 'btn', onclick: newDoc }, I('plus', 's'), '新建测试计划')),
      recent.length ? h('div', { class: 'recent' },
        h('h3', null, '最近打开'),
        recent.map(p => h('div', { class: 'rec', title: p, onclick: () => openPath(p), oncontextmenu: e => { e.preventDefault(); recentMenu(e, p); } },
          h('span', { class: 'ic' }, I('file')),
          h('div', { class: 'grow' }, h('div', { class: 'n' }, baseName(p)), h('div', { class: 'p' }, '‎' + dirOf(p))),
          h('button', {
            class: 'btn ghost sm icon x', title: '从列表移除',
            onclick: e => { e.stopPropagation(); forgetRecent(p); },
          }, I('x', 'xs'))))) : null,
      h('div', { class: 'drop-hint' }, '也可以把 .jmx 文件直接拖进窗口')));
}
function vResizer() {
  const r = h('div', { class: 'vresizer' });
  r.addEventListener('mousedown', e => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = els.side.getBoundingClientRect().width;
    const move = ev => {
      const w = Math.max(220, Math.min(560, startW + ev.clientX - startX));
      els.side.style.width = w + 'px';
      localStorage.setItem('dl.sideW', w);
    };
    const up = () => { removeEventListener('mousemove', move); removeEventListener('mouseup', up); document.body.style.cursor = ''; };
    addEventListener('mousemove', move);
    addEventListener('mouseup', up);
    document.body.style.cursor = 'col-resize';
  });
  return r;
}

/* ------------------------------------------------------------------ render: sidebar */

function buildSide() {
  const s = els.side;
  s.innerHTML = '';
  const input = h('input', {
    placeholder: '搜索请求 / 接口 / 方法', value: S.filter,
    oninput: e => { S.filter = e.target.value; renderSide(); },
    onkeydown: e => {
      if (e.key === 'Escape') { S.filter = ''; e.target.value = ''; renderSide(); e.target.blur(); }
      if (e.key === 'Enter') {
        const first = els.tree.querySelector('.row.dubbo');
        if (first) select(+first.dataset.id);
      }
    },
  });
  s.append(
    h('div', { class: 'search' },
      h('div', { class: 'box' }, I('search', 's'), input, h('span', { class: 'hint' }, MOD + 'K')),
      h('button', { class: 'btn icon', title: '新建', onclick: e => addMenu(e.currentTarget) }, I('plus', 's'))),
    els.tree = h('div', { class: 'tree', tabindex: '0', onkeydown: treeKeys }),
    els.sideFoot = h('div', { class: 'side-foot' }));
  renderSide();
  renderSideFoot();
}
function addMenu(btn) {
  const d = doc();
  const r = btn.getBoundingClientRect();
  const n = d.byId.get(S.sel[d.id]);
  let g = n;
  while (g && !CONTAINER.has(g.kind)) g = parentOf(d, g);
  g = g || d.root;
  const where = g.kind === 'plan' ? '' : '（加到“' + g.name + '”）';
  showMenu(r.left, r.bottom + 6, [
    { text: '新建 Dubbo 请求', icon: 'send', run: newRequest },
    { text: '新建 ZMS 消息', icon: 'send', run: () => newRequest('zms') },
    { text: '新建分组', icon: 'folder', run: () => addNode(d.root, 'group') },
    '-',
    { label: '配置元素' + where },
    { text: 'Dubbo 默认配置', icon: 'plug', run: () => addNode(g, 'defaults') },
    { text: 'BeanShell 前置脚本', icon: 'braces', run: () => addNode(g, 'pre') },
    { text: 'BeanShell 后置脚本', icon: 'braces', run: () => addNode(g, 'post') },
    { text: 'CSV 数据文件', icon: 'text', run: () => addNode(g, 'csv') },
    { text: '用户定义的变量', icon: 'text', run: () => addNode(g, 'vars') },
  ]);
}
function renderSideFoot() {
  const d = doc();
  if (!els.sideFoot || !d) return;
  const n = samplersIn(d.root).length;
  els.sideFoot.innerHTML = '';
  els.sideFoot.append(h('span', { class: 'grow', title: d.path || '' }, d.path ? dirOf(d.path).replace(/^\/Users\/[^/]+/, '~') : '尚未保存'), h('span', null, n + ' 个请求'));
}
function hl(text, f) {
  if (!f) return text;
  const i = text.toLowerCase().indexOf(f);
  if (i < 0) return text;
  return [text.slice(0, i), h('mark', null, text.slice(i, i + f.length)), text.slice(i + f.length)];
}
function renderSide() {
  const d = doc();
  if (!d || !els.tree) return;
  const f = S.filter.trim().toLowerCase();
  const exp = S.expanded[d.id];
  const rows = [];
  const matches = n => (n.name + ' ' + (n.props.interface || '') + ' ' + (n.props.method || '') + ' ' + (n.props.topic || '')).toLowerCase().includes(f);
  const walk = (n, depth, all) => {
    for (const c of visibleKids(n)) {
      const cont = isContainer(c);
      if (f && !all) {
        if (SENDABLE.has(c.kind) || c.kind === 'sampler') {
          if (matches(c)) rows.push({ n: c, depth });
        } else if (cont) {
          const at = rows.length;
          const self = c.name.toLowerCase().includes(f);
          rows.push({ n: c, depth, open: true });
          walk(c, depth + 1, self);
          if (rows.length === at + 1 && !self) rows.pop();
        }
        continue;
      }
      const open = f ? true : exp.has(c.id);
      rows.push({ n: c, depth, open });
      if (cont && open) walk(c, depth + 1, all);
    }
  };
  walk(d.root, 0, false);
  const frag = document.createDocumentFragment();
  frag.append(rowEl(d, { n: d.root, depth: 0 }, f));
  rows.forEach(r => frag.append(rowEl(d, r, f)));
  if (f && !rows.length) frag.append(h('div', { class: 'help', style: { padding: '16px 12px' } }, '没有匹配“' + S.filter + '”的请求'));
  const st = els.tree.scrollTop;
  els.tree.innerHTML = '';
  els.tree.append(frag);
  els.tree.scrollTop = st;
}
function rowStatus(d, n) {
  const key = resKey(d, n);
  if (S.sending.has(key)) return 'run';
  const r = S.results.get(key);
  return r ? (r.cancelled ? 'cancel' : r.ok ? 'ok' : 'err') : '';
}
function updateRowStatus(d, n) {
  if (S.cur !== d.id || !els.tree) return;
  const dot = els.tree.querySelector(`.row[data-id="${n.id}"] .sdot`);
  if (dot) dot.className = 'sdot ' + rowStatus(d, n);
}
function badge(kind, n) {
  if (kind === 'sampler') return h('span', { class: 'badge req', title: n ? n.testclass : '' }, n ? samplerTag(n.testclass) : 'REQ');
  const m = { defaults: ['cfg', 'CFG'], pre: ['pre', 'PRE'], post: ['post', 'POST'], csv: ['csv', 'CSV'], vars: ['var', 'VAR'] }[kind];
  return m ? h('span', { class: 'badge ' + m[0] }, m[1]) : null;
}
function rowEl(d, { n, depth, open }, f) {
  const sel = S.sel[d.id] === n.id;
  const cont = n.kind !== 'plan' && isContainer(n);
  const row = h('div', {
    class: `row ${n.kind}${sel ? ' sel' : ''}${isOn(n) ? '' : ' off'}`,
    'data-id': n.id,
    draggable: n.kind === 'plan' ? null : 'true',
    style: { paddingLeft: (n.kind === 'plan' ? 6 : 6 + depth * 16) + 'px' },
    title: n.kind === 'dubbo' ? (n.props.interface || '') + '#' + (n.props.method || '') : n.kind === 'zms' ? 'ZMS · ' + (n.props.topic || '') : KIND_LABEL[n.kind] || n.testclass,
  });
  const chev = h('span', { class: 'chev' + (cont ? (open ? ' open' : '') : ' none') }, I('chev', 'xs'));
  if (cont) chev.addEventListener('click', e => { e.stopPropagation(); toggleExpand(d, n); });
  row.append(n.kind === 'plan' ? h('span', { class: 'ic' }, I('file', 's')) : chev);
  if (n.kind === 'group' || n.kind === 'controller') row.append(h('span', { class: 'ic' }, I('folder', 's')));
  else if (SENDABLE.has(n.kind)) {
    row.append(h('span', { class: 'sdot ' + rowStatus(d, n) }));
    if (n.kind === 'zms') row.append(h('span', { class: 'badge zms' }, 'ZMS'));
  } else if (n.kind !== 'plan') row.append(badge(n.kind, n));
  row.append(h('span', { class: 'nm' }, hl(n.name, f)));
  if (n.kind === 'dubbo') row.append(h('span', { class: 'meta' }, hl(n.props.method || '', f)));
  else if (n.kind === 'zms') row.append(h('span', { class: 'meta' }, hl(n.props.topic || '', f)));
  else if (cont && !SENDABLE.has(n.kind)) row.append(h('span', { class: 'cnt' }, samplersIn(n).length || ''));
  row.addEventListener('click', () => select(n.id));
  row.addEventListener('dblclick', () => { if (cont) toggleExpand(d, n); });
  row.addEventListener('contextmenu', e => { e.preventDefault(); select(n.id); nodeMenu(d, n, e.clientX, e.clientY); });
  dnd(row, d, n);
  return row;
}
function toggleExpand(d, n) {
  const e = S.expanded[d.id];
  e.has(n.id) ? e.delete(n.id) : e.add(n.id);
  persistUi(d);
  renderSide();
}
function select(id) {
  const d = doc();
  if (!d || !d.byId.has(id)) return;
  if (S.sel[d.id] !== id) {
    S.sel[d.id] = id;
    persistUi(d);
    els.tree.querySelectorAll('.row.sel').forEach(r => r.classList.remove('sel'));
    const r = els.tree.querySelector(`.row[data-id="${id}"]`);
    if (r) { r.classList.add('sel'); r.scrollIntoView({ block: 'nearest' }); }
    renderContent();
  }
}
function treeKeys(e) {
  if (e.target !== els.tree) return;
  const rows = [...els.tree.querySelectorAll('.row')];
  const d = doc();
  const i = rows.findIndex(r => +r.dataset.id === S.sel[d.id]);
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    const nx = rows[Math.max(0, Math.min(rows.length - 1, i + (e.key === 'ArrowDown' ? 1 : -1)))];
    if (nx) select(+nx.dataset.id);
  } else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
    const n = d.byId.get(S.sel[d.id]);
    if (n && isContainer(n) && n.kind !== 'plan' && S.expanded[d.id].has(n.id) === (e.key === 'ArrowLeft')) toggleExpand(d, n);
  }
}
function nodeMenu(d, n, x, y) {
  const items = [];
  if (SENDABLE.has(n.kind)) items.push({ text: '发送', icon: 'send', key: MOD + '↵', run: () => send(d, n) }, '-');
  if (n.kind === 'plan') {
    items.push({ text: '新建分组', icon: 'folder', run: () => addNode(n, 'group') });
  }
  if (n.kind === 'group' || n.kind === 'controller') items.push({ text: '新建 Dubbo 请求', icon: 'send', run: () => addNode(n, 'dubbo') }, { text: '新建 ZMS 消息', icon: 'send', run: () => addNode(n, 'zms') });
  if (CONTAINER.has(n.kind)) {
    items.push(
      { text: '添加 Dubbo 默认配置', icon: 'plug', run: () => addNode(n, 'defaults') },
      { text: '添加前置脚本', icon: 'braces', run: () => addNode(n, 'pre') },
      { text: '添加后置脚本', icon: 'braces', run: () => addNode(n, 'post') },
      { text: '添加 CSV 数据文件', icon: 'text', run: () => addNode(n, 'csv') },
      { text: '添加用户变量', icon: 'text', run: () => addNode(n, 'vars') });
  }
  if (SENDABLE.has(n.kind)) {
    items.push(
      { text: '添加前置脚本（仅此请求）', icon: 'braces', run: () => addNode(n, 'pre') },
      { text: '添加后置脚本（仅此请求）', icon: 'braces', run: () => addNode(n, 'post') });
  }
  if (n.kind !== 'plan') {
    items.push('-',
      { text: '复制', icon: 'copy', run: () => structural('duplicate', { nodeId: n.id }, true) },
      CONFIG_KINDS.has(n.kind) && { text: n.enabled ? '停用' : '启用', icon: 'power', run: () => toggleEnabled(n) },
      { text: '上移', icon: 'up', run: () => moveBy(d, n, -1) },
      { text: '下移', icon: 'down', run: () => moveBy(d, n, 1) },
      '-',
      { text: '删除', icon: 'trash', danger: true, run: () => removeNode(n) });
  }
  showMenu(x, y, items);
}
function moveBy(d, n, delta) {
  const p = parentOf(d, n);
  const vis = visibleKids(p);
  const i = vis.indexOf(n);
  const target = vis[i + delta];
  if (!target) return;
  const idx = p.children.indexOf(target) + (delta > 0 ? 1 : 0);
  structural('move', { nodeId: n.id, parentId: p.id, index: idx });
}
let dragId = null;
function dnd(row, d, n) {
  row.addEventListener('dragstart', e => { dragId = n.id; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', n.name); });
  row.addEventListener('dragend', () => { dragId = null; clearDrop(); });
  row.addEventListener('dragover', e => {
    if (dragId == null || dragId === n.id) return;
    const pos = dropPos(e, row, n);
    if (!pos) return;
    e.preventDefault();
    clearDrop();
    row.classList.add('drop-' + pos);
  });
  row.addEventListener('dragleave', () => row.classList.remove('drop-before', 'drop-after', 'drop-inside'));
  row.addEventListener('drop', e => {
    if (dragId == null) return;
    e.preventDefault();
    e.stopPropagation();
    const pos = dropPos(e, row, n);
    clearDrop();
    const moving = d.byId.get(dragId);
    dragId = null;
    if (!pos || !moving) return;
    if (pos === 'inside') {
      S.expanded[d.id].add(n.id);
      structural('move', { nodeId: moving.id, parentId: n.id, index: -1 });
    } else {
      const p = parentOf(d, n);
      structural('move', { nodeId: moving.id, parentId: p.id, index: p.children.indexOf(n) + (pos === 'after' ? 1 : 0) });
    }
  });
}
function dropPos(e, row, n) {
  if (n.kind === 'plan') return 'inside';
  const r = row.getBoundingClientRect();
  const y = (e.clientY - r.top) / r.height;
  const canInside = n.kind === 'group' || n.kind === 'controller';
  if (canInside && y > 0.28 && y < 0.72) return 'inside';
  return y < 0.5 ? 'before' : 'after';
}
function clearDrop() {
  document.querySelectorAll('.drop-before,.drop-after,.drop-inside').forEach(x => x.classList.remove('drop-before', 'drop-after', 'drop-inside'));
}

/* ------------------------------------------------------------------ render: content */

function renderContent() {
  const d = doc();
  const c = els.content;
  if (!c || !d) return;
  c.innerHTML = '';
  els.resp = null;
  const n = d.byId.get(S.sel[d.id]) || d.root;
  const view = {
    dubbo: viewRequest, defaults: viewDefaults, pre: viewScript, post: viewScript, csv: viewCsv,
    vars: viewVars, plan: viewPlan, group: viewGroup, controller: viewGroup, sampler: viewSampler, zms: viewZms,
  }[n.kind] || viewOther;
  view(d, n, c);
}
function titleLine(d, n, extra) {
  const input = h('input', {
    class: 'title-input', value: n.name, spellcheck: 'false',
    oninput: e => {
      queue(d, n, { name: e.target.value });
      const nm = els.tree.querySelector(`.row[data-id="${n.id}"] .nm`);
      if (nm) nm.textContent = e.target.value;
    },
    onkeydown: e => { if (e.key === 'Enter') e.target.blur(); },
  });
  const sw = !CONFIG_KINDS.has(n.kind) ? null : h('label', { class: 'switch' + (n.enabled ? ' on' : ''), title: '停用后发送时不再生效（JMeter 运行时同样会跳过它）' },
    h('span', null, n.enabled ? '已启用' : '已停用'), h('span', { class: 'tr' }));
  if (sw) sw.addEventListener('click', () => toggleEnabled(n));
  return h('div', { class: 'title-line' }, input, extra, sw);
}
/** 配置元素列表中的小开关：点击直接启用/停用，不跳转 */
function miniSwitch(n) {
  return h('label', {
    class: 'switch sm' + (n.enabled ? ' on' : ''), title: n.enabled ? '已启用，点击停用' : '已停用，点击启用',
    onclick: e => { e.stopPropagation(); toggleEnabled(n); renderContent(); },
  }, h('span', { class: 'tr' }));
}
function crumb(d, n) {
  const anc = ancestors(d, n);
  if (!anc.length) return null;
  const parts = [];
  anc.forEach((a, i) => {
    if (i) parts.push(h('span', null, '/'));
    parts.push(h('a', { onclick: () => select(a.id) }, a.name));
  });
  parts.push(h('span', null, '·'), h('span', null, KIND_LABEL[n.kind] + (n.flavor && FLAVOR_LABEL[n.flavor] ? '（' + FLAVOR_LABEL[n.flavor] + '）' : '')));
  return h('div', { class: 'crumb' }, parts);
}
function subtabs(tabs, cur, onPick, right) {
  return h('div', { class: 'subtabs' },
    tabs.map(([k, label, n]) => h('div', { class: 'subtab' + (k === cur ? ' on' : ''), onclick: () => onPick(k) }, label, n ? h('span', { class: 'n' }, n) : null)),
    h('div', { class: 'grow' }), right);
}

function viewRequest(d, n, c) {
  const { f, from } = effective(d, n);
  const ifaceInput = h('input', { class: 'iface', value: n.props.interface, placeholder: 'com.example.api.FooService', spellcheck: 'false', oninput: e => { queue(d, n, { props: { interface: e.target.value } }); } });
  dottedSelect(ifaceInput);
  const setMethod = v => {
    queue(d, n, { props: { method: v } });
    const m = els.tree.querySelector(`.row[data-id="${n.id}"] .meta`);
    if (m) m.textContent = v;
  };
  const methodInput = h('input', {
    class: 'method', value: n.props.method, placeholder: 'method', spellcheck: 'false',
    oninput: e => setMethod(e.target.value),
  });
  const sendBtn = els.sendBtn = sendButton(d, n);

  const chips = [];
  const chip = (label, value, k, warn) => h('span', { class: 'chip' + (warn ? ' warn' : ''), onclick: () => { S.reqTab = 'conn'; renderContent(); }, title: from[k] ? '继承自 ' + from[k].name : '' },
    label, value ? h('b', null, value) : null, from[k] ? h('span', { class: 'inh' }, '继承') : null);
  const direct = (f.registryProtocol || '').toLowerCase() === 'none';
  if (n.flavor === 'dandelion') {
    const url = f.registryAddress || '';
    const real = resolveVars(d, n, url);
    const label = !url ? '' : (real.includes('zookeeper') || real.includes('${') ? real : '直连 ' + real);
    if (!url) chips.push(chip(I('plug', 'xs'), '未填写 URL', 'registryAddress', true));
    else chips.push(h('span', { class: 'chip' + (real.includes('${') ? ' warn' : ''), title: url, onclick: () => { S.reqTab = 'conn'; renderContent(); } },
      I('plug', 'xs'), real !== url ? h('span', { class: 'inh' }, url + ' =') : null, h('b', null, label)));
  } else if (!f.registryAddress) chips.push(chip(I('plug', 'xs'), '未配置注册中心地址', 'registryAddress', true));
  else chips.push(chip(I('plug', 'xs'), direct ? '直连 ' + f.registryAddress : (f.registryAddress.includes('://') ? '' : (f.registryProtocol || 'zookeeper') + '://') + f.registryAddress, 'registryAddress'));
  chips.push(chip('超时', (f.timeout || '120000') + ' ms', 'timeout'));
  if (f.appidKey) chips.push(chip('签名', f.appidKey.split(',')[0], 'appidKey'));
  if (f.version) chips.push(chip('版本', f.version, 'version'));
  if (f.group) chips.push(chip('分组', f.group, 'group'));
  const scope = scopeOf(d, n);
  const scripts = scope.filter(x => x.kind === 'pre' || x.kind === 'post');
  const csvs = scope.filter(x => x.kind === 'csv');
  if (scripts.length || csvs.length) {
    chips.push(h('span', { class: 'chip', onclick: () => { S.reqTab = 'scope'; renderContent(); } },
      I('braces', 'xs'), [scripts.length ? scripts.length + ' 个脚本' : '', csvs.length ? csvs.length + ' 个 CSV' : ''].filter(Boolean).join(' · ')));
  }

  const head = h('div', { class: 'req' },
    titleLine(d, n),
    crumb(d, n),
    h('div', { class: 'endpoint' }, ifaceInput, endpointSplit(methodInput), h('span', { class: 'hash' }, '#'), methodInput, sendBtn,
      ifaceComplete(d, n, ifaceInput, methodInput), methodComplete(d, n, methodInput, setMethod)),
    h('div', { class: 'chips' }, chips));

  const tab = S.reqTab;
  const fmtBtn = h('button', { class: 'btn ghost sm', title: '格式化 JSON（大数字与 ${变量} 原样保留）', onclick: () => formatParams() }, I('wand', 's'), '格式化');
  const tabs = subtabs([['params', '参数'], ['conn', '连接'], ['scope', '脚本与数据', scope.filter(x => x.kind !== 'defaults').length || null]],
    tab, k => { S.reqTab = k; renderContent(); }, tab === 'params' ? fmtBtn : null);
  const body = h('div', { class: 'req-body' });
  let editor = null;
  if (tab === 'params') {
    let check = null;
    editor = codeEditor({ value: n.props.params || '', lang: 'json', hint: PARAM_HINT, onChange: v => { queue(d, n, { props: { params: v } }); check(); } });
    check = paramsChecker(editor, n.flavor === 'dandelion');
    check(0);
    paramsComplete(d, n, editor);
    body.append(editor);
  } else if (tab === 'conn') {
    body.append(connForm(d, n, from));
  } else {
    body.append(scopeView(d, n));
  }
  function formatParams() {
    const v = editor.ed.get();
    try {
      editor.ed.replaceAll(stringifyLoose(parseLoose(v)));
    } catch (e) {
      toast('不是合法的 JSON，未格式化：' + e.message, true);
    }
  }
  c.append(head, tabs, body, hResizer(), els.resp = h('div', { class: 'resp', style: { height: (localStorage.getItem('dl.respH') || 46) + '%' } }));
  renderResp();
}
/** 已打开文件中出现过的接口名：当前文件在前，其次是其他已打开的文件 */
function knownInterfaces(d, self) {
  const m = new Map();
  const add = (doc, here) => doc.byId.forEach(x => {
    if (x === self || x.kind !== 'dubbo') return;
    const name = (x.props.interface || '').trim();
    if (!name) return;
    let e = m.get(name);
    if (!e) m.set(name, e = { name, count: 0, here: false });
    e.count++;
    if (here) e.here = true;
  });
  add(d, true);
  S.docs.forEach(o => { if (o !== d) add(o, false); });
  return [...m.values()];
}
/** 匹配优先级：类名前缀 > 类名包含 > 包段前缀 > 全名包含 > 类名驼峰首字母；at/len 为全名中的高亮位置 */
function matchIface(name, q) {
  if (!q) return { rank: 0 };
  const base = name.lastIndexOf('.') + 1;
  const simple = name.slice(base).toLowerCase();
  const full = name.toLowerCase();
  let i = simple.indexOf(q);
  if (i >= 0) return { rank: i === 0 ? 0 : 1, at: base + i, len: q.length };
  i = full.indexOf(q);
  if (i >= 0) return { rank: i === 0 || full[i - 1] === '.' ? 2 : 3, at: i, len: q.length };
  if (q.length > 1 && name.slice(base).replace(/[^A-Z]/g, '').toLowerCase().startsWith(q)) return { rank: 4 };
  return null;
}
/** 输入是全类名的前缀时，按下一级包名归并（如 com.zto.me → com.zto.message.），逐级补全 */
function nextPackages(all, q) {
  const m = new Map();
  all.forEach(it => {
    if (!it.name.toLowerCase().startsWith(q)) return;
    const dot = it.name.indexOf('.', q.length);
    if (dot < 0) return;
    const name = it.name.slice(0, dot + 1);
    let e = m.get(name);
    if (!e) m.set(name, e = { name, pkg: true, classes: 0, count: 0, here: false, at: 0, len: q.length });
    e.classes++;
    e.count += it.count;
    if (it.here) e.here = true;
  });
  return [...m.values()].sort((a, b) => b.here - a.here || b.count - a.count || a.name.localeCompare(b.name));
}
/* ---- 注册中心嗅探（只读 zookeeper 上的服务与提供者 URL；前后端各缓存一段时间） */

/** 请求实际使用的注册中心；不能嗅探时返回 { error } */
function registryOf(d, n) {
  const { f } = effective(d, n);
  const address = resolveVars(d, n, (f.registryAddress || '').trim());
  if (n.flavor === 'dandelion') {
    if (!address) return { error: '未填写 URL' };
    if (!address.includes('zookeeper')) return { error: '直连地址，没有注册中心可嗅探' };
    return { protocol: 'zookeeper', address, group: '' };
  }
  const protocol = (f.registryProtocol || 'zookeeper').trim().toLowerCase();
  if (protocol === 'none') return { error: '直连地址，没有注册中心可嗅探' };
  if (protocol !== 'zookeeper') return { error: '只支持嗅探 zookeeper 注册中心' };
  if (!address) return { error: '未配置注册中心地址' };
  if (address.includes('${')) return { error: '注册中心地址中有未定义的变量' };
  return { protocol, address, group: resolveVars(d, n, (f.registryGroup || '').trim()) };
}
const SNIFF = new Map();
/** 读取嗅探结果：同一 key 同时只请求一次，成功结果缓存 ttl，失败 30 秒内不重试；返回 { loading, data, error, promise } */
function sniff(action, body, ttl, refresh) {
  const key = action + JSON.stringify(body);
  let e = SNIFF.get(key);
  if (e && !refresh && (e.loading || Date.now() - e.at < (e.error ? 30000 : ttl))) return e;
  e = { loading: true, at: Date.now() };
  SNIFF.set(key, e);
  e.promise = api(action, Object.assign({ refresh: !!refresh }, body)).then(
    data => Object.assign(e, { loading: false, data, at: Date.now() }),
    err => Object.assign(e, { loading: false, error: err.message, at: Date.now() }));
  return e;
}
const sniffServices = (reg, refresh) => sniff('sniffServices', { protocol: reg.protocol, address: reg.address, group: reg.group }, 120000, refresh);
const sniffProviders = (reg, iface, refresh) => sniff('sniffProviders', { protocol: reg.protocol, address: reg.address, group: reg.group, iface }, 30000, refresh);

/**
 * 候选列表弹窗（输入框下拉与编辑器内补全共用）：show(items, head, foot, active) 显示，
 * key(e) 处理 ↑↓ 选择、↵/Tab 确认、Esc 关闭，已处理时返回 true
 */
function acList({ row, pick }) {
  const box = h('div', { class: 'ac', style: { display: 'none' } });
  const st = { items: [], active: 0, shown: false };
  const hide = () => { box.style.display = 'none'; st.items = []; st.shown = false; };
  const mark = () => {
    box.querySelectorAll('.ac-i').forEach((x, i) => x.classList.toggle('on', i === st.active));
    const on = box.querySelector('.ac-i.on');
    if (on) on.scrollIntoView({ block: 'nearest' });
  };
  const show = (items, head, foot, active) => {
    st.items = items;
    st.active = Math.min(Math.max(0, active || 0), Math.max(0, items.length - 1));
    st.shown = true;
    box.style.display = '';
    box.innerHTML = '';
    if (head) box.append(head);
    items.forEach((it, i) => {
      const r = row(it);
      const el = h('div', { class: 'ac-i' + (r.cls ? ' ' + r.cls : ''), title: r.title || '' }, r.kids);
      el.addEventListener('mousedown', e => { e.preventDefault(); pick(it); });
      el.addEventListener('mousemove', () => { if (st.active !== i) { st.active = i; mark(); } });
      box.append(el);
    });
    if (foot) box.append(foot);
    mark();
  };
  const key = e => {
    if (!st.shown) return false;
    if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && st.items.length) {
      e.preventDefault();
      st.active = (st.active + (e.key === 'ArrowDown' ? 1 : st.items.length - 1)) % st.items.length;
      mark();
      return true;
    }
    if (st.items.length && (e.key === 'Enter' || e.key === 'Tab') && !e.metaKey && !e.ctrlKey && !e.shiftKey) {
      e.preventDefault();
      pick(st.items[st.active]);
      return true;
    }
    if (e.key === 'Escape') {
      e.stopPropagation();
      hide();
      return true;
    }
    return false;
  };
  return { box, st, show, hide, key };
}
/**
 * 输入框的下拉候选。compute(q, typed) 返回 { items, head, foot }（返回空则不显示）；
 * typed 表示聚焦后是否输入过。refresh() 在异步数据到达后重算
 */
function dropdown(input, { compute, row, pick, openOnFocus, initial, minWidth = 360 }) {
  const list = acList({ row, pick });
  const { box, st } = list;
  // want：用户期望下拉打开（异步数据到达前 compute 可能返回空，到达后由 refresh 再打开）
  let typed = false, want = false;
  const open = keep => {
    want = true;
    const r = compute(input.value.trim(), typed);
    if (!r || (!(r.items || []).length && !r.head && !r.foot)) return list.hide();
    const items = r.items || [];
    const active = keep && st.shown && st.items.length ? st.active : initial ? initial(items) : 0;
    const wrap = input.parentElement.clientWidth;
    const width = Math.min(wrap, Math.max(minWidth, input.offsetWidth));
    Object.assign(box.style, { left: Math.max(0, Math.min(input.offsetLeft, wrap - width)) + 'px', width: width + 'px' });
    list.show(items, r.head, r.foot, active);
  };
  input.addEventListener('input', () => { typed = true; open(); });
  input.addEventListener('focus', () => { typed = false; if (openOnFocus()) open(); });
  const close = () => { want = false; list.hide(); };
  input.addEventListener('blur', close);
  input.addEventListener('keydown', e => {
    if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && !st.shown) {
      e.preventDefault();
      open();
    } else {
      if (e.key === 'Escape') want = false;
      list.key(e);
    }
  });
  return { box, open, close, refresh: () => { if (want && document.activeElement === input) open(true); } };
}
/** 下拉框顶部/底部的说明行；action 为 [文字, 回调] 时右侧带一个按钮（如“刷新”） */
function acNote(text, cls, action) {
  const el = h('div', { class: 'ac-note' + (cls ? ' ' + cls : '') }, h('span', null, text));
  if (action) {
    const b = h('span', { class: 'ac-act' }, action[0]);
    b.addEventListener('mousedown', e => { e.preventDefault(); action[1](); });
    el.append(b);
  }
  return el;
}
/** 嗅探请求返回后刷新下拉框（每个请求只挂一次） */
function whenLoaded(st, dd, hooked) {
  if (st.loading && !hooked.has(st.promise)) {
    hooked.add(st.promise);
    st.promise.then(() => dd.refresh());
  }
}
/** 接口名候选：下一级包名在前，其后是已打开文件中用过的接口，再后是注册中心上的其他接口 */
function ifaceComplete(d, n, input, next) {
  const hooked = new Set();
  let refresh = false;
  const dd = dropdown(input, {
    minWidth: 520,
    openOnFocus: () => !input.value.trim(),
    compute: q0 => {
      const q = q0.toLowerCase();
      const known = knownInterfaces(d, n);
      const reg = registryOf(d, n);
      const st = reg.error ? null : sniffServices(reg, refresh);
      refresh = false;
      if (st) whenLoaded(st, dd, hooked);
      const matched = [];
      known.forEach(it => { const m = matchIface(it.name, q); if (m) matched.push(Object.assign(it, m)); });
      if (st && st.data) {
        const seen = new Set(known.map(x => x.name));
        for (const name of st.data.services) {
          if (seen.has(name)) continue;
          const m = matchIface(name, q);
          if (m) matched.push(Object.assign({ name, count: 0, here: false, reg: true }, m));
        }
      }
      const exact = matched.filter(it => it.name.toLowerCase() !== q);
      const items = nextPackages(exact, q).concat(exact
        .sort((a, b) => a.rank - b.rank || b.here - a.here || b.count - a.count || (a.reg ? 1 : 0) - (b.reg ? 1 : 0) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)))
        .slice(0, 50);
      const foot = st && st.error ? acNote('注册中心：' + st.error, 'warn', ['重试', () => { refresh = true; dd.open(true); }]) : null;
      return { items, foot };
    },
    row: it => ({
      cls: it.pkg ? 'pkg' : '',
      title: it.name,
      kids: (() => {
        const base = it.name.lastIndexOf('.', it.pkg ? it.name.length - 2 : it.name.length) + 1;
        return [it.pkg ? I('folder', 'xs') : null,
          h('span', { class: 'ac-n' },
            h('span', { class: 'pkg' }, acMark(it.name.slice(0, base), 0, it.at, it.len)),
            h('b', null, acMark(it.name.slice(base), base, it.at, it.len))),
          h('span', { class: 'ac-c' }, it.pkg ? it.classes + ' 个接口' : it.here ? it.count + ' 处' : it.reg ? '注册中心' : '其他文件')];
      })(),
    }),
    pick: it => {
      input.value = it.name;
      queue(d, n, { props: { interface: it.name } });
      if (it.pkg) return dd.open();
      dd.close();
      next.focus();
      next.select();
    },
  });
  return dd.box;
}
/** 方法名下拉：列出注册中心上该接口提供者的方法；版本/分组为空且提供者一致时，选择方法后顺带填上 */
function methodComplete(d, n, input, onPick) {
  const hooked = new Set();
  let refresh = false;
  let ctx = null;
  const dd = dropdown(input, {
    minWidth: 420,
    openOnFocus: () => true,
    initial: items => items.findIndex(it => it.name === input.value),
    compute: (q0, typed) => {
      ctx = null;
      const iface = resolveVars(d, n, (n.props.interface || '').trim());
      const reg = registryOf(d, n);
      if (!iface || reg.error) return null;
      const st = sniffProviders(reg, iface, refresh);
      refresh = false;
      whenLoaded(st, dd, hooked);
      const again = () => { refresh = true; dd.open(true); };
      if (st.loading) return null;
      if (st.error) return { head: acNote('注册中心：' + st.error, 'warn', ['重试', again]) };
      const all = st.data.providers;
      if (!all.length) return { head: acNote('注册中心上没有该接口的提供者', 'warn', ['刷新', again]) };
      const { f } = effective(d, n);
      const ver = resolveVars(d, n, (f.version || '').trim());
      const grp = resolveVars(d, n, (f.group || '').trim());
      const match = all.filter(p => p.version === ver && p.group === grp);
      const use = match.length ? match : all;
      ctx = { all, match, ver, grp };
      const uniq = a => [...new Set(a)];
      const vg = p => '版本 ' + (p.version || '（空）') + (p.group ? ' · 分组 ' + p.group : '');
      const head = match.length ? null : acNote('没有' + vg({ version: ver, group: grp }) + ' 的提供者，下面是全部提供者的方法', 'warn');
      const q = typed ? q0.toLowerCase() : '';
      const methods = uniq(use.flatMap(p => p.methods)).sort();
      const items = methods.filter(m => m.toLowerCase().includes(q))
        .sort((a, b) => (b.toLowerCase().startsWith(q) ? 1 : 0) - (a.toLowerCase().startsWith(q) ? 1 : 0))
        .map(name => ({ name, at: q ? name.toLowerCase().indexOf(q) : null, len: q.length }));
      return { head, items };
    },
    row: it => ({
      cls: it.name === input.value ? 'cur' : '',
      kids: [h('span', { class: 'ac-n' }, h('b', null, acMark(it.name, 0, it.at, it.len))),
        it.name === input.value ? h('span', { class: 'ac-c' }, '当前') : null],
    }),
    pick: it => {
      input.value = it.name;
      dd.close();
      onPick(it.name);
      const fill = {};
      if (ctx && !ctx.match.length) {
        const vs = [...new Set(ctx.all.map(p => p.version))], gs = [...new Set(ctx.all.map(p => p.group))];
        if (!ctx.ver && vs.length === 1 && vs[0]) fill.version = vs[0];
        if (!ctx.grp && gs.length === 1 && gs[0]) fill.group = gs[0];
      }
      if (Object.keys(fill).length) {
        queue(d, n, { props: fill });
        toast('已按注册中心的提供者填写' + (fill.version ? '版本 ' + fill.version : '') + (fill.group ? ' 分组 ' + fill.group : ''));
        renderContent();
      }
    },
  });
  return dd.box;
}
/* ---- 参数编辑器内补全：参数类名 + 属性名（来源于已打开文件中出现过的参数） */

/**
 * 宽松扫描参数文本 [{类型: 值}, ...]：容忍 YAML 标签、${变量}、未加引号的键和语法错误。
 * 容器链 stack 每层 { ch: '{' | '[', seg: 在父容器中的键（数组元素为 '[]'）, keys: 已出现的键 }。
 * onKey(stack, key, kind) 在每读完一个键时回调，kind 为值的首字符；
 * 给定 stop 时扫描到该位置，返回 { stack, mode, start }：mode 为 'quoted' / 'bare' 表示光标正在输入一个键，start 为键的起始位置
 */
function scanParams(s, stop, onKey) {
  const end = stop == null ? s.length : stop;
  const stack = [];
  const top = () => stack[stack.length - 1];
  const keyPos = () => { const t = top(); return !!t && t.ch === '{' && t.expectKey; };
  const keyDone = (key, j) => {
    const t = top();
    t.pending = key;
    t.expectKey = false;
    t.keys.push(key);
    if (onKey) { const m = /^\s*:\s*(\S)/.exec(s.slice(j, j + 100)); onKey(stack, key, m ? m[1] : ''); }
  };
  let i = 0;
  while (i < end) {
    const c = s[i];
    if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < s.length && s[j] !== c && s[j] !== '\n') j += s[j] === '\\' ? 2 : 1;
      if (j >= end) return { stack, mode: keyPos() ? 'quoted' : '', start: i };
      if (keyPos()) keyDone(s.slice(i + 1, j), j + 1);
      i = j + 1;
    } else if (c === '{' || c === '[') {
      const p = top();
      stack.push({ ch: c, seg: p ? (p.ch === '[' ? '[]' : p.pending) : null, keys: [], expectKey: c === '{', pending: null });
      i++;
    } else if (c === '}' || c === ']') {
      stack.pop();
      i++;
    } else if (c === ',') {
      const t = top();
      if (t && t.ch === '{') { t.expectKey = true; t.pending = null; }
      i++;
    } else if (c === ':' || c === ' ' || c === '\t' || c === '\n' || c === '\r') {
      i++;
    } else if (c === '$' && s[i + 1] === '{') {
      let depth = 0, j = i + 1;
      for (; j < s.length && s[j] !== '\n'; j++) {
        if (s[j] === '{') depth++;
        else if (s[j] === '}' && --depth === 0) break;
      }
      if (j >= end) return { stack, mode: '', start: i };
      i = j + 1;
    } else if (c === '#' && (i === 0 || /\s/.test(s[i - 1]))) {
      const j = s.indexOf('\n', i);
      i = j < 0 ? s.length : j;
    } else {
      let j = i;
      while (j < s.length && !/[\s,:{}[\]"]/.test(s[j])) j++;
      if (j >= end) return { stack, mode: keyPos() ? 'bare' : '', start: i };
      if (keyPos()) keyDone(s.slice(i, j), j);
      i = j;
    }
  }
  return stop == null ? null : { stack, mode: '', start: end };
}
/** 光标所在的补全位置：参数类型（[{ 这一层的键）或参数对象中的属性（type 为参数类型，path 为属性路径） */
function paramsPlace(stack) {
  if (stack.length === 2 && stack[0].ch === '[') return { kind: 'type' };
  if (stack.length >= 3 && stack[0].ch === '[' && stack[2].seg) return { kind: 'prop', type: stack[2].seg, path: stack.slice(3).map(c => c.seg).join('.') };
  return null;
}
const JAVA_COMMON = [
  ['java.lang.String', '"'], ['java.lang.Long', '0'], ['java.lang.Integer', '0'], ['java.lang.Boolean', 'f'], ['java.lang.Double', '0'],
  ['java.math.BigDecimal', '0'], ['java.util.List', '['], ['java.util.Map', '{'], ['java.util.Date', '"'], ['java.time.LocalDateTime', '"'],
  ['long', '0'], ['int', '0'], ['boolean', 'f'], ['double', '0'],
];
/** 已打开文件（不含当前请求）中出现过的参数类型与属性名 */
function paramsCorpus(d, self) {
  const iface = (self.props.interface || '').trim();
  const method = (self.props.method || '').trim();
  const root = iface.split('.').slice(0, 3).join('.') + '.';
  const types = new Map(), exact = new Map(), bySeg = new Map(), all = new Map(), kinds = new Map();
  const bump = (m, k, key) => {
    let x = m.get(k);
    if (!x) m.set(k, x = new Map());
    x.set(key, (x.get(key) || 0) + 1);
  };
  S.docs.forEach(doc => doc.byId.forEach(x => {
    if (x === self || x.kind !== 'dubbo' || !x.props.params) return;
    const sameIface = (x.props.interface || '').trim() === iface;
    const group = sameIface && (x.props.method || '').trim() === method ? 0 : sameIface ? 1 : 2;
    scanParams(x.props.params, null, (stack, key, kind) => {
      const pl = paramsPlace(stack);
      if (!pl) return;
      if (pl.kind === 'type') {
        let t = types.get(key);
        if (!t) types.set(key, t = { name: key, kind, count: 0, group: 9 });
        t.count++;
        t.group = Math.min(t.group, group === 2 && key.startsWith(root) ? 2 : group === 2 ? 3 : group);
      } else {
        bump(exact, pl.type + '|' + pl.path, key);
        bump(bySeg, pl.path ? pl.path.split('.').pop() : '|' + pl.type, key);
        all.set(key, (all.get(key) || 0) + 1);
        if (!kinds.has(key)) kinds.set(key, kind);
      }
    });
  }));
  JAVA_COMMON.forEach(([name, kind]) => { if (!types.has(name)) types.set(name, { name, kind, count: 0, group: 4 }); });
  return { types, exact, bySeg, all, kinds };
}
/** 属性名匹配：前缀 > 包含 > 驼峰首字母 */
function matchProp(name, q) {
  if (!q) return { rank: 0 };
  const l = name.toLowerCase();
  const i = l.indexOf(q);
  if (i >= 0) return { rank: i === 0 ? 0 : 1, at: i, len: q.length };
  if (q.length > 1 && (name[0] + name.slice(1).replace(/[^A-Z]/g, '')).toLowerCase().startsWith(q)) return { rank: 2 };
  return null;
}
/** 值的占位：对象 {}、列表 []、字符串 ""，光标放在里面；数字、布尔等留空 */
const valueStub = kind => (kind === '{' || kind === '[' ? kind + (kind === '{' ? '}' : ']') : kind === '"' ? '""' : '');
/** 参数编辑器内的补全：输入参数类型的键时补全类名（逐级包名），输入参数对象中的键时补全属性名 */
function paramsComplete(d, n, editor) {
  const ta = editor.ed.ta;
  let corpus = null, place = null, ctx = null;
  const list = acList({
    row: it => {
      if (place.kind === 'prop') return { kids: [h('span', { class: 'ac-n' }, h('b', null, acMark(it.name, 0, it.at, it.len)))] };
      const base = it.name.lastIndexOf('.', it.pkg ? it.name.length - 2 : it.name.length) + 1;
      return {
        cls: it.pkg ? 'pkg' : '',
        title: it.name,
        kids: [it.pkg ? I('folder', 'xs') : null,
          h('span', { class: 'ac-n' },
            h('span', { class: 'pkg' }, acMark(it.name.slice(0, base), 0, it.at, it.len)),
            h('b', null, acMark(it.name.slice(base), base, it.at, it.len))),
          it.group === 0 ? h('span', { class: 'ac-c' }, '本方法') : it.group === 1 ? h('span', { class: 'ac-c' }, '本接口') : null],
      };
    },
    pick: it => {
      const v = ta.value;
      const caret = ta.selectionStart;
      const a = ctx.start;
      if (it.pkg) {
        ta.setSelectionRange(a, caret);
        insertText(ta, '"' + it.name);
        return;
      }
      const rest = v.slice(caret);
      const m = ctx.mode === 'quoted' ? /^[^"\n]*"/.exec(rest) : /^[^\s,:{}[\]"]*/.exec(rest);
      const b = caret + (m ? m[0].length : 0);
      const hasColon = /^\s*:/.test(v.slice(b));
      const stub = hasColon ? '' : valueStub(it.kind || (place.kind === 'type' ? '{' : ''));
      const text = '"' + it.name + '"' + (hasColon ? '' : ': ' + stub);
      list.hide();
      ta.setSelectionRange(a, b);
      insertText(ta, text);
      const pos = a + text.length - (stub ? 1 : 0);
      ta.setSelectionRange(pos, pos);
    },
  });
  editor.append(list.box);
  const update = () => {
    const caret = ta.selectionStart;
    if (ta.selectionEnd !== caret) return list.hide();
    ctx = scanParams(ta.value, caret);
    place = ctx && ctx.mode ? paramsPlace(ctx.stack) : null;
    if (!place) return list.hide();
    if (!corpus) corpus = paramsCorpus(d, n);
    const q = ta.value.slice(ctx.mode === 'quoted' ? ctx.start + 1 : ctx.start, caret).toLowerCase();
    let items;
    if (place.kind === 'type') {
      const cands = [];
      corpus.types.forEach(t => { const m = matchIface(t.name, q); if (m && t.name.toLowerCase() !== q) cands.push(Object.assign({}, t, m)); });
      cands.sort((x, y) => x.rank - y.rank || x.group - y.group || y.count - x.count || (x.name < y.name ? -1 : 1));
      const pkgs = q ? nextPackages(cands.map(c => Object.assign({ here: c.group < 4 }, c)), q) : [];
      items = pkgs.concat(cands).slice(0, 40);
    } else {
      const have = new Set(ctx.stack[ctx.stack.length - 1].keys);
      const seg = place.path ? place.path.split('.').pop() : '|' + place.type;
      const merged = new Map();
      [corpus.exact.get(place.type + '|' + place.path), corpus.bySeg.get(seg), corpus.all].forEach((m, group) => {
        if (m) m.forEach((count, name) => { if (!merged.has(name)) merged.set(name, { name, group, count }); });
      });
      items = [];
      merged.forEach(it => {
        if (have.has(it.name) || it.name.toLowerCase() === q) return;
        const m = matchProp(it.name, q);
        if (m) items.push(Object.assign(it, m, { kind: corpus.kinds.get(it.name) }));
      });
      items = items.sort((x, y) => x.rank - y.rank || x.group - y.group || y.count - x.count || (x.name < y.name ? -1 : 1)).slice(0, 30);
    }
    if (!items.length) return list.hide();
    const width = place.kind === 'type' ? 520 : 280;
    const xy = editor.ed.caretXY(ctx.start);
    const maxLeft = Math.max(0, editor.clientWidth - width - 8);
    Object.assign(list.box.style, { left: Math.min(Math.max(0, xy.x), maxLeft) + 'px', top: xy.y + 2 + 'px', width: Math.min(width, editor.clientWidth - 8) + 'px' });
    list.show(items);
  };
  ta.addEventListener('input', e => {
    if (e.inputType === 'insertText' || (list.st.shown && e.inputType && e.inputType.startsWith('delete'))) update();
    else list.hide();
  });
  editor.ed.onKey(e => {
    if (e.key === ' ' && e.ctrlKey) { e.preventDefault(); update(); return true; }
    return list.key(e);
  });
  ta.addEventListener('blur', () => { list.hide(); corpus = null; });
  ta.addEventListener('mousedown', () => list.hide());
  ta.addEventListener('scroll', () => list.hide());
}
const acMark = (text, from, at, len) => {
  if (at == null) return text;
  const a = Math.max(0, at - from), b = Math.min(text.length, at + len - from);
  return a >= b ? text : [text.slice(0, a), h('mark', null, text.slice(a, b)), text.slice(b)];
};
/** 接口名 / 方法名之间的分隔线：拖动调整方法名宽度（占整栏的百分比），双击恢复默认 */
function endpointSplit(methodInput) {
  const saved = +localStorage.getItem('dl.methodW');
  if (saved) Object.assign(methodInput.style, { width: saved + '%', maxWidth: 'none' });
  const r = h('div', { class: 'sep', title: '拖动调整宽度，双击恢复默认' });
  r.addEventListener('dblclick', () => {
    localStorage.removeItem('dl.methodW');
    Object.assign(methodInput.style, { width: '', maxWidth: '' });
  });
  r.addEventListener('mousedown', e => {
    e.preventDefault();
    const box = r.parentElement.getBoundingClientRect();
    const right = methodInput.getBoundingClientRect().right;
    const offset = right - e.clientX - methodInput.getBoundingClientRect().width;
    const move = ev => {
      const pct = Math.max(10, Math.min(75, ((right - ev.clientX - offset) / box.width) * 100));
      Object.assign(methodInput.style, { width: pct + '%', maxWidth: 'none' });
      localStorage.setItem('dl.methodW', pct.toFixed(1));
    };
    const up = () => { removeEventListener('mousemove', move); removeEventListener('mouseup', up); document.body.style.cursor = ''; r.classList.remove('on'); };
    addEventListener('mousemove', move);
    addEventListener('mouseup', up);
    document.body.style.cursor = 'col-resize';
    r.classList.add('on');
  });
  return r;
}
function hResizer() {
  const r = h('div', { class: 'hresizer' });
  r.addEventListener('mousedown', e => {
    e.preventDefault();
    const box = els.content.getBoundingClientRect();
    const move = ev => {
      const pct = Math.max(15, Math.min(80, ((box.bottom - ev.clientY) / box.height) * 100));
      els.resp.style.height = pct + '%';
      localStorage.setItem('dl.respH', pct.toFixed(1));
    };
    const up = () => { removeEventListener('mousemove', move); removeEventListener('mouseup', up); document.body.style.cursor = ''; };
    addEventListener('mousemove', move);
    addEventListener('mouseup', up);
    document.body.style.cursor = 'row-resize';
  });
  return r;
}
const DANDELION_FIELDS = [
  ['registryAddress', 'URL（zookeeper://… 注册中心，或 IP:端口 直连）', 'zookeeper://10.0.0.1:2181?backup=10.0.0.2:2181'],
  ['timeout', '超时 (ms)', '3000'],
  ['version', '服务版本', ''],
  ['group', '服务分组', ''],
  ['appidKey', 'appidKey（签名：appid,key；或 JSON 形式的附件）', ''],
];
function dandelionForm(d, n) {
  const sgr = 'orJar' in n.props;
  const grid = h('div', { class: 'grid' });
  for (const [k, label, ph] of DANDELION_FIELDS) {
    const fixed = k === 'timeout' && !sgr;
    const real = k === 'registryAddress' ? resolveVars(d, n, n.props[k] || '') : null;
    grid.append(h('div', { class: 'field' + (k === 'registryAddress' || k === 'appidKey' ? ' wide' : '') },
      h('label', null, label, fixed ? h('span', { class: 'from' }, 'DubboApp 固定 3000') : null,
        real && real !== n.props[k] ? h('span', { class: 'from', title: real }, '= ' + real) : null),
      h('input', { class: 'input', value: n.props[k] || '', placeholder: ph, disabled: fixed, spellcheck: 'false', oninput: e => queue(d, n, { props: { [k]: e.target.value } }) })));
  }
  return h('div', { class: 'form' },
    h('div', { class: 'note', style: { marginBottom: '16px' } },
      '该请求来自 JMeter 的 JavaSampler（', h('code', null, sgr ? 'mm.jmeter.duser.SgrDubbo' : 'mm.jmeter.duser.DubboApp'),
      '），参数按位置保存在 JavaSampler 的参数表中，这里修改后会写回对应位置。它不读取“Dubbo 默认配置”；URL 常用 ', h('code', null, '${url}'), ' 引用测试计划中的变量。'),
    grid);
}
function connForm(d, n, from) {
  if (n.flavor === 'dandelion') return dandelionForm(d, n);
  const isSampler = n.kind === 'dubbo';
  const grid = h('div', { class: 'grid' });
  for (const [k, label, ph, opts] of CONN) {
    const inh = from && from[k];
    const placeholder = inh ? inh.props[k] : (isSampler ? (ph ? '默认 ' + ph : '') : ph);
    const onPick = v => queue(d, n, { props: { [k]: v } });
    // 协议、集群容错、负载均衡等枚举字段用下拉框选择；留空表示继承 / 使用默认值
    const ctl = opts
      ? selectInput({ value: n.props[k] || '', opts, onPick, empty: inh ? '继承：' + inh.props[k] + '（' + inh.name + '）' : isSampler ? '默认（' + ph + '）' : '未设置（默认 ' + ph + '）' })
      : h('input', { class: 'input', value: n.props[k] || '', placeholder, spellcheck: 'false', oninput: e => onPick(e.target.value) });
    grid.append(h('div', { class: 'field' + (k === 'registryAddress' ? ' wide' : '') },
      h('label', null, label, inh ? h('span', { class: 'from', title: inh.name }, '继承自 ' + inh.name) : null), ctl));
  }
  const note = isSampler
    ? h('div', { class: 'note', style: { marginBottom: '16px' } }, '留空的字段使用所在分组（或测试计划）中最近的“Dubbo 默认配置”，与 JMeter 的配置元件规则一致。注册中心协议填 ', h('code', null, 'none'), ' 表示直连，地址填 ', h('code', null, 'IP:端口'), '。')
    : null;
  return h('div', { class: 'form' }, note, grid);
}
function scopeView(d, n) {
  const all = scopeOf(d, n, true).filter(x => x.kind !== 'defaults');
  const wrap = h('div', { class: 'form' });
  wrap.append(h('div', { class: 'note', style: { marginBottom: '14px' } },
    '发送时按 JMeter 规则依次执行：用户变量 → CSV 取下一行 → 前置脚本 → 替换 ${变量}/函数 → 调用 → 后置脚本。脚本中可用 ',
    h('code', null, 'vars'), ' ', h('code', null, 'props'), ' ', h('code', null, 'log'), ' ', h('code', null, 'prev'), ' ', h('code', null, 'sampler'), ' ', h('code', null, 'ctx'),
    '，也可以用 ', h('code', null, 'RpcContext.getContext().setAttachment(...)'), ' 设置附件。'));
  const list = h('div', { class: 'list' });
  if (!all.length) list.append(h('div', { class: 'help' }, '此请求没有生效的脚本、CSV 或变量。'));
  for (const x of all) {
    const owner = parentOf(d, x);
    list.append(h('div', { class: 'item' + (x.enabled ? '' : ' off'), onclick: () => select(x.id) },
      badge(x.kind), h('div', { class: 'grow' }, x.name), h('span', { class: 'sub' }, owner === n ? '仅此请求' : '来自 ' + owner.name + (x.enabled ? '' : ' · 已停用')),
      miniSwitch(x)));
  }
  wrap.append(list,
    h('div', { style: { display: 'flex', gap: '8px', marginTop: '14px' } },
      h('button', { class: 'btn sm', onclick: () => addNode(n, 'pre') }, I('plus', 'xs'), '为此请求添加前置脚本'),
      h('button', { class: 'btn sm', onclick: () => addNode(n, 'post') }, I('plus', 'xs'), '添加后置脚本')));
  return wrap;
}

/* ------------------------------------------------------------------ response panel */

let tickTimer = null;
function fmtMs(ms) {
  return ms < 1000 ? Math.round(ms) + ' ms' : (ms / 1000).toFixed(ms < 10000 ? 2 : 1) + ' s';
}
function fmtSize(b) {
  return b < 1024 ? b + ' B' : b < 1048576 ? (b / 1024).toFixed(1) + ' KB' : (b / 1048576).toFixed(2) + ' MB';
}
function renderResp() {
  const d = doc();
  const box = els.resp;
  if (!box || !d) return;
  const n = d.byId.get(S.sel[d.id]);
  if (!n || !SENDABLE.has(n.kind)) return;
  clearInterval(tickTimer);
  const key = resKey(d, n);
  const started = S.sending.get(key);
  const r = S.results.get(key);
  box.innerHTML = '';
  if (els.sendBtn && els.sendBtn.isConnected) {
    const b = sendButton(d, n);
    els.sendBtn.replaceWith(b);
    els.sendBtn = b;
  }
  const head = h('div', { class: 'resp-head' }, h('span', { class: 'ttl' }, '响应'));
  if (started != null) {
    const t = h('span', { class: 'stat' }, '0 ms');
    head.append(h('span', { class: 'stat' }, h('span', { class: 'spinner' }), '请求中'), t);
    tickTimer = setInterval(() => { t.textContent = fmtMs(performance.now() - started); }, 97);
  } else if (r) {
    if (r.cancelled) head.append(h('span', { class: 'stat warn' }, h('span', { class: 'sdot cancel' }), '已中断'));
    else head.append(h('span', { class: 'stat ' + (r.ok ? 'ok' : 'err') }, h('span', { class: 'sdot ' + (r.ok ? 'ok' : 'err') }), r.ok ? '成功' : '失败'));
    if (r.elapsed != null && (r.ok || r.elapsed > 0)) head.append(h('span', { class: 'stat', title: r.cancelled ? '中断前已等待' : '调用耗时（不含建立连接）' }, I('send', 'xs'), fmtMs(r.elapsed)));
    if (r.connectMs) head.append(h('span', { class: 'stat', title: '首次调用：连接注册中心并订阅服务的耗时' }, I('plug', 'xs'), '连接 ' + fmtMs(r.connectMs)));
    if (r.ok) head.append(h('span', { class: 'stat' }, fmtSize(r.size || 0)));
    head.append(h('span', { class: 'stat' }, new Date(r.time).toLocaleTimeString()));
  }
  head.append(h('div', { class: 'grow' }));
  if (r && r.ok && S.respTab === 'body') {
    head.append(
      h('button', { class: 'btn ghost sm' + (S.respView === 'tree' ? '' : ''), title: '切换 树形 / 原文', onclick: () => { S.respView = S.respView === 'tree' ? 'raw' : 'tree'; localStorage.setItem('dl.respView', S.respView); renderResp(); } },
        I(S.respView === 'tree' ? 'text' : 'braces', 's'), S.respView === 'tree' ? '原文' : '树形'),
      S.respView === 'tree' ? h('button', { class: 'btn ghost sm', title: '隐藏 Dubbo 泛化调用结果中的 "class" 字段', onclick: () => { S.hideClass = !S.hideClass; localStorage.setItem('dl.hideClass', S.hideClass ? '1' : ''); renderResp(); } },
        I('eye', 's'), S.hideClass ? '显示 class' : '隐藏 class') : null,
      h('button', { class: 'btn ghost sm', onclick: () => copy(r.response) }, I('copy', 's'), '复制'));
  }
  box.append(head);
  const logs = r && r.logs ? r.logs : [];
  const errs = logs.filter(l => l.level === 'ERROR' || l.level === 'WARN').length;
  const vars = r && r.vars ? Object.keys(r.vars).length : null;
  box.append(subtabs([['body', '响应'], ['request', '实际请求'], ['logs', errs ? '日志 ⚠' : '日志', logs.length || null], ['vars', '变量', vars || null]],
    S.respTab, k => { S.respTab = k; renderResp(); }));
  const body = h('div', { class: 'resp-body scroll' });
  box.append(body);
  if (!r) {
    body.append(h('div', { class: 'placeholder' }, h('div', null,
      h('div', { class: 'big' }, started != null ? '正在调用…' : '还没有发送过'),
      started != null ? '首次调用需要连接注册中心，可能稍慢；按 Esc 或点击“中断”可停止' : h('div', null, '按 ', h('span', { class: 'k' }, MOD + '↵'), ' 或点击“发送”'))));
    if (S.respTab === 'vars') showVars(body, null);
    return;
  }
  if (S.respTab === 'body') {
    if (!r.ok) body.append(errorCard(r.error));
    else if (S.respView === 'raw') body.append(codeEditor({ value: r.response, lang: 'json', readOnly: true }));
    else {
      let tree;
      try {
        tree = jsonViewer(parseLoose(r.response), S.hideClass);
      } catch (e) {
        tree = codeEditor({ value: r.response, lang: 'json', readOnly: true });
      }
      body.append(tree);
    }
  } else if (S.respTab === 'request') {
    body.append(requestView(r.request));
  } else if (S.respTab === 'logs') {
    body.append(logs.length ? h('div', { class: 'logs' }, logs.map(l => h('div', { class: 'log' },
      h('span', { class: 'lv ' + l.level }, l.level), l.source ? h('span', { class: 'src', title: l.source }, l.source) : null, h('span', { class: 'msg' }, l.message))))
      : h('div', { class: 'placeholder' }, '本次发送没有日志输出（脚本中的 log.info(...) 会显示在这里）'));
  } else {
    showVars(body, r.vars);
  }
}
async function showVars(body, vars) {
  const d = doc();
  if (!vars) {
    try {
      vars = (await api('vars', { docId: d.id })).vars;
    } catch (e) {
      vars = {};
    }
  }
  body.innerHTML = '';
  const keys = Object.keys(vars);
  body.append(h('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 20px 6px' } },
    h('span', { class: 'help' }, '会话变量在多次发送之间保留（例如后置脚本提取的 token），关闭文件后清空'),
    h('button', { class: 'btn sm', onclick: clearVars }, I('eraser', 'xs'), '清空')));
  if (!keys.length) body.append(h('div', { class: 'help', style: { padding: '8px 20px' } }, '暂无变量'));
  else body.append(h('table', { class: 'kvt' }, keys.map(k => h('tr', null, h('td', null, k), h('td', { class: 'm' }, vars[k])))));
}
function errorCard(e) {
  e = e || { kind: '失败', message: '' };
  return h('div', { class: 'err-card' },
    h('div', { class: 'k' }, I('info', 's'), e.kind, e.provider ? h('span', { class: 'prov' }, '提供者 ' + e.provider) : null),
    h('pre', null, e.message),
    e.detail && e.detail !== e.message ? h('details', null, h('summary', null, '完整信息'), h('pre', { class: 'more' }, e.detail)) : null,
    e.stack ? h('details', null, h('summary', null, '本地堆栈'), h('pre', { class: 'more' }, e.stack)) : null);
}
function requestView(q) {
  if (!q) return h('div', { class: 'placeholder' }, '没有请求信息');
  if (q.type === 'zms') {
    const row = (k, v) => h('tr', null, h('td', null, k), h('td', { class: 'm' }, v === '' || v == null ? '—' : v));
    return h('div', null,
      h('table', { class: 'kvt' }, row('ZK 地址', q.zk), row('Topic', q.topic), row('Tag', q.tag), row('Key', q.key), row('发送方式', q.mode)),
      h('div', { class: 'section-title', style: { margin: '16px 20px 0' } }, '消息体（变量已替换）'),
      h('pre', { class: 'jv', style: { whiteSpace: 'pre-wrap', wordBreak: 'break-all' } }, q.body || ''));
  }
  const o = q.options || {};
  const inh = q.inherited || {};
  const row = (label, v, k) => h('tr', null, h('td', null, label), h('td', { class: 'm' }, v === '' || v == null ? '—' : String(v), inh[k] ? h('span', { class: 'inh' }, '继承自 ' + inh[k]) : null));
  const t = h('table', { class: 'kvt' },
    row('注册中心', q.registry, 'registryAddress'),
    row('接口', q.interface),
    row('方法', q.method),
    row('版本', o.version, 'version'),
    row('服务分组', o.group, 'group'),
    row('超时', o.timeout != null ? o.timeout + ' ms' : '', 'timeout'),
    row('重试 / 集群 / 负载', [o.retries, o.cluster || '默认', o.loadbalance || '默认'].join(' / ')),
    row('参数类型', q.parameterTypes ? q.parameterTypes.join(', ') : '(参数未能解析)'),
    row('附件 (attachments)', q.attachments && Object.keys(q.attachments).length ? JSON.stringify(q.attachments) : ''));
  const wrap = h('div', null, t);
  if (q.parameterValues) {
    wrap.append(h('div', { class: 'section-title', style: { margin: '16px 20px 0' } }, '参数值（变量已替换）'));
    try {
      wrap.append(jsonViewer(parseLoose(JSON.stringify(q.parameterValues)), false));
    } catch (e) {
      wrap.append(h('pre', { class: 'jv' }, JSON.stringify(q.parameterValues, null, 2)));
    }
  } else if (q.params != null) {
    wrap.append(h('div', { class: 'section-title', style: { margin: '16px 20px 0' } }, '参数文本（变量已替换）'), h('pre', { class: 'jv' }, q.params));
  }
  return wrap;
}
async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    const ta = h('textarea', { style: { position: 'fixed', opacity: '0' } }, text);
    document.body.append(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
  }
  toast('已复制到剪贴板');
}

/* ------------------------------------------------------------------ other editors */

function viewDefaults(d, n, c) {
  const owner = parentOf(d, n);
  const count = samplersIn(owner).length;
  c.append(h('div', { class: 'req' }, titleLine(d, n), crumb(d, n)),
    h('div', { class: 'form', style: { paddingTop: '4px' } },
      h('div', { class: 'note', style: { marginBottom: '18px' } },
        '作用于“', owner.name, '”下的 ', h('b', null, count), ' 个请求：请求中留空的连接字段会使用这里的值（离请求最近的默认配置优先）。'),
      connForm(d, n, null).lastChild));
}
function viewScript(d, n, c) {
  const lang = n.props.language || 'beanshell';
  const owner = parentOf(d, n);
  const label = n.kind === 'pre' ? '前置' : '后置';
  const target = SENDABLE.has(owner.kind) ? '仅在发送“' + owner.name + '”时执行' : '在“' + owner.name + '”下每个请求' + (n.kind === 'pre' ? '发送前' : '返回后') + '执行';
  const langTag = h('span', { class: 'badge ' + n.kind }, lang === 'beanshell' ? 'BeanShell' : 'JSR223 · ' + lang);
  c.append(h('div', { class: 'req' }, titleLine(d, n, langTag), crumb(d, n),
    h('div', { class: 'help', style: { marginBottom: '10px' } }, label + '脚本 · ' + target + ' · 可用 vars / props / log / prev / sampler / ctx')));
  const ed = codeEditor({ value: n.props.script || '', lang: 'java', onChange: v => queue(d, n, { props: { script: v } }) });
  const adv = h('div', { class: 'form', style: { flex: 'none', borderTop: '1px solid var(--line)', padding: '12px 20px' } },
    h('div', { class: 'grid' },
      h('div', { class: 'field' }, h('label', null, '参数 (Parameters / bsh.args)'), h('input', { class: 'input', value: n.props.parameters, oninput: e => queue(d, n, { props: { parameters: e.target.value } }) })),
      h('div', { class: 'field' }, h('label', null, '脚本文件（填写后代替上方脚本）'), h('input', { class: 'input', value: n.props.filename, placeholder: '相对路径相对于 jmx 所在目录', oninput: e => queue(d, n, { props: { filename: e.target.value } }) }))));
  c.append(h('div', { class: 'req-body', style: { borderTop: '1px solid var(--line)' } }, ed), adv);
}
function viewCsv(d, n, c) {
  const p = n.props;
  const field = (k, label, ph, wide) => h('div', { class: 'field' + (wide ? ' wide' : '') }, h('label', null, label),
    h('input', { class: 'input', value: p[k], placeholder: ph || '', oninput: e => queue(d, n, { props: { [k]: e.target.value } }) }));
  const toggle = (k, label, def) => {
    const on = (p[k] || String(def)) === 'true';
    const sw = h('label', { class: 'switch' + (on ? ' on' : '') }, h('span', { class: 'tr' }), h('span', null, label));
    sw.addEventListener('click', () => { queue(d, n, { props: { [k]: String(!((p[k] || String(def)) === 'true')) } }); renderContent(); });
    return sw;
  };
  const fileInput = field('filename', '文件路径', '/path/to/data.csv（相对路径相对于 jmx 所在目录）', true);
  c.append(h('div', { class: 'req' }, titleLine(d, n), crumb(d, n)),
    h('div', { class: 'form', style: { paddingTop: '4px' } },
      h('div', { class: 'note', style: { marginBottom: '18px' } }, '每次发送读取下一行并写入变量；读到末尾后，开启“循环”则从头开始，否则变量值为 <EOF>。'),
      h('div', { class: 'grid' },
        fileInput,
        field('variableNames', '变量名（逗号分隔，留空则取首行）', 'id,name'),
        field('delimiter', '分隔符', ',  （\\t 表示 Tab）'),
        field('fileEncoding', '编码', 'UTF-8'),
        h('div', { class: 'field wide', style: { flexDirection: 'row', gap: '22px', marginTop: '4px' } },
          toggle('ignoreFirstLine', '忽略首行', false), toggle('quotedData', '允许带引号的数据', false), toggle('recycle', '读到末尾后循环', true)))));
}
function varsTable(d, n) {
  const rows = (n.props.variables || []).map(v => ({ ...v }));
  const tbody = h('tbody');
  const commit = () => queue(d, n, { props: { variables: rows.filter(r => r.name.trim()) } });
  const draw = () => {
    tbody.innerHTML = '';
    rows.forEach((r, i) => tbody.append(h('tr', null,
      h('td', null, h('input', { class: 'input', value: r.name, placeholder: '变量名', oninput: e => { r.name = e.target.value; commit(); } })),
      h('td', null, h('input', { class: 'input', value: r.value, placeholder: '值，可使用函数如 ${__time(yyyyMMdd,)}', oninput: e => { r.value = e.target.value; commit(); } })),
      h('td', null, h('button', { class: 'btn ghost sm icon', title: '删除', onclick: () => { rows.splice(i, 1); commit(); draw(); } }, I('x', 'xs'))))));
  };
  draw();
  return h('div', null,
    h('table', { class: 'kv' }, h('thead', null, h('tr', null, h('th', null, '名称'), h('th', null, '值'), h('th'))), tbody),
    h('button', { class: 'btn sm', style: { marginTop: '8px' }, onclick: () => { rows.push({ name: '', value: '' }); draw(); tbody.querySelector('tr:last-child input').focus(); } }, I('plus', 'xs'), '添加变量'));
}
function viewVars(d, n, c) {
  c.append(h('div', { class: 'req' }, titleLine(d, n), crumb(d, n)),
    h('div', { class: 'form', style: { paddingTop: '4px' } },
      h('div', { class: 'note', style: { marginBottom: '16px' } }, '在请求参数中用 ${名称} 引用。定义变化后，下次发送时会重新载入。'),
      varsTable(d, n)));
}
function viewPlan(d, n, c) {
  const groups = d.root.children.filter(x => x.kind === 'group');
  c.append(h('div', { class: 'req' }, titleLine(d, n)),
    h('div', { class: 'form', style: { paddingTop: '0' } },
      h('div', { class: 'help', style: { marginBottom: '16px', display: 'flex', gap: '10px', alignItems: 'center' } },
        d.path || '尚未保存',
        d.path && CAN_REVEAL ? h('a', { style: { color: 'var(--accent)', cursor: 'pointer' }, onclick: () => reveal(d.path) }, REVEAL_LABEL) : null),
      h('div', { class: 'section-title' }, '用户定义的变量'),
      varsTable(d, n),
      h('div', { class: 'section-title' }, '分组 (' + groups.length + ')'),
      h('div', { class: 'list' }, groups.map(g => h('div', { class: 'item', onclick: () => select(g.id) },
        I('folder', 's'), h('div', { class: 'grow' }, g.name), h('span', { class: 'sub' }, samplersIn(g).length + ' 个请求')))),
      h('button', { class: 'btn sm', style: { marginTop: '10px' }, onclick: () => addNode(d.root, 'group') }, I('plus', 'xs'), '新建分组'),
      h('div', { class: 'section-title' }, '备注'),
      h('textarea', { class: 'input ui', rows: 3, style: { maxWidth: '980px' }, oninput: e => queue(d, n, { props: { comments: e.target.value } }) }, n.props.comments || '')));
}
function viewGroup(d, n, c) {
  const reqs = n.children.filter(x => SENDABLE.has(x.kind) || x.kind === 'sampler');
  const configs = n.children.filter(x => !HIDDEN.has(x.kind) && !SENDABLE.has(x.kind) && x.kind !== 'sampler' && !CONTAINER.has(x.kind));
  const hidden = n.children.filter(x => HIDDEN.has(x.kind));
  const hiddenOther = hidden.filter(x => x.kind === 'other');
  c.append(h('div', { class: 'req' }, titleLine(d, n), crumb(d, n)),
    h('div', { class: 'form', style: { paddingTop: '4px' } },
      h('div', { class: 'section-title' }, '配置 (' + configs.length + ')',
        h('button', { class: 'btn ghost sm', onclick: e => { const r = e.currentTarget.getBoundingClientRect(); nodeMenuAdd(d, n, r.left, r.bottom + 4); } }, I('plus', 'xs'), '添加')),
      configs.length ? h('div', { class: 'list' }, configs.map(x => h('div', { class: 'item' + (isOn(x) ? '' : ' off'), onclick: () => select(x.id) },
        badge(x.kind), h('div', { class: 'grow' }, x.name),
        h('span', { class: 'sub' }, x.kind === 'defaults' ? (x.props.registryProtocol || '') + '://' + (x.props.registryAddress || '未填地址') : KIND_LABEL[x.kind]),
        CONFIG_KINDS.has(x.kind) ? miniSwitch(x) : null)))
        : h('div', { class: 'help' }, '没有配置元素。可以添加“Dubbo 默认配置”统一设置注册中心地址。'),
      h('div', { class: 'section-title' }, '请求 (' + reqs.length + ')',
        h('button', { class: 'btn ghost sm', onclick: () => addNode(n, 'dubbo') }, I('plus', 'xs'), '新建 Dubbo 请求'),
        h('button', { class: 'btn ghost sm', onclick: () => addNode(n, 'zms') }, I('plus', 'xs'), '新建 ZMS 消息')),
      h('div', { class: 'list' }, reqs.map(x => h('div', { class: 'item', onclick: () => select(x.id) },
        x.kind === 'sampler' ? badge('sampler', x) : h('span', { class: 'sdot ' + rowStatus(d, x) }), h('div', { class: 'grow' }, x.name),
        h('span', { class: 'sub', style: { fontFamily: 'var(--mono)' } }, x.kind === 'sampler' ? '不支持发送' : x.kind === 'zms' ? 'ZMS · ' + (x.props.topic || '') : (x.props.interface || '').replace(/^.*\./, '') + '#' + (x.props.method || ''))))),
      hidden.length ? h('div', { class: 'note', style: { marginTop: '18px' } },
        `此分组还包含 ${hidden.length} 个本工具不处理的元素（${hiddenOther.length ? '如 HTTP 请求、定时器等，' : ''}以及“察看结果树”等监听器），保存时会原样保留，用 JMeter 打开不受影响。`) : null));
}
function nodeMenuAdd(d, n, x, y) {
  showMenu(x, y, [
    { text: 'Dubbo 默认配置', icon: 'plug', run: () => addNode(n, 'defaults') },
    { text: 'BeanShell 前置脚本', icon: 'braces', run: () => addNode(n, 'pre') },
    { text: 'BeanShell 后置脚本', icon: 'braces', run: () => addNode(n, 'post') },
    { text: 'CSV 数据文件', icon: 'text', run: () => addNode(n, 'csv') },
    { text: '用户定义的变量', icon: 'text', run: () => addNode(n, 'vars') },
  ]);
}
const ZMS_MODES = [['syncSend', '同步发送（syncSend，返回 code）'], ['sendOneWay', '单向发送（sendOneWay，无响应）']];
function viewZms(d, n, c) {
  const p = n.props;
  const topicInput = h('input', {
    class: 'iface', value: p.topic, placeholder: 'topic 名称', spellcheck: 'false',
    oninput: e => {
      queue(d, n, { props: { topic: e.target.value } });
      const m = els.tree.querySelector(`.row[data-id="${n.id}"] .meta`);
      if (m) m.textContent = e.target.value;
    },
  });
  const sendBtn = els.sendBtn = sendButton(d, n);
  const toConn = () => { S.reqTab = 'conn'; renderContent(); };
  const zk = resolveVars(d, n, p.zmsZKAddr || '');
  const oneway = (p.messageSendMod || 'syncSend') === 'sendOneWay';
  const chips = [
    zk ? h('span', { class: 'chip', onclick: toConn, title: 'ZMS 注册中心（zms_zk）' }, I('plug', 'xs'), h('b', null, zk))
      : h('span', { class: 'chip warn', onclick: toConn }, I('plug', 'xs'), '未填写 ZK 地址'),
    h('span', { class: 'chip', onclick: toConn }, '方式', h('b', null, oneway ? 'sendOneWay' : 'syncSend')),
    p.messageTag ? h('span', { class: 'chip', onclick: toConn }, 'Tag', h('b', null, p.messageTag)) : null,
    p.messageKey ? h('span', { class: 'chip', onclick: toConn }, 'Key', h('b', null, p.messageKey)) : null,
  ];
  const scope = scopeOf(d, n);
  const nScope = scope.filter(x => x.kind !== 'defaults').length;
  const head = h('div', { class: 'req' },
    titleLine(d, n, h('span', { class: 'badge zms' }, 'ZMS')),
    crumb(d, n),
    h('div', { class: 'endpoint' }, h('span', { class: 'hash', style: { paddingLeft: '12px' } }, 'topic'), topicInput, sendBtn),
    h('div', { class: 'chips' }, chips));
  const tab = ['params', 'conn', 'scope'].includes(S.reqTab) ? S.reqTab : 'params';
  let editor = null;
  const fmtBtn = h('button', { class: 'btn ghost sm', onclick: () => {
    try { editor.ed.replaceAll(stringifyLoose(parseLoose(editor.ed.get()))); } catch (e) { toast('不是合法的 JSON，未格式化：' + e.message, true); }
  } }, I('wand', 's'), '格式化');
  const tabs = subtabs([['params', '消息体'], ['conn', '设置'], ['scope', '脚本与数据', nScope || null]], tab, k => { S.reqTab = k; renderContent(); },
    tab === 'params' ? fmtBtn : null);
  const body = h('div', { class: 'req-body' });
  if (tab === 'params') {
    editor = codeEditor({ value: p.messageBody || '', lang: 'json', hint: '消息体（payload），按 UTF-8 发送；支持 ${变量} 与 JMeter 函数', onChange: v => queue(d, n, { props: { messageBody: v } }) });
    body.append(editor);
  } else if (tab === 'conn') {
    const field = (k, label, ph, wide) => h('div', { class: 'field' + (wide ? ' wide' : '') }, h('label', null, label),
      h('input', { class: 'input', value: p[k] || '', placeholder: ph, spellcheck: 'false', oninput: e => queue(d, n, { props: { [k]: e.target.value } }) }));
    const mode = h('select', { class: 'input ui', onchange: e => { queue(d, n, { props: { messageSendMod: e.target.value } }); } },
      ZMS_MODES.map(([v, t]) => h('option', { value: v, selected: (p.messageSendMod || 'syncSend') === v }, t)));
    body.append(h('div', { class: 'form' },
      h('div', { class: 'note', style: { marginBottom: '16px' } }, '与 JMeter 插件 ', h('code', null, 'com.zto.zmsjmeter.ZMSSampler'),
        ' 行为一致：同步发送返回 code=200 为成功；单向发送不等待结果。ZK 地址即 ZMS 客户端的 ', h('code', null, 'zms_zk'), '。'),
      h('div', { class: 'grid' },
        field('zmsZKAddr', 'ZK 地址（zmsZKAddr）', '10.0.0.1:2181,10.0.0.2:2181', true),
        field('topic', 'Topic', ''),
        h('div', { class: 'field' }, h('label', null, '发送方式'), mode),
        field('messageTag', 'Tag（可选）', ''),
        field('messageKey', 'Key（可选）', ''))));
  } else {
    body.append(scopeView(d, n));
  }
  c.append(head, tabs, body, hResizer(), els.resp = h('div', { class: 'resp', style: { height: (localStorage.getItem('dl.respH') || 46) + '%' } }));
  renderResp();
}
function viewSampler(d, n, c) {
  const fields = n.props.fields || [];
  c.append(h('div', { class: 'req' }, titleLine(d, n, badge('sampler', n)), crumb(d, n)),
    h('div', { class: 'form', style: { paddingTop: '4px' } },
      h('div', { class: 'note', style: { marginBottom: '16px' } },
        '这是 ', h('code', null, n.testclass), ' 类型的请求，不是 Dubbo 请求，Dubbo Lite 只展示不发送；可以重命名、启用/停用、移动，其余内容保存时原样保留，用 JMeter 打开不受影响。'),
      fields.length ? h('table', { class: 'kvt', style: { maxWidth: '980px' } }, fields.map(([k, v]) => h('tr', null, h('td', null, k), h('td', { class: 'm', style: { whiteSpace: 'pre-wrap' } }, v))))
        : h('div', { class: 'help' }, '没有可显示的属性')));
}
function viewOther(d, n, c) {
  c.append(h('div', { class: 'req' }, titleLine(d, n), crumb(d, n)),
    h('div', { class: 'form' }, h('div', { class: 'note' }, '“' + n.testclass + '” 不属于 Dubbo 请求的核心能力，这里不提供编辑；保存时原样保留。')));
}

/* ------------------------------------------------------------------ global events */

document.addEventListener('keydown', e => {
  const mod = IS_MAC ? e.metaKey : e.ctrlKey;
  if (e.key === 'Escape') {
    const hadMenu = !!menuEl || !!subMenuEl;
    if (subMenuEl) closeSubMenu();
    else closeMenu();
    // Esc 中断当前请求（不抢搜索框的 Esc）
    if (!hadMenu && !(e.target.closest && e.target.closest('.search')) && cancelCurrent()) e.preventDefault();
  }
  if (!mod) return;
  const k = e.key.toLowerCase();
  if (k === 's') { e.preventDefault(); save(doc(), e.shiftKey); }
  else if (k === 'o') { e.preventDefault(); openDialog(); }
  else if (k === 'enter') { e.preventDefault(); sendCurrent(); }
  else if (k === 'k' || k === 'p') { e.preventDefault(); COMMANDS.find(); }
});
document.addEventListener('mousedown', e => {
  if (subMenuEl && subMenuEl.contains(e.target)) return;
  closeSubMenu();
  if (menuEl && !menuEl.contains(e.target)) closeMenu();
});
addEventListener('blur', closeMenu);
addEventListener('beforeunload', e => {
  if (!NATIVE && window.__hasDirty()) { e.preventDefault(); e.returnValue = ''; }
});
let dragDepth = 0;
const isFileDrag = e => e.dataTransfer && [...e.dataTransfer.types].includes('Files');
addEventListener('dragenter', e => { if (isFileDrag(e)) { dragDepth++; document.body.classList.add('dropping'); } });
addEventListener('dragleave', e => { if (isFileDrag(e) && --dragDepth <= 0) { dragDepth = 0; document.body.classList.remove('dropping'); } });
addEventListener('dragover', e => { if (isFileDrag(e)) e.preventDefault(); });
addEventListener('drop', async e => {
  if (!isFileDrag(e)) return;
  e.preventDefault();
  dragDepth = 0;
  document.body.classList.remove('dropping');
  for (const file of e.dataTransfer.files) {
    if (file.path) { await openPath(file.path); continue; }
    try {
      const d = applyDoc(await api('openContent', { name: file.name, content: await file.text() }));
      S.cur = d.id;
      render();
      toast('已打开“' + file.name + '”。浏览器拖入的文件拿不到原路径，保存时需要选择位置');
    } catch (err) {
      toast(file.name + '：' + err.message, true);
    }
  }
});

/* ------------------------------------------------------------------ boot */

(async function boot() {
  if (!TOKEN) {
    document.body.innerHTML = '<p style="padding:40px;font:14px sans-serif">请通过 Dubbo Lite 启动器打开。</p>';
    return;
  }
  const es = new EventSource('/api/events?t=' + TOKEN);
  es.onerror = () => {};
  try {
    S.state = await api('state');
    applyTheme(S.state.theme);
    for (const x of S.state.docs) applyDoc(await api('doc', { docId: x.id }));
    if (S.docs.length) S.cur = S.docs[0].id;
  } catch (e) {
    toast(e.message, true);
  }
  render();
  const open = QS.get('open');
  if (open) {
    history.replaceState(null, '', '?t=' + TOKEN);
    await openPath(open);
  }
  if (S.state.autoUpdate) setTimeout(() => checkUpdate(false), 3000);
})();
