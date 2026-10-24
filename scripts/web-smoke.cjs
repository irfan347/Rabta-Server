// Run with NODE_PATH pointing to an existing Playwright installation. Uses local disposable fixtures only.
const {chromium}=require('playwright');
const express=require('express');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const mount=require('../web-http');
(async()=>{
 const app=express();app.use(express.json());
 // Hold the first session lookup until the browser proves linking is visibly disabled.
 let releaseState;const firstState=new Promise(resolve=>{releaseState=resolve;});let stateHeld=false;
 app.use('/web/api/state',async(req,res,next)=>{if(!stateHeld){stateHeld=true;await firstState;}next();});const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});
 const origin='http://localhost:'+server.address().port;
 const records=[{id:'ABCDEF000001',text:'Hey! Good to see you here 👋',mine:false,at:Date.now(),status:'received'}];
 let relay;const phone={readyState:1,rabtaWebReady:true,send(raw){const c=JSON.parse(raw);let data={};
  if(c.op==='chats')data={chats:[{id:'ABCDEF123456',name:'Ayesha · Fixture',unread:1},{id:'ABCDEF654321',name:'Weekend plans',unread:0}]};
  if(c.op==='messages')data={messages:records};
  if(c.op==='send'){if(!records.some(m=>m.id===c.messageId))records.push({id:c.messageId,text:c.text,mine:true,at:Date.now(),status:'queued'});data={queued:true,messageId:c.messageId};}
  setTimeout(()=>relay.reply('fixture','phone',{requestId:c.requestId,ok:true,data}),10);
 }};
 relay=mount({app,origin,authPhone:req=>req.headers.authorization==='Bearer fixture'?{id:'fixture',device:'phone'}:null,deviceAllowed:()=>true,phoneSocket:()=>phone});
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome-stable',headless:true,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:1440,height:950}});const errors=[];page.on('pageerror',e=>errors.push(String(e)));
  await page.goto(origin+'/web/');
  const pairButton=page.getByRole('button',{name:'Create a linking code'});
  assert.equal(await pairButton.isDisabled(),true,'Pending session lookup must not silently swallow a linking click');
  releaseState();await pairButton.click();
  await page.locator('#qr').waitFor({state:'visible'});
  const dir=process.env.RABTA_SCREENSHOTS||'/tmp/rabta-web-screenshots';fs.mkdirSync(dir,{recursive:true});
  await page.screenshot({path:dir+'/pairing-desktop.png',fullPage:true});
  const code=await page.locator('#code').textContent();
  const approval=await fetch(origin+'/api/web-links/approve',{method:'POST',headers:{Authorization:'Bearer fixture','Content-Type':'application/json'},body:JSON.stringify({code})});assert.equal(approval.status,200);
  await page.getByRole('button',{name:'Ayesha · Fixture',exact:false}).waitFor();
  // Polling briefly disables actions while the phone request is in progress.
  await page.waitForFunction(()=>document.getElementById('connection').textContent==='Phone connected');
  await page.waitForTimeout(200);
  await page.getByRole('button',{name:'Ayesha · Fixture',exact:false}).click();
  await page.getByText('Hey! Good to see you here 👋',{exact:true}).waitFor();
  await page.locator('#text').fill('😀'.repeat(4000));
  await page.waitForFunction(()=>!document.getElementById('send').disabled);await page.locator('#send').click();
  await page.getByText('This message is too long. Shorten it and try again.',{exact:true}).waitFor();
  assert.equal(await page.locator('#text').evaluate(el=>el.readOnly),false);
  await page.locator('#text').fill('<script>window.bad=true</script> Hello from the browser!');
  await page.waitForFunction(()=>!document.getElementById('send').disabled);
  await page.locator('#send').click();
  await page.getByText('<script>window.bad=true</script> Hello from the browser!',{exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>window.bad),undefined);
  assert.equal(records.length,2);
  await page.screenshot({path:dir+'/chat-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.screenshot({path:dir+'/chat-mobile.png',fullPage:true});
  await page.setViewportSize({width:320,height:700});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Narrow phone chat must not overflow');
  await page.setViewportSize({width:390,height:844});
  phone.readyState=3;
  await page.getByText('Phone disconnected',{exact:true}).waitFor();
  assert.equal(await page.locator('.bubble').count(),0);
  assert.equal(await page.locator('#send').isDisabled(),true);
  phone.readyState=1;
  await page.getByText('Phone connected',{exact:true}).waitFor();
  await page.locator('#back').click();await page.waitForTimeout(200);
  await page.screenshot({path:dir+'/chats-mobile.png',fullPage:true});
  await page.getByRole('button',{name:'Unlink',exact:true}).click();
  await page.locator('#pair').waitFor({state:'visible'});
  assert.equal(await page.locator('.bubble').count(),0);assert.deepEqual(errors,[]);
  console.log('PASS: QR pairing, approval, history, text send, XSS rendering, mobile layout, phone disconnect/reconnect and unlink');
 } finally {await browser.close();server.closeAllConnections();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
