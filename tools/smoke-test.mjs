#!/usr/bin/env node
/**
 * 端到端冒烟测试（demo 档）
 *
 * 覆盖链路：发验证码 → 登录 → 上传 → 试算 → 下单 → 发起支付 → Mock 支付成功
 *          → 打印助手领取并回传 → 状态查询；并校验幂等（重复确认不重复投递任务）。
 *
 * 用法：
 *   node tools/smoke-test.mjs [baseUrl]
 * 默认 baseUrl = http://127.0.0.1:8080
 *
 * 前置：后端已启动；打印助手（.NET 或 agent-sim）已启动，否则最后一步会等待超时。
 */

const BASE = process.argv[2] || 'http://127.0.0.1:8080';
// 每次运行使用随机手机号，避免命中 60s 发送冷却（限频本身另有用例覆盖）
const PHONE = '138' + String(Math.floor(10000000 + Math.random() * 89999999));

let pass = 0;
let fail = 0;

function ok(name, cond, extra = '') {
  if (cond) {
    pass++;
    console.log(`  ✅ ${name}${extra ? '  ' + extra : ''}`);
  } else {
    fail++;
    console.log(`  ❌ ${name}${extra ? '  ' + extra : ''}`);
  }
}

async function api(method, path, { token, body, raw } = {}) {
  const headers = {};
  if (token) headers['Authorization'] = 'Bearer ' + token;
  let payload;
  if (raw) {
    payload = raw;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(BASE + path, { method, headers, body: payload });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON 响应 */
  }
  return { status: res.status, json, text };
}

/** 生成一个含 3 页的最小合法 PDF（PageCountResolver 按 /Type /Page 计数） */
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

async function main() {
  console.log(`\n=== 自助付费打印系统 · 端到端冒烟测试 ===`);
  console.log(`后端地址: ${BASE}\n`);

  // 0. 健康检查
  const health = await api('GET', '/actuator/health');
  ok('健康检查返回 UP', health.json?.status === 'UP', `status=${health.json?.status}`);

  // 1. 发送验证码（demo 档返回 debugCode）
  const sms = await api('POST', '/api/v1/auth/sms/send', { body: { phone: PHONE } });
  const code = sms.json?.data?.debugCode;
  ok('发送验证码成功且返回 debugCode', sms.json?.code === 0 && !!code, `code=${code}`);

  // 2. 登录
  const login = await api('POST', '/api/v1/auth/login', { body: { phone: PHONE, code } });
  const token = login.json?.data?.token;
  ok('验证码登录成功并签发 JWT', login.json?.code === 0 && !!token);

  // 3. 上传文件（multipart）
  const form = new FormData();
  form.append('file', new Blob([makePdf(3)], { type: 'application/pdf' }), 'smoke_3p.pdf');
  const upRes = await fetch(BASE + '/api/v1/files', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    body: form,
  });
  const up = await upRes.json();
  const fileId = up?.data?.id;
  ok('上传 PDF 并解析页数 = 3', up?.code === 0 && up?.data?.pageCount === 3,
    `fileId=${fileId}, pageCount=${up?.data?.pageCount}`);

  // 4. 试算：黑白 3 页 × 2 份 = 1.20（高于起印价 1.00）
  const quoteBody = { fileId, copies: 2, colorMode: 'MONO', duplexMode: 'SIMPLEX', paperSize: 'A4' };
  const quote = await api('POST', '/api/v1/pricing/quote', { token, body: quoteBody });
  ok('试算金额 = 1.20（3页×2份×0.20）', quote.json?.data?.amount === 1.2,
    `amount=${quote.json?.data?.amount}`);

  // 4b. 双面折扣：3 页 × 3 份 × 0.20 × 0.9 = 1.62（高于起印价，验证折扣与精度）
  const quoteDuplex = await api('POST', '/api/v1/pricing/quote', {
    token,
    body: { ...quoteBody, copies: 3, duplexMode: 'DUPLEX' },
  });
  ok('双面折扣金额 = 1.62（3页×3份×0.20×0.9）', Number(quoteDuplex.json?.data?.amount) === 1.62,
    `amount=${quoteDuplex.json?.data?.amount}`);

  // 4c. 起印价生效：1 页 × 1 份 = 0.20 → 取起印价 1.00
  const quoteMin = await api('POST', '/api/v1/pricing/quote', {
    token,
    body: { ...quoteBody, copies: 1, pageRange: { from: 1, to: 1 } },
  });
  ok('起印价生效 = 1.00', Number(quoteMin.json?.data?.amount) === 1.0,
    `amount=${quoteMin.json?.data?.amount}`);

  // 5. 下单
  const order = await api('POST', '/api/v1/orders', {
    token,
    body: { ...quoteBody, terminalCode: 'TERM-001' },
  });
  const orderNo = order.json?.data?.orderNo;
  ok('创建订单成功', order.json?.code === 0 && !!orderNo, `orderNo=${orderNo}`);

  // 6. 发起支付，取二维码
  const pay = await api('POST', `/api/v1/payments/${orderNo}/pay`, { token });
  ok('发起支付返回二维码内容', pay.json?.code === 0 && !!pay.json?.data?.qrCodeContent,
    `qr=${pay.json?.data?.qrCodeContent}`);

  // 7. Mock 支付成功
  const confirm1 = await api('POST', '/api/v1/payments/mock/confirm', { token, body: { orderNo } });
  ok('Mock 支付成功 → payStatus=1', confirm1.json?.data?.payStatus === 1,
    `payStatus=${confirm1.json?.data?.payStatus}`);

  // 8. 幂等：重复确认不重复投递任务
  await api('POST', '/api/v1/payments/mock/confirm', { token, body: { orderNo } });
  await api('POST', '/api/v1/payments/mock/confirm', { token, body: { orderNo } });
  const admin = await api('GET', '/api/v1/admin/orders?page=1&size=100', { token });
  const sameOrder = (admin.json?.data?.list || []).filter((o) => o.orderNo === orderNo);
  ok('重复确认后订单仍只有 1 笔（幂等）', sameOrder.length === 1, `count=${sameOrder.length}`);

  // 9. 等待打印助手领取并回传（最多 40s）
  console.log('\n  ⏳ 等待打印助手领取任务并回传（最多 40s）...');
  let finalStatus = null;
  for (let i = 0; i < 40; i++) {
    const st = await api('GET', `/api/v1/tasks/${orderNo}/status`, { token });
    finalStatus = st.json?.data;
    if (finalStatus?.printStatus === 2 || finalStatus?.printStatus === 3) break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  ok('打印任务最终状态 = 已完成', finalStatus?.printStatus === 2,
    `printStatus=${finalStatus?.printStatus} (${finalStatus?.printStatusText}), failReason=${finalStatus?.failReason ?? '-'}`);

  console.log(`\n=== 结果：${pass} 通过 / ${fail} 失败 ===\n`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('冒烟测试异常:', e);
  process.exit(2);
});
