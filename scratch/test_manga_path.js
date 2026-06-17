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
        resolve({ status: res.statusCode, body });
      });
    });
    req.on('error', (e) => {
      resolve({ status: 500, error: e.message });
    });
    req.end();
  });
}

async function run() {
  const mangaPage = await getHTML('https://hentaimanga.io/manga/');
  console.log('Manga Directory Status:', mangaPage.status);
  console.log('Manga Directory Length:', mangaPage.body.length);
  console.log('Contains wp-manga:', mangaPage.body.includes('wp-manga'));
  console.log('Contains c-tabs-item:', mangaPage.body.includes('c-tabs-item'));
  console.log('Contains page-item-detail:', mangaPage.body.includes('page-item-detail'));
  
  // Let's write a snippet of the manga page to a scratch file to inspect it
  const fs = require('fs');
  fs.writeFileSync('scratch/manga_dir.html', mangaPage.body.substring(0, 10000));
}

run();
