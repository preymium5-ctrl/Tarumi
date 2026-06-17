const https = require('https');
const querystring = require('querystring');

const postData = querystring.stringify({
  action: 'madara_load_more',
  page: '0',
  template: 'madara-core/content/content-search',
  'vars[s]': '',
  'vars[paged]': '1',
  'vars[template]': 'search',
  'vars[meta_query][0][relation]': 'AND',
  'vars[meta_query][relation]': 'AND',
  'vars[post_type]': 'wp-manga',
  'vars[post_status]': 'publish',
  'vars[manga_archives_item_layout]': 'default'
});

const options = {
  hostname: 'allporncomic.com',
  port: 443,
  path: '/wp-admin/admin-ajax.php',
  method: 'POST',
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
    'Accept': '*/*',
    'Accept-Language': 'en-US,en;q=0.5',
    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
    'Content-Length': Buffer.byteLength(postData),
    'Origin': 'https://allporncomic.com',
    'Referer': 'https://allporncomic.com/porncomic/',
    'Connection': 'keep-alive',
  }
};

const req = https.request(options, (res) => {
  console.log(`STATUS: ${res.statusCode}`);
  console.log('HEADERS:');
  console.log(JSON.stringify(res.headers, null, 2));

  let body = '';
  res.on('data', (chunk) => {
    body += chunk;
  });

  res.on('end', () => {
    console.log(`BODY LENGTH: ${body.length}`);
    console.log(`BODY PREFIX: ${body.substring(0, 500)}`);
  });
});

req.on('error', (e) => {
  console.error(`problem with request: ${e.message}`);
});

req.write(postData);
req.end();
