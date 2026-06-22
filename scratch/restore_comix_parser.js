const fs = require('fs');
const path = require('path');

const logPath = 'C:/Users/Rockz/.gemini/antigravity-ide/brain/bc79b9e0-c060-4689-b2e0-d71fc6b6497e/.system_generated/logs/transcript.jsonl';

if (!fs.existsSync(logPath)) {
    console.error("Log file does not exist:", logPath);
    process.exit(1);
}

const lines = fs.readFileSync(logPath, 'utf8').split('\n');
let part1 = null;
let part2 = null;

for (const line of lines) {
    if (!line.trim()) continue;
    try {
        const obj = JSON.parse(line);
        // Look for tool output of view_file tool on ComixParser.kt
        if (obj.tool_calls) {
            // Check if it's the view_file tool call and check content
        }
        if (obj.type === 'VIEW_FILE' && obj.status === 'DONE' && obj.content) {
            const content = obj.content;
            if (content.includes("File Path: `file:///e:/Tarumi%202/kotatsu-parsers-redo/src/main/kotlin/org/koitharu/kotatsu/parsers/site/en/ComixParser.kt`")) {
                if (content.includes("Showing lines 1 to 800")) {
                    part1 = content;
                } else if (content.includes("Showing lines 801 to 1244")) {
                    part2 = content;
                }
            }
        }
    } catch (e) {
        // Skip malformed lines
    }
}

if (!part1) {
    console.error("Could not find part 1 of ComixParser.kt in logs!");
}
if (!part2) {
    console.error("Could not find part 2 of ComixParser.kt in logs!");
}

if (part1 && part2) {
    // Parse the lines from part1 and part2
    const parseLines = (partContent) => {
        const fileLines = [];
        const linesArr = partContent.split('\n');
        for (const line of linesArr) {
            const match = line.match(/^(\d+):\s(.*)$/);
            if (match) {
                fileLines.push(match[2]);
            } else if (line.match(/^(\d+):$/)) {
                fileLines.push("");
            }
        }
        return fileLines;
    };

    const lines1 = parseLines(part1);
    const lines2 = parseLines(part2);
    const allLines = [...lines1, ...lines2];

    console.log(`Reconstructed lines: ${allLines.length} (expected 1244)`);

    const targetPath = 'e:/Tarumi 2/kotatsu-parsers-redo/src/main/kotlin/org/koitharu/kotatsu/parsers/site/en/ComixParser.kt';
    fs.writeFileSync(targetPath, allLines.join('\n'), 'utf8');
    console.log("Successfully restored ComixParser.kt!");
}
