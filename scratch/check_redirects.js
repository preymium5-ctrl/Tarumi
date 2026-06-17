const https = require('https');
const http = require('http');

const domains = [
  'manga18.app',
  'bestporncomix.com',
  'themeraider.com',
  'www.yaoihub.net',
  'zh-now-28laps.com',
  'manhwa18.cc',
  'www.manhwatoon.me',
  'www.cmonbae.com'
];

function checkDomain(domain) {
  return new Promise((resolve) => {
    const url = `https://${domain}/`;
    const options = {
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      },
      timeout: 5000,
      rejectUnauthorized: false
    };

    const req = https.request(url, options, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        const titleMatch = body.match(/<title>([^<]+)<\/title>/i);
        const title = titleMatch ? titleMatch[1].trim() : 'No Title';
        const isMadara = body.includes('wp-manga') || body.includes('c-tabs-item') || body.includes('page-item-detail');
        resolve({
          domain,
          status: res.statusCode,
          title,
          isMadara,
          length: body.length
        });
      });
    });

    req.on('error', err => {
      resolve({ domain, status: 'error', error: err.message });
    });

    req.on('timeout', () => {
      req.destroy();
      resolve({ domain, status: 'timeout' });
    });

    req.end();
  });
}

async function run() {
  const results = [];
  for (const domain of domains) {
    const res = await checkDomain(domain);
    results.push(res);
  }
  console.log(JSON.stringify(results, null, 2));
}

run();
