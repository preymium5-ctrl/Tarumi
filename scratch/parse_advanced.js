const fs = require('fs');
const html = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_advanced.html', 'utf8');
const lines = html.split('\n');
for (let i = 336; i < 355; i++) {
  console.log(`${i+1}: ${lines[i]}`);
}
