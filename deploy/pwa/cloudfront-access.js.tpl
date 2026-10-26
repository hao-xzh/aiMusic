// CloudFront Functions runtime 2.0. Render secrets into a private upload file.
var crypto = require('crypto');
var passwordHash = '__PIPO_PASSWORD_SHA256__';
var signingKey = '__PIPO_SIGNING_KEY__';
var cookieName = '__Host-pipo_access';
var lifetime = 180 * 24 * 60 * 60;

function equal(a, b) {
    if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
    var different = 0;
    for (var i = 0; i < a.length; i++) different |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return different === 0;
}
function signature(expiry) {
    return crypto.createHmac('sha256', signingKey).update('pipo-access-v1:' + expiry).digest('base64url');
}
function response(status, type, body) {
    return {statusCode: status, headers: {
        'content-type': {value: type},
        'cache-control': {value: 'private, no-store'},
        'x-content-type-options': {value: 'nosniff'},
        'x-frame-options': {value: 'DENY'},
        'referrer-policy': {value: 'no-referrer'},
        'x-robots-tag': {value: 'noindex, nofollow, noarchive'}
    }, body: body};
}
function valid(value, now) {
    if (typeof value !== 'string' || !/^\d{10}\.[A-Za-z0-9_-]{43}$/.test(value)) return false;
    var parts = value.split('.'), expires = Number(parts[0]);
    return expires > now && expires <= now + lifetime + 60 && equal(parts[1], signature(parts[0]));
}
var page = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#111015"><title>PIPO</title><style>*{box-sizing:border-box}body{margin:0;min-height:100svh;display:grid;place-items:center;padding:24px;padding-top:max(24px,env(safe-area-inset-top));padding-bottom:max(24px,env(safe-area-inset-bottom));background:radial-gradient(ellipse at 50% 0,#332137,transparent 65%),#111015;color:#faf8fc;font:16px -apple-system,BlinkMacSystemFont,sans-serif}main{width:100%;max-width:360px}h1{font-size:38px;letter-spacing:4px;margin:0 0 12px}p{color:#aaa3b0;line-height:1.6}label{display:block;margin:30px 0 10px}input,button{width:100%;min-height:52px;border-radius:14px;font:inherit}input{border:1px solid #534957;padding:14px;background:#211c26;color:white}input:focus{outline:2px solid #e9b8dd;outline-offset:3px}button{margin-top:16px;background:#ecc8e4;color:#211421;border:0;font-weight:600;cursor:pointer}button:disabled{opacity:.6}small{display:block;margin-top:20px;color:#99919f;line-height:1.6}#status{min-height:24px;color:#edb5bb;margin:12px 0}</style><main><h1>PIPO</h1><p>你的音乐，随时相伴。</p><form id="form"><label for="password">访问口令</label><input id="password" name="password" type="password" autocomplete="current-password" required maxlength="128" placeholder="输入一次，即可进入"><button id="submit" type="submit">进入 PIPO</button><p id="status" role="status" aria-live="polite"></p></form><small>此设备将保持登录 180 天。</small></main><script>var form=document.getElementById("form"),input=document.getElementById("password"),button=document.getElementById("submit"),statusText=document.getElementById("status");form.addEventListener("submit",async function(event){event.preventDefault();button.disabled=true;statusText.textContent="";try{var result=await fetch("/__pipo/access",{method:"POST",headers:{"X-Pipo-Password":input.value},credentials:"same-origin",cache:"no-store"});if(!result.ok){statusText.textContent=result.status===401?"口令不正确，请重新输入。":"暂时无法连接，请稍后重试。";return;}input.value="";location.replace("/pwa");}catch(error){statusText.textContent="连接失败，请检查网络后重试。";}finally{button.disabled=false;}});</script></html>';

function handler(event) {
    var request = event.request, headers = request.headers || {};
    if (!/^[a-f0-9]{64}$/.test(passwordHash) || !/^[a-f0-9]{64}$/.test(signingKey)) {
        return response(503, 'application/json', '{"error":"服务尚未配置完成。"}');
    }
    var now = Math.floor(Date.now() / 1000);
    if (request.uri === '/__pipo/access') {
        var origin = headers.origin && headers.origin.value;
        var expectedOrigin = 'https://' + (headers.host && headers.host.value);
        if (request.method !== 'POST' || origin !== expectedOrigin) {
            return response(403, 'application/json', '{"error":"请求来源无效。"}');
        }
        var password = headers['x-pipo-password'] && headers['x-pipo-password'].value;
        if (typeof password !== 'string' || password.length > 128 ||
            !equal(crypto.createHash('sha256').update(password).digest('hex'), passwordHash)) {
            return response(401, 'application/json', '{"error":"口令不正确。"}');
        }
        var expiry = String(now + lifetime), accepted = response(200, 'application/json', '{"ready":true}');
        accepted.cookies = {};
        accepted.cookies[cookieName] = {value: expiry + '.' + signature(expiry),
            attributes: 'Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=' + lifetime};
        return accepted;
    }
    var cookie = request.cookies && request.cookies[cookieName];
    if (cookie && valid(cookie.value, now)) {
        delete request.cookies[cookieName];
        delete headers['x-pipo-password'];
        delete headers.authorization;
        if (request.uri === '/access') {
            var home = response(302, 'text/plain', ''); home.headers.location = {value: '/pwa'}; return home;
        }
        return request;
    }
    if (request.uri === '/access' && request.method === 'GET') {
        var login = response(200, 'text/html; charset=utf-8', page);
        login.headers['content-security-policy'] = {value: "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"};
        return login;
    }
    if ((request.method === 'GET' || request.method === 'HEAD') &&
        (request.uri === '/' || request.uri === '/pwa' || request.uri.indexOf('/pwa/') === 0)) {
        var redirect = response(302, 'text/plain', ''); redirect.headers.location = {value: '/access'}; return redirect;
    }
    return response(401, 'application/json', '{"error":"请打开 /access 验证此设备。","authRequired":true}');
}
