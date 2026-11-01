import {expect,it} from 'vitest';
import {classroomPages} from './classroom-pages';
import type {StudyroomStep} from '../shared/types/studyroom';
const step={id:'s',title:'栈',explanation:'完整讲解，不应转为幻灯片。',resources:[{id:'a',type:'PPT',title:'入栈',description:'',available:true,parameters:{imageUrl:'/api/v1/presentation/slides/a/sig.webp'}},{id:'b',type:'PPT',title:'出栈',description:'',available:true,parameters:{imageUrl:'/api/v1/presentation/slides/b/sig.webp'}}],orchestration:{schemaVersion:2,intent:'CONCEPT',objective:'理解栈',primary:{id:'text',kind:'TEXT',purpose:'讲述',items:[]},supplementary:[{id:'ppt',kind:'PPT',purpose:'课件',items:[{resourceId:'a'},{resourceId:'b'}]},{id:'code',kind:'C_CODE',purpose:'代码',items:[{resourceId:'c'}]}]}} as unknown as StudyroomStep;
it('projects every material item in teaching order without generating text slides',()=>{
 const pages=classroomPages(step,2);
 expect(pages.map(page=>page.resourceId)).toEqual(['a','b','c']);
 expect(pages.map(page=>page.kind)).toEqual(['PPT','PPT','C_CODE']);
 expect(pages.every(page=>page.scene.items.length===1)).toBe(true);
 expect(pages.filter(page=>page.kind==='PPT').map(page=>page.imageUrl)).toEqual([
  '/api/v1/presentation/slides/a/sig.webp',
  '/api/v1/presentation/slides/b/sig.webp',
 ]);
});
it('fences page identities by content revision',()=>{
 expect(classroomPages(step,1).map(page=>page.id)).not.toEqual(classroomPages(step,2).map(page=>page.id));
});
it('uses one neutral empty-resource state for text-only knowledge',()=>{
 const pages=classroomPages({...step,orchestration:{...step.orchestration!,supplementary:[]}},1);
 expect(pages).toHaveLength(1);
 expect(pages[0]!.title).toBe(step.title);
 expect(JSON.stringify(pages)).not.toContain(step.explanation);
});
