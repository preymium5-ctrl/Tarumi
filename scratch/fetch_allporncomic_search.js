const https = require('https');

const options = {
  hostname: 'allporncomic.com',
  port: 443,
  path: '/?s=porn&post_type=wp-manga',
  method: 'GET',
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
    'Connection': 'keep-alive',
  }
};

const req = https.request(options, (res) => {
  console.log(`STATUS: ${res.statusCode}`);
  let body = '';
  res.on('data', (chunk) => {
    body += chunk;
  });

  res.on('end', () => {
    console.log(`BODY LENGTH: ${body.length}`);
    const hasSearchPage = body.includes('page-item-detail') || body.includes('c-tabs-item__content') || body.includes('manga');
    console.log(`CONTAINS MANGA ELEMENTS: ${hasSearchPage}`);
    console.log(`BODY SNIPPET: ${body.substring(body.indexOf('page-item-detail') - 100, body.indexOf('page-item-detail') + 500)}`);
  });
});

req.on('error', (e) => {
  console.error(`problem with request: ${e.message}`);
});

req.end();
