const fs = require('fs');
const html = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_chapter.html', 'utf8');

// Find all matches for class="imgholder"
const imgRegex = /<img[^>]+class="imgholder"[^>]*>/g;
let match;
while ((match = imgRegex.exec(html)) !== null) {
  const imgTag = match[0];
  const index = match.index;
  // Let's look backward for the parent tag
  const before = html.substring(Math.max(0, index - 200), index);
  console.log(`Tag: ${imgTag}`);
  console.log(`Before: ${before.substring(before.length - 150)}`);
  console.log('--------------------------------------------------');
}
