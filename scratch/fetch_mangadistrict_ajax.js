const https = require('https');
const fs = require('fs');

const postData = 'action=madara_load_more&page=0&template=madara-core%2Fcontent%2Fcontent-search&vars%5Bs%5D=&vars%5Bpaged%5D=1&vars%5Btemplate%5D=search&vars%5Bmeta_query%5D%5B0%5D%5Brelation%5D=AND&vars%5Bmeta_query%5D%5Brelation%5D=AND&vars%5Bpost_type%5D=wp-manga&vars%5Bpost_status%5D=publish&vars%5Bmanga_archives_item_layout%5D=default';

const options = {
  hostname: 'mangadistrict.com',
  port: 443,
  path: '/wp-admin/admin-ajax.php',
  method: 'POST',
  headers: {
    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Content-Length': Buffer.byteLength(postData)
  }
};

const req = https.request(options, (res) => {
  console.log('Status Code:', res.statusCode);
  let data = '';
  res.on('data', (chunk) => {
    data += chunk;
  });
  res.on('end', () => {
    fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/ajax_response.html', data);
    console.log('Response length:', data.length);
    console.log('Sample response:', data.substring(0, 1000));
  });
});

req.on('error', (e) => {
  console.error(e);
});

req.write(postData);
req.end();
