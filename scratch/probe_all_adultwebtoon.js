const https = require('https');

const tlds = [
  'com', 'net', 'org', 'me', 'biz', 'info', 'xyz', 'top', 'site', 'online', 'club', 'cc', 'us', 'vip', 'to', 'co', 'tv', 'io',
  'app', 'blog', 'icu', 'ink', 'link', 'live', 'mobi', 'one', 'pro', 'pub', 'space', 'tech', 'today', 'website', 'work',
  'best', 'fit', 'fun', 'host', 'ltd', 'press', 'run', 'shop', 'store', 'stream', 'studio', 'style', 'systems', 'trade',
  'uno', 'web', 'wiki', 'win', 'world', 'zone', 'asia', 'eu', 'uk', 'ru', 'fr', 'de', 'nl', 'se', 'it', 'es', 'ch', 'pl'
];

function check(domain) {
  return new Promise((resolve) => {
    const options = {
      hostname: domain,
      port: 443,
      path: '/',
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
      },
      timeout: 2500
    };
    const req = https.request(options, (res) => {
      console.log(`[FOUND] ${domain} -> STATUS: ${res.statusCode} | LOCATION: ${res.headers.location || 'N/A'}`);
      let body = '';
      res.on('data', (c) => body += c);
      res.on('end', () => {
        const isMadara = body.includes('wp-manga') || body.includes('c-tabs-item') || body.includes('page-item-detail');
        console.log(`[FOUND] ${domain} -> BODY LENGTH: ${body.length} | isMadara: ${isMadara}`);
        resolve();
      });
    });
    req.on('error', (e) => {
      // ignore normal connection errors
      resolve();
    });
    req.end();
  });
}

async function run() {
  console.log('Starting probe of all TLDs for adultwebtoon...');
  const promises = tlds.map(tld => check(`adultwebtoon.${tld}`));
  await Promise.all(promises);
  console.log('Finished probing.');
}
run();
