import {test,expect} from './fixtures/studyroom';
import {resourcePage,knowledgePoint,notes} from './fixtures/classroom-navigation';
async function openQuestion(page:import('@playwright/test').Page){const inside=page.locator('.course-notes').getByRole('button',{name:'课堂提问',exact:true});if(await inside.isVisible())await inside.click();else await page.getByRole('button',{name:'对这里提问 ↗',exact:true}).click();}
async function classroom(page:import('@playwright/test').Page,label:string) {
 await page.goto('/studyroom');await page.getByLabel('课程问题',{exact:true}).fill(`栈的定义和C代码阅读，${label}`);
 await page.getByRole('button',{name:'生成大纲',exact:true}).click();
 await expect(page.getByLabel('课堂生成进度')).toBeVisible();
 await expect(page.locator('.paper-point').first()).toBeVisible();
 await page.getByRole('button',{name:'审阅大纲',exact:true}).click();
 await expect(page.getByRole('button',{name:'确认大纲，开始学习'})).toBeEnabled();
 await page.getByRole('button',{name:'确认大纲，开始学习'}).click();
 await expect(page).toHaveURL(/\/studyroom\/[a-f0-9-]+$/);
}
test('streams a contextual question and resumes the retained code draft; checks are server graded',async({page})=>{
 await classroom(page,'课堂问答回归');
 await resourcePage(page,/栈的 C 代码实现/);
 await expect(page.getByLabel('C 代码讲解')).toBeVisible();
 await page.getByRole('button',{name:'打开代码实验',exact:true}).click();
 const editor=page.getByLabel('C 代码编辑器');await editor.fill('// local draft must stay here');await page.getByLabel('标准输入',{exact:true}).fill('11 22');
 await openQuestion(page);
 await page.getByLabel('课程问题',{exact:true}).fill('为什么最后入栈的元素先出栈？');
 const posted=page.waitForRequest(request=>request.method()==='POST'&&/\/messages$/.test(request.url()));
 await page.getByRole('button',{name:'发送追问 ↗',exact:true}).click();
 const body=(await posted).postDataJSON();expect(body.fragmentId).toBeTruthy();expect(body.contentRevision).toBeGreaterThan(0);expect(JSON.stringify(body)).not.toContain('local draft');
 await expect(page.getByLabel('课堂对话')).toContainText('结合当前资料');
 await page.getByLabel('课程问题',{exact:true}).fill('取消回归：再解释一次');
 const cancellation=page.waitForResponse(response=>response.request().method()==='POST'&&/\/messages$/.test(response.url()));
 await page.getByRole('button',{name:'发送追问 ↗',exact:true}).click();const queued=await (await cancellation).json();
 await page.getByRole('button',{name:'停止回答',exact:true}).click();await expect(page.getByText('已停止回答',{exact:true})).toBeVisible();
 const cancelled=await page.request.get(`/api/v1/study-rooms/${page.url().split('/').at(-1)}/messages/${queued.questionId}`);expect((await cancelled.json()).status).toBe('CANCELLED');
 await page.getByRole('button',{name:'返回刚才的位置，继续学习 →',exact:true}).click();
 await expect(editor).toHaveValue('// local draft must stay here');await expect(page.getByLabel('标准输入',{exact:true})).toHaveValue('11 22');
 await resourcePage(page,/小测/);
 const check=page.getByLabel('知识点小测').filter({visible:true});await expect.poll(async()=>await check.locator('form').count()>0||await check.getByRole('button',{name:'重新准备小测',exact:true}).isVisible()).toBe(true);if(await check.getByRole('button',{name:'重新准备小测',exact:true}).isVisible())await check.getByRole('button',{name:'重新准备小测',exact:true}).click();await expect(check.locator('form')).toHaveCount(1);await expect(check.getByRole('radio',{name:'2',exact:true})).toBeVisible();
 await expect(check).not.toContainText('后进入的元素先离开栈。');
 await check.getByRole('radio',{name:'2',exact:true}).check();await check.getByRole('button',{name:'提交答案',exact:true}).first().click();
 await expect(check).toContainText('理解正确');await expect(check).toContainText('后进入的元素先离开栈。');
 await expect(page.locator('.classroom-progress')).toContainText('/ 2 个知识点');
 await page.screenshot({path:'test-results/classroom-desktop.png',fullPage:true});
});
test('reloading a live answer resumes the same question without another POST',async({page})=>{
 await classroom(page,'断线恢复');await openQuestion(page);
 let posts=0;page.on('request',request=>{if(request.method()==='POST'&&/\/messages$/.test(request.url()))posts++;});
 await page.getByLabel('课程问题',{exact:true}).fill('断线恢复回归：说明栈的顺序');await page.getByRole('button',{name:'发送追问 ↗',exact:true}).click();
 await expect(page.locator('.live-answer')).toContainText('结合当前资料');await page.reload();
 await expect(page.getByLabel('课堂对话')).toContainText('结合当前资料');expect(posts).toBe(1);
});
test('mobile question sheet remains inside the viewport and closes with Escape under reduced motion',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.emulateMedia({reducedMotion:'reduce'});
 await classroom(page,'手机回归');await openQuestion(page);
 await expect(page.getByLabel('课程问题',{exact:true})).toBeVisible();
 await expect(page.getByLabel('课程问题',{exact:true})).toBeFocused();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 await page.screenshot({path:'test-results/classroom-mobile.png',fullPage:true});
 await page.getByLabel('课程问题',{exact:true}).press('Escape');
 await expect(page.getByRole('button',{name:'对这里提问 ↗',exact:true})).toBeFocused();
});
test('applies a bounded teaching highlight to the currently published code resource',async({page})=>{
 await classroom(page,'教学定位回归');await resourcePage(page,/栈的 C 代码实现/);
 await expect(page.getByLabel('C 代码讲解')).toBeVisible();await openQuestion(page);
 await page.getByLabel('课程问题',{exact:true}).fill('请指出当前源码的开头几行');await page.getByRole('button',{name:'发送追问 ↗',exact:true}).click();
 await expect(page.getByLabel('C 代码讲解').locator('.highlighted').first()).toBeVisible();
 await expect(page.getByLabel('课堂对话')).toContainText('结合当前资料');
});

test('keeps long lessons, checks and question history within a single viewport',async({page})=>{
 await page.route(/\/api\/v1\/study-rooms\/[a-f0-9-]+$/,async route=>{
  if(route.request().method()!=='GET'){await route.continue();return;}
  const response=await route.fetch();const body=await response.json();
  body.steps=body.steps.map((step:Record<string,unknown>)=>({...step,explanation:'长讲解：先理解后进先出，再跟踪栈顶变化。\n\n'.repeat(100)}));
  body.messages=Array.from({length:30},(_,i)=>({id:`long-history-${i}`,role:i%2?'assistant':'user',content:'这是一段用于检验滚动区域的历史课程问答。'.repeat(8),sourceIds:[],stepId:null,createdAt:'2026-10-21T00:00:00Z'}));
  await route.fulfill({response,json:body});
 });
 await page.setViewportSize({width:1366,height:768});await classroom(page,'一屏课堂回归');
 for(const viewport of [{width:1366,height:768},{width:390,height:844},{width:844,height:390}]){
  await page.setViewportSize(viewport);await page.reload();
  await expect(page.getByRole('button',{name:'对这里提问 ↗',exact:true})).toBeEnabled();
  const within=async(locator:import('@playwright/test').Locator)=>{const box=await locator.boundingBox();expect(box).not.toBeNull();expect(box!.y).toBeGreaterThanOrEqual(0);expect(box!.y+box!.height).toBeLessThanOrEqual(viewport.height+1);};
  await within(page.getByRole('button',{name:'下一页',exact:true}));
  await notes(page);
  const explanation=page.getByLabel('完整讲解正文');
  expect(await explanation.evaluate(element=>element.scrollHeight>element.clientHeight)).toBe(true);
  await explanation.hover();await page.mouse.wheel(0,320);
  await expect.poll(()=>explanation.evaluate(element=>element.scrollTop)).toBeGreaterThan(0);
  const position=await explanation.evaluate(element=>element.scrollTop);
  await openQuestion(page);
  await expect(page.getByLabel('课程问题',{exact:true})).toBeFocused();
  await within(page.getByLabel('课程问题',{exact:true}));await within(page.getByRole('button',{name:'发送追问 ↗',exact:true}));
  expect(await page.getByLabel('问答记录',{exact:true}).evaluate(element=>element.scrollHeight>element.clientHeight)).toBe(true);
  expect(await page.evaluate(()=>document.documentElement.scrollHeight<=innerHeight+1&&document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByLabel('课程问题',{exact:true}).press('Escape');
  await expect(explanation).toBeVisible();
  expect(await explanation.evaluate(element=>element.scrollTop)).toBe(position);
  await resourcePage(page,/小测/);await expect(page.getByLabel('知识点小测').filter({visible:true})).toBeVisible();
  await within(page.getByRole('button',{name:'下一页',exact:true}));
  await notes(page);await expect(explanation).toBeVisible();
  await page.screenshot({path:`test-results/compact-${viewport.width}.png`,fullPage:true});
 }
});

test('plays ordered narration, saves image-relative focus and keeps checks beside materials',async({page})=>{
 await page.addInitScript(()=>{
  const state={current:null as unknown as {onstart?:()=>void;onend?:()=>void},calls:0};
  (window as unknown as {lectureVoice:typeof state}).lectureVoice=state;
  class Utterance {onstart?:()=>void;onend?:()=>void;onerror?:()=>void;constructor(public text:string){}}
  Object.defineProperty(window,'SpeechSynthesisUtterance',{configurable:true,value:Utterance});
  Object.defineProperty(window,'speechSynthesis',{configurable:true,value:{getVoices:()=>[{lang:'zh-CN'}],cancel(){},speak(utterance:Utterance){state.current=utterance;state.calls++;queueMicrotask(()=>utterance.onstart?.());}}});
 });
 await page.route('**/presentation/slides/harness-stack-slide/**',route=>route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"><rect width="800" height="600" fill="#f4f3ef"/><text x="80" y="100" font-size="36">Stack / top</text><rect x="240" y="180" width="300" height="260" fill="#ddd"/></svg>'}));
 await classroom(page,'讲述框选回归');
 const controls=page.getByLabel('顺序讲授');await expect(controls.getByRole('button',{name:'开始讲授',exact:true})).toBeEnabled();
 await controls.getByRole('button',{name:'开始讲授',exact:true}).click();await expect(controls.getByRole('button',{name:'暂停讲授',exact:true})).toBeVisible();
 const first=await page.getByLabel('当前讲述字幕').innerText();
 await page.evaluate(()=>(window as unknown as {lectureVoice:{current:{onend?:()=>void}}}).lectureVoice.current.onend?.());
 await expect(page.getByLabel('当前讲述字幕')).not.toHaveText(first);
 await controls.getByRole('button',{name:'暂停讲授',exact:true}).click();
 await resourcePage(page,/栈的定义与代码课件/);
 await page.getByRole('button',{name:'标注讲述区域',exact:true}).click();
 const overlay=page.locator('.slide-regions');const rect=(await overlay.boundingBox())!;
 const scale=Math.min(rect.width/800,rect.height/600),paintWidth=800*scale,paintHeight=600*scale;
 const paintLeft=rect.x+(rect.width-paintWidth)/2,paintTop=rect.y+(rect.height-paintHeight)/2;
 await page.mouse.move(paintLeft+paintWidth*.2,paintTop+paintHeight*.25);await page.mouse.down();await page.mouse.move(paintLeft+paintWidth*.65,paintTop+paintHeight*.6);await page.mouse.up();
 await page.getByLabel('这一区域讲什么？',{exact:true}).fill('聚焦这个栈顶区域。');
 const saved=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/lecture/regions'));
 await page.getByRole('button',{name:'保存讲述区域',exact:true}).click();expect((await saved).ok()).toBe(true);
 await expect(page.getByRole('button',{name:'标注讲述区域',exact:true})).toBeVisible();
 const plan=await page.request.get((await saved).url().replace(/\/regions$/,'')+'?contentRevision=1');const script=await plan.json();
 const focusIndex=script.cues.findIndex((cue:{action:string})=>cue.action==='PPT_REGION');expect(focusIndex).toBeGreaterThan(0);
 await controls.getByRole('button',{name:'开始讲授',exact:true}).click();
 for(let i=0;i<focusIndex;i++){await page.evaluate(()=>(window as unknown as {lectureVoice:{current:{onend?:()=>void}}}).lectureVoice.current.onend?.());await expect(page.getByLabel('当前讲述字幕')).toContainText(script.cues[i+1].text);}
 await expect(page.getByLabel('当前讲述区域')).toBeVisible();await expect(page.getByLabel('当前讲述字幕')).toContainText('聚焦这个栈顶区域。');
 const focus=await page.getByLabel('当前讲述区域').boundingBox();expect(focus!.width).toBeGreaterThan(10);
 await page.screenshot({path:'test-results/lecture-focus.png',fullPage:true});
 await page.reload();await expect(controls.getByRole('button',{name:'开始讲授',exact:true})).toBeEnabled();
 await controls.getByRole('button',{name:'开始讲授',exact:true}).click();await expect(page.getByLabel('当前讲述区域')).toBeVisible();
 await openQuestion(page);await expect(controls.getByRole('button',{name:'开始讲授',exact:true})).toBeDisabled();
 await page.getByLabel('课程问题',{exact:true}).press('Escape');
 await resourcePage(page,/小测/);
 await expect(page.getByLabel('知识点小测').filter({visible:true})).toBeVisible();await expect(page.getByRole('button',{name:'课程目录',exact:true})).toBeVisible();
 await expect(page.locator('.classroom-progress')).toContainText('/ 2 个知识点');
});
