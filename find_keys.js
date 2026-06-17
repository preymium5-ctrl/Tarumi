const fs = require('fs');

// Redefine read-only navigator in Node
Object.defineProperty(global, 'navigator', {
    value: {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        appCodeName: 'Mozilla'
    },
    configurable: true,
    writable: true
});

Object.defineProperty(global, 'location', {
    value: {
        href: 'https://comix.to/',
        protocol: 'https:',
        hostname: 'comix.to',
        pathname: '/'
    },
    configurable: true,
    writable: true
});

// Mock ImageData
global.ImageData = class ImageData {
    constructor(width, height) {
        this.width = width;
        this.height = height;
        this.data = new Uint8ClampedArray(width * height * 4);
    }
};

// Override global.fetch to handle relative URLs
const originalFetch = global.fetch;
global.fetch = (url, options) => {
    let targetUrl = url;
    if (typeof url === 'string' && url.startsWith('/')) {
        targetUrl = 'https://comix.to' + url;
    }
    console.log(`[Fetch] ${url} -> ${targetUrl}`);
    return originalFetch(targetUrl, options).catch(err => {
        console.log(`[Fetch Failed] ${url}:`, err.message);
        throw err;
    });
};

const mockMeta = {
    name: 'cfg',
    getAttribute: (name) => {
        if (name === 'content') {
            return 'ZZYdbXagjEpeaRwTE56mTpBkKVnnIBmAB3gdwWXXjEM7ZqAcLgonw0ylNjY621zM0zefn1Qg_jIQEn0oAIFnaXeGk3K4XZgY6S1Ldadwahluywsju2Z_xXiMDsD2';
        }
        return null;
    }
};

const mockElement = {
    style: {},
    setAttribute: () => {},
    getAttribute: () => null,
    appendChild: (child) => child,
    removeChild: () => {},
    insertBefore: () => {},
    innerHTML: '',
    innerText: '',
    textContent: '',
    className: '',
    tagName: 'DIV',
    id: '',
    children: [],
    childNodes: [],
    addEventListener: () => {},
    removeEventListener: () => {},
    getContext: () => ({
        getImageData: () => new global.ImageData(1, 1),
        putImageData: () => {},
        createImageData: () => new global.ImageData(1, 1),
        drawImage: () => {},
        fillRect: () => {},
    }),
};

const mockDocument = {
    documentElement: mockElement,
    body: mockElement,
    head: mockElement,
    title: 'Comix',
    addEventListener: () => {},
    removeEventListener: () => {},
    getElementById: () => null,
    querySelector: (sel) => {
        if (sel === 'meta[name="cfg"]') return mockMeta;
        return mockMeta;
    },
    querySelectorAll: () => [mockMeta],
    getElementsByTagName: (tag) => {
        if (tag === 'meta') return [mockMeta];
        return [mockElement];
    },
    createElement: (tag) => {
        return { ...mockElement, tagName: tag.toUpperCase() };
    },
    createDocumentFragment: () => ({
        appendChild: () => {},
        children: []
    }),
    createTextNode: () => ({})
};

const globalProxy = new Proxy(global, {
    get(target, prop) {
        if (prop === 'document') return mockDocument;
        if (prop === 'location') return global.location;
        if (prop === 'window') return globalProxy;
        if (prop === 'globalThis') return globalProxy;
        if (prop === 'top') return globalProxy;
        if (prop === 'parent') return globalProxy;
        return target[prop];
    }
});

global.window = globalProxy;

async function runTests() {
    try {
        const code = fs.readFileSync('secure.js', 'utf8');
        const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
        
        const runFn = new Function('window', 'globalThis', 'document', cleanCode);
        runFn(globalProxy, globalProxy, mockDocument);
        
        const vmKeys = Object.keys(global).filter(k => k.startsWith('vm'));
        console.log("Successfully ran secure.js. vmKeys:", vmKeys);
        
        if (vmKeys.length > 0) {
            const vmObj = global[vmKeys[0]];
            const probePath = "/manga/g2rk/chapters";
            console.log(`\nTesting all functions on ${vmKeys[0]} with path "${probePath}":`);
            
            for (const key of Object.keys(vmObj)) {
                const value = vmObj[key];
                if (typeof value === 'function') {
                    try {
                        const result = value(probePath);
                        if (result instanceof Promise) {
                            console.log(`  vmObj.${key}("${probePath}") -> returned Promise`);
                            const resolved = await result;
                            console.log(`    vmObj.${key} resolved -> type: ${typeof resolved}, value: ${resolved}`);
                        } else if (result !== undefined) {
                            console.log(`  vmObj.${key}("${probePath}") -> type: ${typeof result}, value: ${result}`);
                        }
                    } catch (e) {
                        console.log(`  vmObj.${key} failed:`, e.message);
                    }
                }
            }
        }
        
    } catch (err) {
        console.error("Execution failed:", err.message);
        console.error(err.stack);
    }
}

setTimeout(runTests, 1000);
