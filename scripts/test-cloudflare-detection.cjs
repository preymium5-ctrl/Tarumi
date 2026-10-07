const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/kotlin/org/koitharu/kotatsu/core/network/webview/CloudFlareDetection.kt'), 'utf8');
const script = source.match(/CF_STATE_JS = """([\s\S]*?)"""/)[1];

function state({ title = '', text = '', children = [], nodes = [], bodyMissing = false } = {}) {
  return vm.runInNewContext(script, {
    document: { title, location: { href: 'https://fixture.invalid/' }, readyState: 'complete', body: bodyMissing ? null : { textContent: text, children }, querySelectorAll: () => nodes },
    window: { getComputedStyle: node => node.style },
  });
}

assert.equal(state({ text: '0' }), 'ok', 'WordPress text-only AJAX responses must finish verification');
assert.equal(state({ text: '{"items":[]}' }), 'ok', 'JSON responses must finish verification');
assert.equal(state({ children: [{}] }), 'ok');
assert.equal(state(), 'wait');
assert.equal(state({ bodyMissing: true }), 'wait');
assert.equal(state({ title: 'Just a moment...', text: 'Challenge' }), 'wait');
assert.equal(state({ title: 'Attention Required! | Cloudflare', text: 'Blocked' }), 'error');
const node = (style, width = 100, height = 30) => ({ style, getBoundingClientRect: () => ({ width, height }) });
assert.equal(state({ text: 'Loaded', nodes: [node({ display: 'none' })] }), 'ok');
assert.equal(state({ text: 'Loaded', nodes: [node({ visibility: 'hidden' })] }), 'ok');
assert.equal(state({ text: 'Loaded', nodes: [node({ opacity: '0' })] }), 'ok');
assert.equal(state({ text: 'Loaded', nodes: [node({}, 0, 0)] }), 'ok');
assert.equal(state({ text: 'Loaded', nodes: [node({})] }), 'wait');
console.log('Cloudflare detection: 12 regression cases passed.');
