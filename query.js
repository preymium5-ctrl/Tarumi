const { DatabaseSync } = require('node:sqlite');
const db = new DatabaseSync('kotatsu-db');
console.log("FAVOURITES MANGA DETAILS:");
try {
    const rows = db.prepare("SELECT CAST(m.manga_id AS TEXT) as manga_id_str, m.title, m.url, m.source, m.state, m.author FROM manga m JOIN favourites f ON m.manga_id = f.manga_id").all();
    console.log(rows);
} catch (e) {
    console.error(e);
}
