const fs = require('fs');

const results = JSON.parse(fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/probe_results.json', 'utf8'));

console.log('Total results:', results.length);

const working = [];
const redirected = [];
const failed = [];

results.forEach(r => {
  if (r.status === 200) {
    working.push(r);
  } else if (r.status === 301 || r.status === 302 || r.status === 307 || r.status === 308) {
    if (r.redirect) {
      try {
        const origHost = r.domain;
        const newHost = new URL(r.redirect).host;
        if (origHost.replace('www.', '') !== newHost.replace('www.', '')) {
          redirected.push({ ...r, newHost });
        } else {
          working.push(r);
        }
      } catch (e) {
        working.push(r);
      }
    } else {
      working.push(r);
    }
  } else {
    failed.push(r);
  }
});

console.log('====================================');
console.log('WORKING SOURCES:', working.length);
console.log('====================================');

console.log('\n====================================');
console.log('REDIRECTED TO NEW DOMAIN:', redirected.length);
console.log('====================================');
redirected.forEach(r => {
  console.log(`Source: ${r.name} (${r.id})`);
  console.log(`  Old domain: ${r.domain}`);
  console.log(`  New domain: ${r.newHost}`);
  console.log(`  File: ${r.file}`);
  console.log('------------------------------------');
});

console.log('\n====================================');
console.log('FAILED SOURCES (Timeout, 404, DNS error, etc):', failed.length);
console.log('====================================');
failed.forEach(r => {
  console.log(`Source: ${r.name} (${r.id})`);
  console.log(`  Domain: ${r.domain}`);
  console.log(`  Status: ${r.status}`);
  if (r.error) console.log(`  Error: ${r.error}`);
  console.log(`  File: ${r.file}`);
  console.log('------------------------------------');
});
