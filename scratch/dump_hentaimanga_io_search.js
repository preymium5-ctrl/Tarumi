const https = require('https');

function getHTML(url) {
  return new Promise((resolve) => {
    const options = {
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
      }
    };
    const req = https.request(url, options, (res) => {
      let body = '';
      res.on('data', (c) => body += c);
      res.on('end', () => {
        resolve(body);
      });
    });
    req.on('error', (e) => {
      resolve('');
    });
    req.end();
  });
}

async function run() {
  const html = await getHTML('https://hentaimanga.io/?s=teacher&post_type=wp-manga');
  console.log('HTML Length:', html.length);
  
  // Find all divs or elements that might be manga items
  const matches = html.match(/<div class="[^"]*(?:manga|entry|post|card|item)[^"]*"[^>]*>/g) || [];
  console.log('Manga/Item/Card Div classes:');
  console.log(matches.slice(0, 40));
}

run();
