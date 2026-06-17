const fs = require('fs');

const files = [
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\en\\FreeComicOnline.kt',
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\en\\HentaiManga.kt',
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\en\\HentaiWebtoon.kt',
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\en\\ManhwaHentai.kt',
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\en\\ManyComic.kt',
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\en\\ManyToonMe.kt',
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\madara\\en\\AdultWebtoon.kt',
  'e:\\Tarumi 2\\kotatsu-parsers-redo\\src\\main\\kotlin\\org\\koitharu\\kotatsu\\parsers\\site\\mangareader\\en\\EDoujin.kt'
];

files.forEach(f => {
  if (fs.existsSync(f)) {
    const content = fs.readFileSync(f, 'utf8');
    const isBroken = content.includes('@Broken');
    console.log(`${f.split('\\').pop()}: isBroken = ${isBroken}`);
  } else {
    console.log(`File not found: ${f}`);
  }
});
