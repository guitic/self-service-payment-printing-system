/**
 * k6 压测脚本 —— 自助付费打印系统「下单链路」容量验证
 * ============================================================================
 * 场景：200 并发（VU）混合「quote + orders」，持续 3 分钟
 * 目标（设计文档第 3 章 §7.6.1）：
 *   - 下单接口 P95 < 500ms
 *   - 请求失败率 < 1%
 *   - 无重复订单（orderNo 由后端唯一约束保证）
 *
 * 前置：脚本在 setup() 阶段自行完成「发验证码 → 登录 → 上传内存 PDF」，无需手工准备数据。
 *       依赖 demo 档返回 debugCode；staging/prod 请用环境变量提供已签发 token：
 *         k6 run -e TOKEN=<JWT> -e FILE_ID=<已有文件ID> k6-booking.js
 *       或让 staging 打开 SMS debug（sms.debug=true）。
 *
 * 运行：
 *   k6 run tools/load-test/k6-booking.js
 *   k6 run -e BASE=http://staging:8080 -e VUS=200 -e DURATION=3m tools/load-test/k6-booking.js
 *   # 快速冒烟（10 并发 15 秒）：
 *   k6 run -e VUS=10 -e DURATION=15s tools/load-test/k6-booking.js
 *
 * 结果解读见同目录 README.md。
 * ============================================================================
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// —— 可覆盖参数（-e KEY=VALUE）——
const BASE = (__ENV.BASE || 'http://127.0.0.1:8080').replace(/\/$/, '');
const API = BASE + '/api/v1';
const VUS = Number(__ENV.VUS || 200);
const DURATION = __ENV.DURATION || '3m';
const THINK_TIME = Number(__ENV.THINK_TIME || 0.3); // 每次迭代后的思考时间（秒）

// —— 自定义指标（在默认 k6 指标之外，补充业务视角）——
const quoteTrend = new Trend('booking_quote_duration', true);
const orderTrend = new Trend('booking_order_duration', true);
const orderCreated = new Counter('booking_orders_created');
const businessErrorRate = new Rate('booking_business_error_rate');

// —— 阈值：与设计文档 §7.6.1 一致 ——
export const options = {
  scenarios: {
    // 场景名 booking：k6 会自动给该场景下的请求打上 scenario=booking 标签，
    // 因此 http_req_duration{scenario:booking} 只统计本场景流量。
    booking: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      tags: { scenario: 'booking' },
    },
  },
  thresholds: {
    'http_req_duration{scenario:booking}': ['p(95)<500'], // 下单链路 P95 < 500ms
    'http_req_failed': ['rate<0.01'],                     // 失败率 < 1%
    'booking_business_error_rate': ['rate<0.01'],         // 业务错误率 < 1%
    'checks': ['rate>0.99'],                              // 断言通过率 > 99%
  },
  // 云/CI 场景可下调；本地默认不限制
  discardResponseBodies: false,
};

/**
 * 生成一个含 pages 页的最小合法 PDF（与 PageCountResolver 的 /Type /Page 计数兼容）。
 * @param {number} pages 页数
 * @returns {ArrayBuffer} PDF 字节
 */
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
  const str = head + body + tail;
  const buf = new Uint8Array(str.length);
  for (let i = 0; i < str.length; i++) buf[i] = str.charCodeAt(i) & 0xff;
  return buf.buffer;
}

/**
 * setup：每个测试运行只执行一次，完成登录与文件上传，返回共享的 token / fileId。
 */
export function setup() {
  // 允许直接用外部注入的 token / fileId（staging、prod 无 debugCode 时）
  if (__ENV.TOKEN && __ENV.FILE_ID) {
    console.log(`[setup] 使用外部注入 TOKEN / FILE_ID=${__ENV.FILE_ID}`);
    return { token: __ENV.TOKEN, fileId: Number(__ENV.FILE_ID) };
  }

  // 1) 随机手机号，规避 60s 发送冷却
  const phone = '138' + String(randomIntBetween(10000000, 99999999));
  const smsRes = http.post(
    `${API}/auth/sms/send`,
    JSON.stringify({ phone }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const debugCode = smsRes.json('data.debugCode');
  if (!debugCode) {
    throw new Error(
      '[setup] 未取得 debugCode：demo 档才会返回。staging/prod 请用 -e TOKEN=... -e FILE_ID=... 传入，或打开 sms.debug。',
    );
  }

  // 2) 登录拿 JWT
  const loginRes = http.post(
    `${API}/auth/login`,
    JSON.stringify({ phone, code: debugCode }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const token = loginRes.json('data.token');
  if (!token) throw new Error('[setup] 登录失败：' + loginRes.body);

  // 3) 上传内存 PDF，拿 fileId
  const form = http.file(makePdf(3), 'k6_3p.pdf', 'application/pdf');
  const upRes = http.post(`${API}/files`, { file: form }, {
    headers: { Authorization: 'Bearer ' + token },
  });
  const fileId = upRes.json('data.id');
  if (!fileId) throw new Error('[setup] 上传文件失败：' + upRes.body);

  console.log(`[setup] 登录成功 phone=${phone} fileId=${fileId} pageCount=${upRes.json('data.pageCount')}`);
  return { token, fileId };
}

/**
 * default：每个 VU 反复执行「试算 + 下单」混合操作。
 * 默认权重 quote:order = 3:1（贴近真实：用户试算次数多于下单）。
 */
export default function (data) {
  const authHeaders = {
    Authorization: 'Bearer ' + data.token,
    'Content-Type': 'application/json',
  };

  const body = {
    fileId: data.fileId,
    copies: randomIntBetween(1, 3),
    colorMode: Math.random() < 0.8 ? 'MONO' : 'COLOR',
    duplexMode: Math.random() < 0.5 ? 'SIMPLEX' : 'DUPLEX',
    paperSize: Math.random() < 0.9 ? 'A4' : 'A3',
  };

  // —— 试算（quote）：权重 3 ——
  for (let i = 0; i < 3; i++) {
    const t0 = Date.now();
    const res = http.post(`${API}/pricing/quote`, JSON.stringify(body), {
      headers: authHeaders,
      tags: { name: 'quote' },
    });
    quoteTrend.add(Date.now() - t0);
    const okRes = check(res, {
      'quote 200': (r) => r.status === 200,
      'quote code=0': (r) => r.json('code') === 0,
      'quote 返回金额': (r) => r.json('data.amount') !== undefined,
    });
    businessErrorRate.add(!okRes);
  }

  // —— 下单（orders）：权重 1 ——
  const t1 = Date.now();
  const orderRes = http.post(
    `${API}/orders`,
    JSON.stringify({ ...body, terminalCode: 'TERM-001' }),
    { headers: authHeaders, tags: { name: 'orders' } },
  );
  orderTrend.add(Date.now() - t1);
  const orderOk = check(orderRes, {
    'order 200': (r) => r.status === 200,
    'order code=0': (r) => r.json('code') === 0,
    'order 返回 orderNo': (r) => !!r.json('data.orderNo'),
  });
  businessErrorRate.add(!orderOk);
  if (orderOk) orderCreated.add(1);

  if (THINK_TIME > 0) sleep(THINK_TIME);
}

/**
 * teardown：打印一次摘要，便于 CI 日志快速判读。
 */
export function teardown(data) {
  console.log('[teardown] 压测结束。请关注 http_req_duration{scenario:booking} 的 p(95) 与 http_req_failed 是否达标。');
}
