const fs = require('fs');
const html = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_manga.html', 'utf8');
const lines = html.split('\n');
for (let i = 419; i < 480; i++) {
  console.log(`${i+1}: ${lines[i]}`);
}
