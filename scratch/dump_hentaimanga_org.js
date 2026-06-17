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
  const html = await getHTML('https://www.hentaimanga.org/');
  console.log('HTML Length:', html.length);
  const regex = /href="([^"]+)"/g;
  let match;
  const links = new Set();
  while ((match = regex.exec(html)) !== null) {
    links.add(match[1]);
  }
  console.log('--- ALL LINKS ---');
  for (const link of links) {
    console.log(link);
  }
}

run();
