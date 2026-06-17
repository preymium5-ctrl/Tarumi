const fs = require('fs');
const html = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_chapter.html', 'utf8');

// Find all parent nodes of <img class="imgholder" ...>
// Let's use simple regex search. We want to see the path from body to the images.
// Let's trace the tag hierarchy.
// Let's write a simple HTML tokenizer/parser in JS.
const parseHTML = (html) => {
  const stack = [];
  const imgParents = [];
  const tagRegex = /<\/?([a-zA-Z0-9:-]+)[^>]*>|([^<]+)/g;
  let match;
  while ((match = tagRegex.exec(html)) !== null) {
    const tag = match[0];
    if (tag.startsWith('</')) {
      const tagName = match[1];
      if (stack.length > 0 && stack[stack.length - 1] === tagName) {
        stack.pop();
      }
    } else if (tag.startsWith('<') && !tag.startsWith('<!--') && !tag.endsWith('/>')) {
      const tagName = match[1];
      if (tagName) {
        stack.push(tagName);
        if (tagName.toLowerCase() === 'img') {
          if (tag.includes('imgholder')) {
            imgParents.push({ tag, path: stack.join(' > ') });
          }
          stack.pop(); // self-closing or empty
        }
      }
    }
  }
  return imgParents;
};

const parents = parseHTML(html);
parents.forEach((p, i) => {
  console.log(`${i}: Tag=${p.tag} Path=${p.path}`);
});
