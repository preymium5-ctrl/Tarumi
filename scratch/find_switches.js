const fs = require('fs');

const content = fs.readFileSync('e:\\Tarumi 2\\recovered\\jadx\\sources\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\p065en\\Mangagg.java', 'utf8');
const lines = content.split('\n');

for (let i = 0; i < lines.length; i++) {
  if (lines[i].includes('switch (this.$r8$classId)')) {
    // search backwards for public/private/protected or return type
    let methodDecl = '';
    for (let j = i - 1; j >= Math.max(0, i - 10); j--) {
      if (lines[j].includes('public') || lines[j].includes('private') || lines[j].includes('protected') || lines[j].includes('override') || lines[j].includes('Object')) {
        methodDecl = lines[j].trim() + '\n' + methodDecl;
      }
    }
    console.log(`--- Line ${i + 1} ---`);
    console.log(methodDecl);
    // print 10 lines of the switch block
    for (let k = i; k < Math.min(lines.length, i + 25); k++) {
      console.log(`  ${k + 1}: ${lines[k]}`);
    }
  }
}
