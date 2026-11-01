import type {StudyroomScene,StudyroomStep} from '../shared/types/studyroom';
import {signedPptImageUrl} from './ppt-image';
import {scenesFor} from './studyroom-scenes';

export interface ClassroomPage {
 id:string;title:string;kind:StudyroomScene['kind'];scene:StudyroomScene;
 resourceId?:string;
 imageUrl?:string;
}

/** Presentation pages never mint resource permissions or change publication state. */
export function classroomPages(step:StudyroomStep,revision:number):ClassroomPage[]{
 const orchestration=scenesFor(step);
 const scenes=[orchestration.primary,...orchestration.supplementary];
 const pages:ClassroomPage[]=[];
 for(const scene of scenes){
  if(scene.kind==='TEXT')continue;
  scene.items.forEach((item,index)=>{
   const resource=step.resources.find(candidate=>candidate.id===item.resourceId);
   pages.push({id:`${step.id}:${revision}:${scene.id}:${index}:${item.resourceId}`,kind:scene.kind,title:resource?.title||scene.purpose,resourceId:item.resourceId,imageUrl:scene.kind==='PPT'?signedPptImageUrl(resource):undefined,scene:{...scene,items:[item]}});
  });
 }
 return pages.length?pages:[{id:`${step.id}:${revision}:empty`,title:step.title,kind:'TEXT',scene:orchestration.primary}];
}
