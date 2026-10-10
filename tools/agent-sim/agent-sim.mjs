#!/usr/bin/env node
/**
 * 自助付费打印系统 —— 打印助手本地仿真器
 *
 * 与 .NET 8 打印助手（print-agent/）行为等价，纯 Node.js 内置能力实现（fetch / fs / process），
 * 无任何第三方依赖。用于本机零依赖端到端联调（CONTRACT.md §3.6 / §5）。
 *
 * 功能：
 *   - 心跳：每 30s POST /api/v1/agent/heartbeat（Header X-Agent-Token）
 *   - 长轮询领取：POST /api/v1/agent/tasks/claim（longPollSeconds，默认 25）
 *   - 下载文件：GET  /api/v1/agent/tasks/{id}/file → 落本地 spool
 *   - 模拟打印：延时 + 结构化日志（可注入随机失败 --fail-rate）
 *   - 回传：POST /api/v1/agent/tasks/{id}/report {status, failReason}
 *   - 断线重连：指数退避 1→2→4→8→16→32→60s（±20% 抖动），429 尊重 Retry-After，4xx 不重试
 *   - 离线 spool 补报：回传失败时落本地 JSON（原子 rename），恢复后按序补报
 *   - 优雅退出：SIGINT / SIGTERM 停机
 *
 * 用法见 README.md。
 */

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const VERSION = '1.0.0';
const BACKOFF_SECONDS = [1, 2, 4, 8, 16, 32, 60];
const LOCAL_RETRY_BACKOFF = [3, 6, 12]; // 设计文档 §2.5 本地瞬时错误退避

// ——————————————————— CLI 解析 ———————————————————

function parseArgs(argv) {
  const defaults = {
    backend: 'http://127.0.0.1:8080',
    terminal: 'TERM-001',
    token: 'dev-agent-token',
    failRate: 0,
    printSeconds: 2,
    pollInterval: 3,
    longPoll: 25,
    heartbeat: 30,
    maxRetry: 3,
    spoolDir: path.resolve(process.cwd(), 'spool'),
    printer: '',
    help: false
  };

  const alias = {
    '--backend': 'backend',
    '--terminal': 'terminal',
    '--token': 'token',
    '--fail-rate': 'failRate',
    '--print-seconds': 'printSeconds',
    '--poll-interval': 'pollInterval',
    '--long-poll': 'longPoll',
    '--heartbeat': 'heartbeat',
    '--max-retry': 'maxRetry',
    '--spool-dir': 'spoolDir',
    '--printer': 'printer',
    '--help': 'help',
    '-h': 'help'
  };

  const numeric = new Set(['failRate', 'printSeconds', 'pollInterval', 'longPoll', 'heartbeat', 'maxRetry']);

  for (let i = 0; i < argv.length; i++) {
    const raw = argv[i];
    const eq = raw.indexOf('=');
    const key = eq >= 0 ? raw.slice(0, eq) : raw;
    const field = alias[key];
    if (!field) {
      console.error(`未知参数：${raw}（用 --help 查看用法）`);
      process.exit(2);
    }
    if (field === 'help') { defaults.help = true; continue; }

    let value = eq >= 0 ? raw.slice(eq + 1) : argv[++i];
    if (value === undefined) {
      console.error(`参数 ${key} 缺少取值`);
      process.exit(2);
    }
    if (numeric.has(field)) {
      const n = Number(value);
      if (!Number.isFinite(n) || n < 0) {
        console.error(`参数 ${key} 需要非负数字，收到：${value}`);
        process.exit(2);
      }
      defaults[field] = n;
    } else {
      defaults[field] = value;
    }
  }

  // 约束：长轮询须小于 Nginx proxy_read_timeout 65s
  if (defaults.longPoll >= 65) {
    console.error(`--long-poll 必须小于 65（Nginx 反代超时），收到：${defaults.longPoll}`);
    process.exit(2);
  }
  if (defaults.spoolDir) defaults.spoolDir = path.resolve(defaults.spoolDir);
  return defaults;
}

function printHelp() {
  console.log(`打印助手仿真器 v${VERSION}

用法：
  node agent-sim.mjs [选项]

选项：
  --backend <url>        后端基址（默认 http://127.0.0.1:8080）
  --terminal <code>      终端编码（默认 TERM-001）
  --token <token>        X-Agent-Token（默认 dev-agent-token）
  --fail-rate <0..1>     模拟打印失败概率（默认 0）
  --print-seconds <n>    单次模拟打印耗时秒数（默认 2）
  --poll-interval <n>    空闲轮询间隔秒数（默认 3）
  --long-poll <n>        长轮询 hold 秒数，须 <65（默认 25）
  --heartbeat <n>        心跳间隔秒数（默认 30）
  --max-retry <n>        本地瞬时错误重试上限（默认 3）
  --spool-dir <dir>      spool 目录（默认 ./spool）
  --printer <name>       上报的打印机名（默认 Default Printer）
  -h, --help             显示本帮助

示例：
  node agent-sim.mjs --backend http://127.0.0.1:8080 --terminal TERM-001 --token dev-agent-token --fail-rate 0.1 --print-seconds 2
`);
}

// ——————————————————— 日志 ———————————————————

function ts() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

function log(level, msg) {
  const line = `[${ts()}] [${level}] ${msg}`;
  if (level === 'ERROR' || level === 'WARN') console.error(line);
  else console.log(line);
}

// ——————————————————— 工具 ———————————————————

function sleep(ms, signal) {
  return new Promise((resolve) => {
    if (signal?.aborted) return resolve();
    const timer = setTimeout(resolve, ms);
    if (signal) {
      signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
    }
  });
}

function makeBackoff() {
  let attempt = 0;
  return {
    get attempt() { return attempt; },
    reset() { attempt = 0; },
    /** 返回下一次等待秒数；429 传入 retryAfter 时优先采用且不计入序列 */
    next(retryAfterSec) {
      if (retryAfterSec && retryAfterSec > 0) { attempt = 0; return retryAfterSec; }
      const base = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
      attempt++;
      const jitter = 1 + (Math.random() * 0.4 - 0.2); // ±20%
      return base * jitter;
    }
  };
}

function parseRetryAfter(header) {
  if (!header) return undefined;
  const sec = Number(header);
  if (Number.isFinite(sec)) return sec;
  const date = Date.parse(header);
  if (!Number.isNaN(date)) return Math.max(0, (date - Date.now()) / 1000);
  return undefined;
}

function makeError(message, { retryable = false, status = 0, retryAfter, aborted = false } = {}) {
  return Object.assign(new Error(message), { retryable, status, retryAfter, aborted });
}

// ——————————————————— HTTP 客户端 ———————————————————

let OPTS;

async function api(pathname, { method = 'GET', body, timeoutMs = 70000, signal } = {}) {
  const url = OPTS.backend.replace(/\/+$/, '') + pathname;
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (signal) signal.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  let res;
  try {
    res = await fetch(url, {
      method,
      headers: {
        'X-Agent-Token': OPTS.token,
        'Accept': 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {})
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal
    });
  } catch (e) {
    if (signal?.aborted) throw makeError('已停机', { aborted: true });
    throw makeError(`网络不可达/超时: ${e.message}`, { retryable: true });
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
  }

  if (res.status === 429) {
    throw makeError('后端限流 429', { retryable: true, status: 429, retryAfter: parseRetryAfter(res.headers.get('retry-after')) });
  }
  if (res.status >= 500) {
    throw makeError(`服务端错误 ${res.status}: ${await safeText(res)}`, { retryable: true, status: res.status });
  }
  if (!res.ok) {
    throw makeError(`客户端错误 ${res.status}: ${await safeText(res)}`, { retryable: false, status: res.status });
  }

  let json;
  try {
    json = await res.json();
  } catch (e) {
    throw makeError(`响应解析失败: ${e.message}`, { retryable: true, status: res.status });
  }
  if (json.code !== 0) {
    throw makeError(`业务错误 code=${json.code} message=${json.message}`, { retryable: false, status: res.status });
  }
  return json.data;
}

async function safeText(res) {
  try { return (await res.text()).slice(0, 300); } catch { return ''; }
}

async function downloadFile(task, signal) {
  const url = `${OPTS.backend.replace(/\/+$/, '')}/api/v1/agent/tasks/${task.taskId}/file`;
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (signal) signal.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => controller.abort(), 70000);

  let res;
  try {
    res = await fetch(url, { headers: { 'X-Agent-Token': OPTS.token }, signal: controller.signal });
  } catch (e) {
    if (signal?.aborted) throw makeError('已停机', { aborted: true });
    throw makeError(`下载网络不可达/超时: ${e.message}`, { retryable: true });
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
  }

  if (res.status >= 500) throw makeError(`下载服务端错误 ${res.status}`, { retryable: true, status: res.status });
  if (!res.ok) throw makeError(`下载客户端错误 ${res.status}: ${await safeText(res)}`, { retryable: false, status: res.status });

  const buf = Buffer.from(await res.arrayBuffer());
  const safeName = sanitizeName(task.fileName) || `task-${task.taskId}.bin`;
  const filesDir = path.join(OPTS.spoolDir, 'files');
  await fsp.mkdir(filesDir, { recursive: true });
  const localPath = path.join(filesDir, `task-${task.taskId}-${safeName}`);
  await fsp.writeFile(localPath, buf);

  log('INFO', `task#${task.taskId} [DOWNLOAD] 文件已下载 ${task.fileName} (${buf.length}B) → ${localPath}`);
  return localPath;
}

function sanitizeName(name) {
  if (!name) return '';
  return path.basename(name).replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_');
}

// ——————————————————— 离线 spool ———————————————————

async function spoolSave(record) {
  const stamp = new Date(record.finishedAt).toISOString().replace(/[:.]/g, '');
  const name = `${stamp}-${record.taskId}-${Math.random().toString(16).slice(2, 10)}.json`;
  const finalPath = path.join(OPTS.spoolDir, name);
  const tmpPath = `${finalPath}.tmp`;
  await fsp.mkdir(OPTS.spoolDir, { recursive: true });
  await fsp.writeFile(tmpPath, JSON.stringify(record, null, 2));
  await fsp.rename(tmpPath, finalPath); // 原子 rename
  log('WARN', `task#${record.taskId} [SPOOL] 已写入离线待补报：${name}`);
}

async function spoolList() {
  await fsp.mkdir(OPTS.spoolDir, { recursive: true });
  const files = (await fsp.readdir(OPTS.spoolDir)).filter((f) => f.endsWith('.json'));
  const records = [];
  for (const f of files) {
    const full = path.join(OPTS.spoolDir, f);
    try {
      const rec = JSON.parse(await fsp.readFile(full, 'utf8'));
      rec.filePath = full;
      records.push(rec);
    } catch (e) {
      log('ERROR', `[SPOOL] 解析 spool 文件失败，跳过 ${f}: ${e.message}`);
    }
  }
  records.sort((a, b) => new Date(a.finishedAt) - new Date(b.finishedAt));
  return records;
}

async function spoolRemove(rec) {
  try { await fsp.rm(rec.filePath, { force: true }); } catch { /* ignore */ }
}

async function cleanupTemps() {
  try {
    const files = await fsp.readdir(OPTS.spoolDir);
    for (const f of files.filter((x) => x.endsWith('.tmp'))) {
      await fsp.rm(path.join(OPTS.spoolDir, f), { force: true });
    }
  } catch { /* ignore */ }
}

// ——————————————————— 业务：心跳 / 领取 / 打印 / 回传 ———————————————————

async function sendHeartbeat(signal) {
  const printers = [{ name: OPTS.printer || 'Default Printer', status: 'READY' }];
  const data = await api('/api/v1/agent/heartbeat', {
    method: 'POST',
    body: { terminalCode: OPTS.terminal, agentVersion: VERSION, printers },
    signal
  });
  log('DEBUG', `[HEARTBEAT] 成功 terminal=${OPTS.terminal} 打印机=${printers.length} serverTime=${data?.serverTime ?? '-'}`);
}

async function heartbeatLoop(signal) {
  const bo = makeBackoff();
  while (!signal.aborted) {
    try {
      await sendHeartbeat(signal);
      bo.reset();
      await sleep(OPTS.heartbeat * 1000, signal);
    } catch (e) {
      if (signal.aborted || e.aborted) break;
      if (e.retryable) {
        const d = bo.next(e.retryAfter);
        log('WARN', `[HEARTBEAT] 失败(可重试，第${bo.attempt}次): ${e.message}，${d.toFixed(1)}s 后重试`);
        await sleep(d * 1000, signal);
      } else {
        log('ERROR', `[HEARTBEAT] 失败(不可重试，需人工介入): ${e.message}`);
        await sleep(OPTS.heartbeat * 1000, signal);
      }
    }
  }
}

async function mockPrint(task, localPath, signal) {
  const durationMs = Math.max(OPTS.printSeconds, 0) * 1000;
  log('INFO', `task#${task.taskId} [PRINT] 开始出纸 file=${task.fileName} 参数=${task.colorMode}/${task.duplexMode}/${task.paperSize} x${task.copies} 页数=${task.pageCount} 预计=${durationMs}ms`);
  await sleep(durationMs, signal);
  if (signal.aborted) throw makeError('已停机', { aborted: true });
  if (OPTS.failRate > 0 && Math.random() < OPTS.failRate) {
    throw makeError('模拟打印失败（--fail-rate 注入）', { retryable: true });
  }
  log('INFO', `task#${task.taskId} [PRINT] 出纸完成`);
}

async function printWithLocalRetry(task, localPath, signal) {
  const maxRetries = Math.max(OPTS.maxRetry, 0);
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      await mockPrint(task, localPath, signal);
      return;
    } catch (e) {
      if (signal.aborted || e.aborted) throw e;
      if (attempt === maxRetries) {
        throw makeError(`本地重试 ${maxRetries} 次仍失败: ${e.message}`, { retryable: true });
      }
      const wait = LOCAL_RETRY_BACKOFF[Math.min(attempt, LOCAL_RETRY_BACKOFF.length - 1)];
      log('WARN', `task#${task.taskId} [PRINT] 瞬时失败（第${attempt + 1}/${maxRetries}次）: ${e.message}，${wait}s 后重试`);
      await sleep(wait * 1000, signal);
    }
  }
}

async function reportWithFallback(task, report, signal) {
  try {
    await api(`/api/v1/agent/tasks/${task.taskId}/report`, { method: 'POST', body: report, signal });
    log('INFO', `task#${task.taskId} [REPORT] 回传成功 status=${report.status}`);
  } catch (e) {
    if (e.aborted || signal.aborted) {
      // 停机中：先落盘再退出，确保结果不丢
      await spoolSave({
        taskId: task.taskId, orderNo: task.orderNo,
        status: report.status, failReason: report.failReason,
        finishedAt: new Date().toISOString()
      });
      throw e;
    }
    if (e.retryable) {
      log('WARN', `task#${task.taskId} [REPORT] 回传失败(可重试): ${e.message}，写入 spool 待补报`);
      await spoolSave({
        taskId: task.taskId, orderNo: task.orderNo,
        status: report.status, failReason: report.failReason,
        finishedAt: new Date().toISOString()
      });
    } else {
      log('ERROR', `task#${task.taskId} [REPORT] 回传失败(不可重试，告警): ${e.message}`);
    }
  }
}

async function processTask(task, signal) {
  log('INFO', `task#${task.taskId} order=${task.orderNo} [CLAIM] 领取到任务 ${task.fileName} (${task.fileSize}B)`);

  let localPath = null;
  try {
    localPath = await downloadFile(task, signal);
  } catch (e) {
    if (signal.aborted || e.aborted) throw e;
    if (e.retryable) {
      log('WARN', `task#${task.taskId} [DOWNLOAD] 后端不可用: ${e.message}；等待后端超时回收`);
    } else {
      log('ERROR', `task#${task.taskId} [DOWNLOAD] 下载失败(不可重试): ${e.message}`);
    }
    return;
  }

  try {
    await printWithLocalRetry(task, localPath, signal);
    await reportWithFallback(task, { status: 'SUCCESS' }, signal);
  } catch (e) {
    if (signal.aborted || e.aborted) {
      log('WARN', `task#${task.taskId} [ABORT] 停机中断任务处理，将由后端超时回收`);
      throw e;
    }
    log('ERROR', `task#${task.taskId} [FAIL] 打印失败: ${e.message}`);
    await reportWithFallback(task, { status: 'FAILED', failReason: e.message }, signal);
  } finally {
    if (localPath) { try { await fsp.rm(localPath, { force: true }); } catch { /* ignore */ } }
  }
}

async function flushSpool(signal) {
  const records = await spoolList();
  if (records.length === 0) return;
  log('INFO', `[SPOOL] 发现 ${records.length} 条离线待补报记录，尝试补报`);
  for (const rec of records) {
    if (signal.aborted) return;
    try {
      await api(`/api/v1/agent/tasks/${rec.taskId}/report`, {
        method: 'POST',
        body: { status: rec.status, failReason: rec.failReason },
        signal
      });
      await spoolRemove(rec);
      log('INFO', `[SPOOL] 补报成功 task#${rec.taskId} status=${rec.status}`);
    } catch (e) {
      if (signal.aborted || e.aborted) return;
      if (e.retryable) {
        log('WARN', `[SPOOL] 补报失败(后端不可用)，保留下次再试: task#${rec.taskId} ${e.message}`);
        return; // 停止本轮，避免无谓循环
      }
      log('ERROR', `[SPOOL] 补报失败(不可重试)，丢弃该记录: task#${rec.taskId} ${e.message}`);
      await spoolRemove(rec);
    }
  }
}

async function claimLoop(signal) {
  const bo = makeBackoff();
  while (!signal.aborted) {
    await flushSpool(signal);

    try {
      const task = await api('/api/v1/agent/tasks/claim', {
        method: 'POST',
        body: { terminalCode: OPTS.terminal, longPollSeconds: OPTS.longPoll },
        timeoutMs: (OPTS.longPoll + 10) * 1000, // 超时须大于长轮询 hold
        signal
      });
      bo.reset();

      if (!task) {
        await sleep(OPTS.pollInterval * 1000, signal);
        continue;
      }
      await processTask(task, signal);
    } catch (e) {
      if (signal.aborted || e.aborted) break;
      if (e.retryable) {
        const d = bo.next(e.retryAfter);
        log('WARN', `[CLAIM] 领取失败(可重试): ${e.message}，${d.toFixed(1)}s 后重试`);
        await sleep(d * 1000, signal);
      } else {
        log('ERROR', `[CLAIM] 领取失败(不可重试，需人工介入): ${e.message}`);
        await sleep(OPTS.pollInterval * 1000, signal);
      }
    }
  }
}

// ——————————————————— 主流程 ———————————————————

async function main() {
  OPTS = parseArgs(process.argv.slice(2));
  if (OPTS.help) { printHelp(); return; }

  fs.mkdirSync(OPTS.spoolDir, { recursive: true });
  await cleanupTemps();

  const controller = new AbortController();
  let stopping = false;
  const shutdown = (sig) => {
    if (stopping) return;
    stopping = true;
    log('INFO', `收到 ${sig}，正在优雅停机…`);
    controller.abort();
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  log('INFO', `打印助手(仿真器) 启动 v${VERSION} terminal=${OPTS.terminal} backend=${OPTS.backend} spool=${OPTS.spoolDir}`);
  log('INFO', `参数：heartbeat=${OPTS.heartbeat}s longPoll=${OPTS.longPoll}s pollInterval=${OPTS.pollInterval}s maxRetry=${OPTS.maxRetry} failRate=${OPTS.failRate} printSeconds=${OPTS.printSeconds}s`);

  await Promise.all([heartbeatLoop(controller.signal), claimLoop(controller.signal)]);

  log('INFO', '打印助手已停止');
}

main().catch((e) => {
  log('ERROR', `致命错误: ${e?.stack || e?.message || e}`);
  process.exit(1);
});
