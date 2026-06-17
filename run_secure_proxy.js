const fs = require('fs');

// Intercept uncaught exceptions/rejections to prevent crashes
process.on('uncaughtException', (err) => {
    console.log("[Uncaught Exception Logged]:", err.message);
});
process.on('unhandledRejection', (reason) => {
    console.log("[Unhandled Rejection Logged]:", reason.message || reason);
});

// Redefine read-only navigator in Node
Object.defineProperty(global, 'navigator', {
    value: {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        appCodeName: 'Mozilla'
    },
    configurable: true,
    writable: true
});

global.addEventListener = () => {};
global.removeEventListener = () => {};
global.alert = () => {};
global.confirm = () => {};
global.prompt = () => {};

// Mock ImageData
global.ImageData = class ImageData {
    constructor(width, height) {
        this.width = width;
        this.height = height;
        this.data = new Uint8ClampedArray(width * height * 4);
    }
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
    querySelector: () => mockMeta,
    querySelectorAll: () => [mockMeta],
    getElementsByTagName: (tag) => {
        if (tag === 'meta') {
            return [mockMeta];
        }
        return [mockElement];
    },
    createElement: (tag) => {
        // console.log(`[Mock Document] createElement("${tag}")`);
        return { ...mockElement, tagName: tag.toUpperCase() };
    },
    createDocumentFragment: () => ({
        appendChild: () => {},
        children: []
    }),
    createTextNode: () => ({})
};

// Override global.fetch to handle relative URLs
const originalFetch = global.fetch;
global.fetch = (url, options) => {
    let targetUrl = url;
    if (typeof url === 'string' && url.startsWith('/')) {
        targetUrl = 'https://comix.to' + url;
    }
    // console.log(`[Fetch intercepted] ${url} -> ${targetUrl}`);
    return originalFetch(targetUrl, options).catch(err => {
        console.log(`[Fetch Failed] ${url}:`, err.message);
        throw err;
    });
};

// We will use a Proxy to catch all accesses
const accessed = new Set();
const globalProxy = new Proxy(global, {
    get(target, prop) {
        const propStr = String(prop);
        if (!accessed.has(propStr)) {
            accessed.add(propStr);
            // console.log(`[Proxy GET] window.${propStr}`);
        }
        
        if (prop === 'document') return mockDocument;
        if (prop === 'location') return { href: 'https://comix.to/', protocol: 'https:', hostname: 'comix.to', pathname: '/' };
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
        
        // Wrap the clean code and pass globalProxy as 'window'
        const runFn = new Function('window', 'globalThis', 'document', cleanCode);
        runFn(globalProxy, globalProxy, mockDocument);
        
        // Find keys on window/global starting with vm
        const vmKeys = Object.keys(global).filter(k => k.startsWith('vm'));
        console.log("Successfully ran! vmKeys:", vmKeys);
        
        if (vmKeys.length > 0) {
            const vmObj = global[vmKeys[0]];
            console.log("Analyzing object keys of", vmKeys[0]);
            const probePath = "/manga/g2rk/chapters";
            console.log(`\nTesting functions on ${vmKeys[0]} with path "${probePath}":`);
            
            for (const key of Object.keys(vmObj)) {
                const value = vmObj[key];
                if (typeof value === 'function') {
                    try {
                        const result = value(probePath);
                        if (result instanceof Promise) {
                            console.log(`  vmObj.${key}("${probePath}") -> returned Promise`);
                            const resolved = await result;
                            console.log(`    vmObj.${key} resolved -> type: ${typeof resolved}, value: ${resolved}`);
                        } else if (result) {
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

// Run tests in 1 second to let any async initializations settle
setTimeout(runTests, 1000);
