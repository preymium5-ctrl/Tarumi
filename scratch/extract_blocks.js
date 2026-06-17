const fs = require('fs');

const data = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/manga_dir.html', 'utf8');

// Find occurrences of item-summary and print 200 characters before and after it
let index = 0;
let count = 0;
while ((index = data.indexOf('class="item-summary"', index)) !== -1 && count < 5) {
  console.log(`OCCURRENCE ${count}:`);
  const start = Math.max(0, index - 200);
  const end = Math.min(data.length, index + 300);
  console.log(data.substring(start, end));
  console.log('====================================================');
  index += 'class="item-summary"'.length;
  count++;
}
