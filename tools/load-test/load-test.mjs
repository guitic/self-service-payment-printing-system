#!/usr/bin/env node
/**
 * 纯 Node 零依赖压测脚本 —— 自助付费打印系统「下单链路」快速容量摸底
 * ============================================================================
 * 仅使用 Node 内置能力（fetch / FormData / Blob / performance），无需 npm install、无需 k6。
 * 用于无 k6 环境时对「quote + orders」链路做快速压测，输出吞吐与延迟分位。
 *
 * 用法：
 *   node tools/load-test/load-test.mjs
 *   node tools/load-test/load-test.mjs --base http://127.0.0.1:8080 --vus 50 --duration 30 --mix quote,order
 *   node tools/load-test/load-test.mjs --vus 100 --duration 60 --mix quote:3,order:1
 *   node tools/load-test/load-test.mjs --mix quote            # 仅试算
 *   node tools/load-test/load-test.mjs --token <JWT> --file-id 1   # 复用已有登录态
 *
 * 参数：
 *   --base      后端基址（默认 http://127.0.0.1:8080）
 *   --vus       并发数（默认 50）
 *   --duration  持续秒数（默认 30）
 *   --mix       操作混合，逗号分隔，支持 name 或 name:weight（默认 quote,order）
 *   --token     复用外部 JWT（目标非 demo 档、无 debugCode 时使用）
 *   --file-id   复用外部文件 ID（配合 --token）
 *   --timeout   单请求超时毫秒（默认 10000）
 *
 * 说明：demo 档会自动完成「发验证码 → 登录 → 上传内存 PDF」；200 VU 级正式验收请用 k6。
 * ============================================================================
 */

import process from 'node:process';

// ——————————————————— CLI 解析 ———————————————————

const DEFAULTS = {
  base: 'http://127.0.0.1:8080',
  vus: 50,
  duration: 30,
  mix: 'quote,order',
  token: '',
  fileId: 0,
  timeout: 10000,
};

function parseArgs(argv) {
  const alias = {
    '--base': 'base',
    '--vus': 'vus',
    '--duration': 'duration',
    '--mix': 'mix',
    '--token': 'token',
    '--file-id': 'fileId',
    '--timeout': 'timeout',
  };
  const opts = { ...DEFAULTS };
  for (let i = 2; i < argv.length; i++) {
    const raw = argv[i];
    if (raw === '-h' || raw === '--help') {
      console.log(
        '用法: node load-test.mjs [--base URL] [--vus N] [--duration SEC] [--mix quote,order]\n' +
          '                        [--token JWT] [--file-id ID] [--timeout MS]',
      );
      process.exit(0);
    }
    let key = raw;
    let val;
    const eq = raw.indexOf('=');
    if (raw.startsWith('--') && eq !== -1) {
      key = raw.slice(0, eq);
      val = raw.slice(eq + 1);
    } else {
      val = argv[++i];
    }
    const name = alias[key];
    if (!name) {
      console.error(`未知参数: ${key}`);
      process.exit(2);
    }
    opts[name] = val;
  }
  opts.base = String(opts.base).replace(/\/$/, '');
  opts.vus = Math.max(1, parseInt(opts.vus, 10) || DEFAULTS.vus);
  opts.duration = Math.max(1, parseInt(opts.duration, 10) || DEFAULTS.duration);
  opts.fileId = parseInt(opts.fileId, 10) || 0;
  opts.timeout = Math.max(1000, parseInt(opts.timeout, 10) || DEFAULTS.timeout);
  return opts;
}

/** 解析 --mix，返回 {name, weight} 列表 */
function parseMix(mixStr) {
  const ops = [];
  for (const part of String(mixStr).split(',')) {
    const t = part.trim();
    if (!t) continue;
    const [name, w] = t.split(':');
    const weight = Math.max(1, parseInt(w, 10) || 1);
    if (name === 'quote' || name === 'order') ops.push({ name, weight });
    else console.warn(`忽略未知操作: ${name}（仅支持 quote / order）`);
  }
  if (ops.length === 0) ops.push({ name: 'quote', weight: 1 }, { name: 'order', weight: 1 });
  return ops;
}

/** 按权重随机选一个操作 */
function pickOp(ops) {
  const total = ops.reduce((s, o) => s + o.weight, 0);
  let r = Math.random() * total;
  for (const o of ops) {
    if ((r -= o.weight) < 0) return o.name;
  }
  return ops[ops.length - 1].name;
}

// ——————————————————— 工具 ———————————————————

const API = (opts) => opts.base + '/api/v1';
const randPhone = () => '138' + String(Math.floor(10000000 + Math.random() * 89999999));

/** 生成含 pages 页的最小合法 PDF */
function makePdf(pages = 3) {
  const objs = [];
  objs.push('1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj');
  const kids = [];
  for (let i = 0; i < pages; i++) kids.push(`${3 + i} 0 R`);
  objs.push(`2 0 obj << /Type /Pages /Kids [${kids.join(' ')}] /Count ${pages} >> endobj`);
  for (let i = 0; i < pages; i++) {
    objs.push(`${3 + i} 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >> endobj`);
  }
  const head = '%PDF-1.4\n';
  const body = objs.join('\n') + '\n';
  const tail = `trailer << /Root 1 0 R /Size ${3 + pages} >>\n%%EOF\n`;
  return Buffer.from(head + body + tail, 'latin1');
}

/** 带超时的 fetch，返回 {status, json, ok, latencyMs, errType} */
async function req(opts, method, url, { token, body, form } = {}) {
  const headers = {};
  if (token) headers['Authorization'] = 'Bearer ' + token;
  let payload;
  if (form) {
    payload = form; // FormData：由 fetch 自动设置 multipart 边界
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), opts.timeout);
  const t0 = performance.now();
  try {
    const res = await fetch(url, { method, headers, body: payload, signal: ctrl.signal });
    const latencyMs = performance.now() - t0;
    let json = null;
    const text = await res.text();
    try {
      json = JSON.parse(text);
    } catch {
      /* 非 JSON */
    }
    return { status: res.status, json, ok: res.ok, latencyMs, errType: null };
  } catch (e) {
    const latencyMs = performance.now() - t0;
    const errType = e && e.name === 'AbortError' ? 'TIMEOUT' : 'NETWORK';
    return { status: 0, json: null, ok: false, latencyMs, errType };
  } finally {
    clearTimeout(timer);
  }
}

// ——————————————————— setup：登录 + 上传 ———————————————————

async function setup(opts) {
  if (opts.token && opts.fileId) {
    console.log(`[setup] 复用外部登录态 fileId=${opts.fileId}`);
    return { token: opts.token, fileId: opts.fileId };
  }

  const phone = randPhone();
  const sms = await req(opts, 'POST', `${API(opts)}/auth/sms/send`, { body: { phone } });
  const debugCode = sms.json?.data?.debugCode;
  if (!debugCode) {
    throw new Error(
      '未取得 debugCode：目标可能非 demo 档。请用 --token <JWT> --file-id <ID> 复用登录态，' +
        '或在 staging 打开 print.sms.debug=true。',
    );
  }

  const login = await req(opts, 'POST', `${API(opts)}/auth/login`, { body: { phone, code: debugCode } });
  const token = login.json?.data?.token;
  if (!token) throw new Error('登录失败：' + JSON.stringify(login.json));

  const form = new FormData();
  form.append('file', new Blob([makePdf(3)], { type: 'application/pdf' }), 'load_3p.pdf');
  const up = await req(opts, 'POST', `${API(opts)}/files`, { token, form });
  const fileId = up.json?.data?.id;
  if (!fileId) throw new Error('上传文件失败：' + JSON.stringify(up.json));

  console.log(`[setup] 登录成功 phone=${phone} fileId=${fileId} pageCount=${up.json?.data?.pageCount}`);
  return { token, fileId };
}

// ——————————————————— 统计容器 ———————————————————

function createStats() {
  return {
    total: 0,
    success: 0,
    fail: 0,
    latencies: [],       // 采样延迟（ms）
    errors: new Map(),   // 分类 → 计数
    ops: new Map(),      // 操作名 → {count, fail, latSum}
    maxLatency: 0,
    stop: false,
  };
}

function classify(res) {
  if (res.errType) return res.errType; // TIMEOUT / NETWORK
  if (res.status >= 400) return `HTTP ${res.status}`;
  if (res.json && res.json.code !== 0) return `业务 code=${res.json.code}`;
  return null;
}

const MAX_SAMPLES = 200000;

function record(stats, opName, res) {
  stats.total++;
  if (res.latencyMs > stats.maxLatency) stats.maxLatency = res.latencyMs;
  if (stats.latencies.length < MAX_SAMPLES) stats.latencies.push(res.latencyMs);

  const errType = classify(res);
  const ok = errType === null;
  if (ok) stats.success++;
  else {
    stats.fail++;
    stats.errors.set(errType, (stats.errors.get(errType) || 0) + 1);
  }

  const o = stats.ops.get(opName) || { count: 0, fail: 0, latSum: 0 };
  o.count++;
  o.latSum += res.latencyMs;
  if (!ok) o.fail++;
  stats.ops.set(opName, o);
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

// ——————————————————— 压测主循环 ———————————————————

async function run(opts) {
  const ops = parseMix(opts.mix);
  const mixLabel = ops.map((o) => (o.weight > 1 ? `${o.name}:${o.weight}` : o.name)).join(',');

  console.log('\n=== 自助付费打印系统 · Node 压测 ===');
  console.log(`目标: ${opts.base}   并发: ${opts.vus}   时长: ${opts.duration}s   混合: ${mixLabel}\n`);

  // 0. 健康检查（非致命）
  const health = await req(opts, 'GET', opts.base + '/actuator/health');
  console.log(`[health] ${health.status} ${health.json?.status || '(无响应)'}\n`);

  const ctx = await setup(opts);
  const stats = createStats();

  const deadline = Date.now() + opts.duration * 1000;
  const authHeaders = { Authorization: 'Bearer ' + ctx.token };

  // 进度打印
  const progress = setInterval(() => {
    const elapsed = opts.duration - Math.max(0, (deadline - Date.now()) / 1000);
    const qps = elapsed > 0 ? (stats.total / elapsed).toFixed(0) : '0';
    console.log(`[进度] ${elapsed.toFixed(0)}s  已完成 ${stats.total} 请求  当前 QPS ≈ ${qps}`);
  }, Math.max(1000, Math.floor((opts.duration * 1000) / 6)));

  // 单个 VU 的循环
  async function vu() {
    while (!stats.stop && Date.now() < deadline) {
      const opName = pickOp(ops);
      const body = {
        fileId: ctx.fileId,
        copies: 1 + Math.floor(Math.random() * 3),
        colorMode: Math.random() < 0.8 ? 'MONO' : 'COLOR',
        duplexMode: Math.random() < 0.5 ? 'SIMPLEX' : 'DUPLEX',
        paperSize: Math.random() < 0.9 ? 'A4' : 'A3',
      };
      let res;
      if (opName === 'quote') {
        res = await req(opts, 'POST', `${API(opts)}/pricing/quote`, { token: ctx.token, body });
      } else {
        res = await req(opts, 'POST', `${API(opts)}/orders`, {
          token: ctx.token,
          body: { ...body, terminalCode: 'TERM-001' },
        });
      }
      record(stats, opName, res);
    }
  }

  const wallStart = Date.now();
  await Promise.all(Array.from({ length: opts.vus }, () => vu()));
  const wallSeconds = (Date.now() - wallStart) / 1000;
  stats.stop = true;
  clearInterval(progress);

  // —— 汇总 ——
  const sorted = stats.latencies.slice().sort((a, b) => a - b);
  const p50 = percentile(sorted, 50);
  const p95 = percentile(sorted, 95);
  const p99 = percentile(sorted, 99);
  const qps = stats.total / wallSeconds;
  const succRate = stats.total ? (stats.success / stats.total) * 100 : 0;

  console.log('\n=== 结果 ===');
  console.log(`总请求数 : ${stats.total}`);
  console.log(`成功     : ${stats.success} (${succRate.toFixed(2)}%)`);
  console.log(`失败     : ${stats.fail} (${(100 - succRate).toFixed(2)}%)`);
  console.log(`QPS      : ${qps.toFixed(1)}  (实际墙钟 ${wallSeconds.toFixed(1)}s)`);
  console.log(
    `延迟(ms) : P50=${p50.toFixed(1)}  P95=${p95.toFixed(1)}  P99=${p99.toFixed(1)}  max=${stats.maxLatency.toFixed(1)}`,
  );

  console.log('\n按操作:');
  for (const [name, o] of stats.ops) {
    const avg = o.count ? o.latSum / o.count : 0;
    console.log(`  ${name.padEnd(6)} 请求=${o.count}  失败=${o.fail}  平均延迟=${avg.toFixed(1)}ms`);
  }

  if (stats.errors.size > 0) {
    console.log('\n错误分类:');
    const entries = [...stats.errors.entries()].sort((a, b) => b[1] - a[1]);
    for (const [type, n] of entries) console.log(`  ${type} × ${n}`);
  } else {
    console.log('\n错误分类: 无');
  }

  // 达标提示（对齐设计文档 §7.6.1）
  const pass = p95 < 500 && stats.total > 0 && stats.fail / stats.total < 0.01;
  console.log(`\n判定(P95<500ms 且 失败率<1%): ${pass ? '✅ 达标' : '❌ 未达标'}\n`);

  // 用 exitCode 而非 process.exit()：后者会截断管道中尚未刷出的 stdout
  process.exitCode = stats.total === 0 ? 2 : pass ? 0 : 1;
}

run(parseArgs(process.argv)).catch((e) => {
  console.error('\n压测异常:', e.message || e);
  process.exitCode = 2;
});
