const https = require('https');

const options = {
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  },
  timeout: 8000,
  rejectUnauthorized: false
};

const paths = [
  '/chaptered.php?manga=13181&chapter=61',
  '/chaptered.php?manga=11640&chapter=37',
  '/chaptered.php?manga=13035&chapter=42',
  '/chaptered.php?manga=11573&chapter=27',
  '/chaptered.php?manga=12009&chapter=94'
];

function checkPath(path) {
  return new Promise((resolve) => {
    https.get('https://demonicscans.org' + path, options, (res) => {
      console.log(`Path: ${path} -> Status: ${res.statusCode} | Location: ${res.headers.location || 'N/A'}`);
      resolve();
    });
  });
}

async function run() {
  for (const p of paths) {
    await checkPath(p);
  }
}
run();
