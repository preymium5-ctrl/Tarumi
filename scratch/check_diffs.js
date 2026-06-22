const fs = require('fs');

function check(filename) {
    console.log(`=== ${filename} ===`);
    if (!fs.existsSync(filename)) {
        console.log("Not found");
        return;
    }
    const content = fs.readFileSync(filename, 'utf8');
    const lines = content.split('\n');
    console.log("Total lines:", lines.length);
    // Find git diff lines
    lines.forEach((line, idx) => {
        if (line.startsWith("diff --git")) {
            console.log(`Line ${idx}: ${line}`);
        }
    });
}

check('e:/Tarumi 2/kotatsu-parsers-redo/last_commit_utf8.diff');
check('e:/Tarumi 2/kotatsu-parsers-redo/branch_diff_utf8.diff');
