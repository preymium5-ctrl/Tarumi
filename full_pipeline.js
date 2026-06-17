const fs = require('fs');
const https = require('https');
const { URL } = require('url');

process.on('uncaughtException', (err) => { /* silently handle */ });
process.on('unhandledRejection', (reason) => { /* silently handle */ });

// ---- Minimal DOM Mocks ----
class MockCanvas{constructor(w,h){this.width=w||300;this.height=h||150;this.style={};}getContext(type){if(type==='2d')return{fillRect:()=>{},clearRect:()=>{},getImageData:(x,y,w,h)=>({data:new Uint8ClampedArray(w*h*4),width:w,height:h}),putImageData:()=>{},createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4),width:w,height:h}),setTransform:()=>{},drawImage:()=>{},save:()=>{},fillText:()=>{},restore:()=>{},beginPath:()=>{},moveTo:()=>{},lineTo:()=>{},closePath:()=>{},stroke:()=>{},translate:()=>{},scale:()=>{},rotate:()=>{},arc:()=>{},fill:()=>{},measureText:t=>({width:t.length*7}),transform:()=>{},font:'',textBaseline:'',textAlign:'',fillStyle:'',strokeStyle:'',globalAlpha:1,globalCompositeOperation:'source-over',canvas:this};if(type==='webgl'||type==='webgl2')return{getExtension:()=>null,getParameter:p=>p===37446?'Intel':p===37445?'Google Inc.':'',getSupportedExtensions:()=>[]};return null;}toDataURL(){return'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==';}toBlob(cb){cb(new Blob());}setAttribute(){}getAttribute(){return null;}}
class MockImageData{constructor(w,h){if(w instanceof Uint8ClampedArray){this.data=w;this.width=h;this.height=arguments[2]||1;}else{this.width=w||1;this.height=h||1;this.data=new Uint8ClampedArray(this.width*this.height*4);}}}
global.ImageData=MockImageData;

const cfgContent='ZZYdbXagjEpeaRwTE56mTpBkKVnnIBmAB3gdwWXXjEM7ZqAcLgonw0ylNjY621zM0zefn1Qg_jIQEn0oAIFnaXeGk3K4XZgY6S1Ldadwahluywsju2Z_xXiMDsD2';
const metaMock={name:'cfg',content:cfgContent,getAttribute(a){return a==='name'?'cfg':a==='content'?cfgContent:null;}};
const documentMock={documentElement:{outerHTML:'<html></html>',innerText:'',style:{}},body:{innerText:'',appendChild:()=>{},removeChild:()=>{},style:{}},title:'',hidden:false,visibilityState:'visible',addEventListener:()=>{},removeEventListener:()=>{},getElementById:()=>null,querySelector:s=>s==='[disable-devtool-auto]'?null:s==='meta[name="cfg"]'?metaMock:null,querySelectorAll:s=>s==='meta'?[metaMock]:[],getElementsByTagName:t=>t==='meta'?[metaMock]:[],createElement:tag=>{if(tag==='canvas')return new MockCanvas();return{style:{},setAttribute:()=>{},getAttribute:()=>null,appendChild:()=>{},removeChild:()=>{},addEventListener:()=>{},removeEventListener:()=>{},classList:{add:()=>{},remove:()=>{},contains:()=>false},innerHTML:'',innerText:'',textContent:'',tagName:tag.toUpperCase()};},createTextNode:t=>({textContent:t}),cookie:''};

Object.defineProperty(global,'navigator',{value:{userAgent:'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',appCodeName:'Mozilla',platform:'Win32',language:'en-US',languages:['en-US','en'],cookieEnabled:true,hardwareConcurrency:8,maxTouchPoints:0,vendor:'Google Inc.',plugins:{length:0},mimeTypes:{length:0}},configurable:true,writable:true});
Object.defineProperty(global,'location',{value:{href:'https://comix.to/',protocol:'https:',hostname:'comix.to',host:'comix.to',origin:'https://comix.to',pathname:'/',search:'',hash:'',port:''},configurable:true,writable:true});
Object.defineProperty(global,'document',{value:documentMock,configurable:true,writable:true});
global.HTMLCanvasElement=MockCanvas;global.CanvasRenderingContext2D=function(){};global.OffscreenCanvasRenderingContext2D=function(){};global.OffscreenCanvas=MockCanvas;global.WebGLRenderingContext=function(){};global.WebGL2RenderingContext=function(){};global.createImageBitmap=async()=>({width:1,height:1,close:()=>{}});global.Document=function(){};global.open=()=>null;global.top=global;global.parent=global;global.innerWidth=1920;global.innerHeight=1080;global.screen={width:1920,height:1080,availWidth:1920,availHeight:1040,colorDepth:24,pixelDepth:24};global.devicePixelRatio=1;global.performance={now:()=>Date.now(),timing:{navigationStart:Date.now()}};global.requestAnimationFrame=cb=>setTimeout(cb,16);global.cancelAnimationFrame=id=>clearTimeout(id);global.MutationObserver=class{constructor(){}observe(){}disconnect(){}};global.ResizeObserver=class{constructor(){}observe(){}disconnect(){}};global.IntersectionObserver=class{constructor(){}observe(){}disconnect(){}};global.localStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{},clear:()=>{}};global.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{},clear:()=>{}};global.Blob=class{constructor(){this.size=0;this.type='';}};global.URL={createObjectURL:()=>'blob:null',revokeObjectURL:()=>{}};
global.window=new Proxy(global,{get(t,p){if(p==='document')return documentMock;return t[p];},set(t,p,v){t[p]=v;return true;}});
global.fetch=async()=>{throw new Error('fetch not available');};

// ---- Run secure.js ----
const code = fs.readFileSync('secure.js', 'utf8');
const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
const runFn = new Function('window', 'globalThis', 'document', cleanCode);
runFn(global.window, global.window, documentMock);

const vmObj = global.vmU_a0e368;
const signer = global.Ri[2]; // Correct signer!

// Helper for HTTPS requests
function httpsGet(url, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const req = https.request({
            hostname: parsed.hostname, port: 443,
            path: parsed.pathname + parsed.search,
            method: 'GET',
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36', ...headers }
        }, res => {
            let data = ''; res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ status: res.statusCode, body: data, headers: res.headers }));
        });
        req.on('error', reject); req.end();
    });
}

(async () => {
    // Step 1: Get the response handler (decryptor)
    console.log("=== CAPTURING RESPONSE HANDLER ===\n");
    
    let responseHandler = null;
    let requestHandler = null;
    
    // Enumerate ALL functions in vmObj and test each as installer
    const allFnKeys = Object.keys(vmObj).filter(k => typeof vmObj[k] === 'function');
    
    for (const key of allFnKeys) {
        try {
            let gotRes = false;
            let gotReq = false;
            let resFn = null;
            let reqFn = null;
            
            const fakeAxios = {
                interceptors: {
                    request: { use: function(fn) { gotReq = true; reqFn = fn; } },
                    response: { use: function(fn) { gotRes = true; resFn = fn; } }
                },
                defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
            };
            vmObj[key](fakeAxios);
            
            if (gotRes || gotReq) {
                console.log(`vmObj.${key}: request=${gotReq}, response=${gotRes}`);
                if (gotRes) responseHandler = resFn;
                if (gotReq) requestHandler = reqFn;
            }
        } catch(e) {
            // Not an installer
        }
    }
    
    // Also check Ri functions as potential installers
    for (let i = 0; i < global.Ri.length; i++) {
        try {
            let gotRes = false;
            let resFn = null;
            const fakeAxios = {
                interceptors: {
                    request: { use: function() {} },
                    response: { use: function(fn) { gotRes = true; resFn = fn; } }
                },
                defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
            };
            global.Ri[i](fakeAxios);
            if (gotRes) {
                console.log(`Ri[${i}] is an installer! Response handler captured.`);
                responseHandler = resFn;
            }
        } catch(e) {}
    }
    
    console.log(`\nResponse handler found: ${!!responseHandler}`);
    console.log(`Request handler found: ${!!requestHandler}`);
    
    // Step 2: Sign and fetch
    console.log("\n=== FETCHING SIGNED API ===\n");
    
    const apiPath = "/api/v1/manga/g2rk/chapters";
    const signable = "/manga/g2rk/chapters";
    const token = signer(signable);
    const signedUrl = `https://comix.to${apiPath}?_=${encodeURIComponent(token)}`;
    
    console.log(`Signed URL: ${signedUrl}`);
    
    const resp = await httpsGet(signedUrl);
    console.log(`Status: ${resp.status}`);
    
    if (resp.status !== 200) {
        console.log(`Body: ${resp.body}`);
        process.exit(1);
    }
    
    const json = JSON.parse(resp.body);
    console.log(`Response keys: ${Object.keys(json).join(', ')}`);
    console.log(`Encrypted 'e' length: ${json.e ? json.e.length : 'N/A'}`);
    
    // Step 3: Decrypt
    if (json.e && responseHandler) {
        console.log("\n=== DECRYPTING RESPONSE ===\n");
        
        const fakeResp = {
            data: json,
            status: 200,
            statusText: 'OK',
            headers: Object.fromEntries(Object.entries(resp.headers)),
            config: { url: signedUrl, method: 'get', baseURL: '/api/v1' },
            request: {}
        };
        
        try {
            const decoded = await responseHandler(fakeResp);
            console.log("Decrypted type:", typeof decoded);
            
            if (decoded && decoded.data) {
                const dataStr = JSON.stringify(decoded.data);
                console.log(`\nDecrypted data length: ${dataStr.length}`);
                console.log(`\nDecrypted data (first 2000 chars):\n${dataStr.substring(0, 2000)}`);
            } else {
                console.log("Decoded:", JSON.stringify(decoded).substring(0, 1000));
            }
        } catch(e) {
            console.log("Decryption error:", e.message);
            console.log(e.stack);
        }
    } else if (!responseHandler) {
        console.log("\n⚠️ No response handler - cannot decrypt");
        console.log("Encrypted body (first 500):", json.e.substring(0, 500));
    }
    
    // Also test a different endpoint
    console.log("\n\n=== TESTING MANGA LIST ENDPOINT ===\n");
    const listPath = "/manga";
    const listToken = signer(listPath);
    const listUrl = `https://comix.to/api/v1${listPath}?_=${encodeURIComponent(listToken)}&page=1`;
    console.log(`URL: ${listUrl}`);
    
    const listResp = await httpsGet(listUrl);
    console.log(`Status: ${listResp.status}`);
    console.log(`Body (first 300): ${listResp.body.substring(0, 300)}`);
    
    console.log("\n=== COMPLETE ===");
    process.exit(0);
})().catch(e => { console.error("Fatal:", e); process.exit(1); });
