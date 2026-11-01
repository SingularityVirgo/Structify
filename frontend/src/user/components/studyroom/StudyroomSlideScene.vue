<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue';
import type {MaterialPosition,TeachingAction} from '../../../shared/types/classroom-experience';
import type { StudyroomResource, StudyroomScene } from '../../../shared/types/studyroom';
import {signedPptImageUrl} from '../../ppt-image';
import StudyroomSlideRegions from './StudyroomSlideRegions.vue';
import type {LectureRegion} from '../../lecture-types';
const props = defineProps<{ scene: StudyroomScene; resources: StudyroomResource[]; bounded?:boolean;singlePage?:boolean;regions?:LectureRegion[] }>();
const emit = defineEmits<{ context: [resourceId: string];saveRegion:[value:LectureRegion];removeRegion:[id:string];interact:[] }>();
const imageSize=ref({width:0,height:0});const focusRegion=ref('');
const index = ref(0);
const zoom = ref(1);
const viewport = ref<HTMLElement>();
const frame = ref<HTMLElement>();
const failed = ref(new Set<string>());
const retries = ref<Record<string, number>>({});
const page = computed(() => props.scene.items[index.value]);
const resource = computed(() => props.resources.find(r => r.id === page.value?.resourceId && r.type.toUpperCase() === 'PPT'));
const url = computed(() => signedPptImageUrl(resource.value));
function thumbUrl(resourceId: string) {
  return signedPptImageUrl(props.resources.find(candidate => candidate.id === resourceId));
}
const originalPage = computed(() => resource.value?.description.match(/原始页码=(\d+)/)?.[1]);
const fullScreenHint = ref('');
watch(() => props.scene.items.map(i => i.resourceId).join('|'), () => { index.value = Math.min(index.value, props.scene.items.length - 1); });
watch(() => page.value?.resourceId, id => { zoom.value = 1;focusRegion.value='';imageSize.value={width:0,height:0}; if (id) emit('context', id); }, { immediate: true });
function changePage(next: number) { index.value = Math.max(0, Math.min(props.scene.items.length - 1, next)); }
function retry() { if (page.value) { failed.value.delete(page.value.resourceId); retries.value[page.value.resourceId] = (retries.value[page.value.resourceId] ?? 0) + 1; } }
async function fullscreen() {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await frame.value?.requestFullscreen(); }
  catch { fullScreenHint.value = '当前浏览器不支持全屏，可使用放大阅读。'; }
}
let drag: { x: number; y: number; left: number; top: number } | null = null;
function pointerDown(event: PointerEvent) {
  if (zoom.value <= 1 || !viewport.value || event.pointerType === 'touch') return;
  drag = { x: event.clientX, y: event.clientY, left: viewport.value.scrollLeft, top: viewport.value.scrollTop };
  viewport.value.setPointerCapture?.(event.pointerId);
}
function pointerMove(event: PointerEvent) {
  if (!drag || !viewport.value) return;
  viewport.value.scrollLeft = drag.left - event.clientX + drag.x;
  viewport.value.scrollTop = drag.top - event.clientY + drag.y;
}
defineExpose({
 capture:():MaterialPosition=>({kind:'PPT',pageId:page.value?.resourceId??'',zoom:zoom.value,scrollLeft:viewport.value?.scrollLeft??0,scrollTop:viewport.value?.scrollTop??0,...(focusRegion.value?{focusRegionId:focusRegion.value}:{})}),
 pause:()=>{},
 restore:async(position:MaterialPosition)=>{if(position.kind!=='PPT')return;const target=props.scene.items.findIndex(item=>item.resourceId===position.pageId);if(target<0)return;index.value=target;await nextTick();zoom.value=position.zoom;focusRegion.value=props.regions?.some(region=>region.id===position.focusRegionId&&region.resourceId===position.pageId)?position.focusRegionId??'':'';await nextTick();viewport.value?.scrollTo?.(position.scrollLeft,position.scrollTop);},
 supports:(action:TeachingAction)=>(action.kind==='MATERIAL_FOCUS'||action.kind==='PPT_REGION')&&props.scene.items.some(item=>item.resourceId===action.resourceId)&&(action.kind!=='PPT_REGION'||!!props.regions?.some(region=>region.id===action.regionId&&region.sceneId===props.scene.id&&region.resourceId===action.resourceId)),
 apply:async(action:TeachingAction)=>{const target=props.scene.items.findIndex(item=>item.resourceId===action.resourceId);if(!['MATERIAL_FOCUS','PPT_REGION'].includes(action.kind)||target<0)return false;if(action.kind==='PPT_REGION'&&!props.regions?.some(region=>region.id===action.regionId&&region.resourceId===action.resourceId))return false;changePage(target);await nextTick();focusRegion.value=action.kind==='PPT_REGION'?action.regionId??'':'';zoom.value=1;return true;},
});
</script>
<template>
  <section ref="frame" class="slide-scene" aria-label="本步课件">
    <header v-if="!bounded||!singlePage" class="slide-toolbar" @click="emit('interact')">
      <div v-if="!singlePage" class="slide-pages"><button type="button" aria-label="上一页" :disabled="index === 0" @click="changePage(index - 1)">←</button><span aria-live="polite">{{ index + 1 }} / {{ scene.items.length }}</span><button type="button" aria-label="下一页" :disabled="index >= scene.items.length - 1" @click="changePage(index + 1)">→</button></div>
      <div class="slide-zoom"><button type="button" aria-label="缩小" :disabled="zoom <= 1" @click="zoom = Math.max(1, zoom - .25)">−</button><span>{{ Math.round(zoom * 100) }}%</span><button type="button" aria-label="放大" :disabled="zoom >= 3" @click="zoom = Math.min(3, zoom + .25)">＋</button><button type="button" aria-label="恢复整页" @click="zoom = 1">整页</button><button type="button" aria-label="全屏阅读" @click="fullscreen">全屏</button></div>
    </header>
    <div ref="viewport" class="studyroom-slide-viewport" :class="{ 'is-zoomed': zoom > 1 }" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="drag = null" @pointercancel="drag = null" @lostpointercapture="drag = null">
      <div v-if="!resource?.available || !url" class="slide-error" role="status">本页课件暂不可用，仍可阅读下方讲解。</div>
      <div v-else-if="page && failed.has(page.resourceId)" class="slide-error" role="status">课件加载失败。<button type="button" aria-label="重试当前页" @click="retry">重试</button></div>
      <div v-else class="slide-canvas" :style="{ width: `${zoom * 100}%`, height: `${zoom * 100}%` }"><img v-if="page" :key="`${page.resourceId}:${retries[page.resourceId] ?? 0}`" class="studyroom__slide" :src="url" :alt="resource?.title || '课程课件'" draggable="false" @load="imageSize={width:($event.target as HTMLImageElement).naturalWidth,height:($event.target as HTMLImageElement).naturalHeight}" @error="failed.add(page.resourceId)"><StudyroomSlideRegions v-if="bounded&&page&&regions" :scene-id="scene.id" :resource-id="page.resourceId" :regions="regions.filter(region=>region.sceneId===scene.id&&region.resourceId===page?.resourceId)" :focus-id="focusRegion" :natural-width="imageSize.width" :natural-height="imageSize.height" @save="emit('saveRegion',$event)" @remove="emit('removeRegion',$event)" @interact="emit('interact')"/></div>
    </div>
    <p v-if="fullScreenHint" role="status">{{ fullScreenHint }}</p>
    <div v-if="!bounded" class="slide-caption"><strong>{{ resource?.title }}</strong><span v-if="originalPage">原课件第 {{ originalPage }} 页</span></div>
    <p v-if="!bounded&&page?.explanation" class="slide-narration">{{ page.explanation }}</p>
    <details v-if="!singlePage&&scene.items.length > 1" class="slide-directory"><summary>本步课件目录 · {{ scene.items.length }} 页</summary><nav aria-label="课件页目录"><button v-for="(item, i) in scene.items" :key="item.resourceId" type="button" :aria-current="i === index ? 'page' : undefined" @click="changePage(i)"><img v-if="thumbUrl(item.resourceId)" :src="thumbUrl(item.resourceId)" alt="" loading="lazy"><span>{{ i + 1 }} · {{ resources.find(r => r.id === item.resourceId)?.title || '课件页' }}</span></button></nav></details>
  </section>
</template>
<style scoped>
.slide-scene { min-width: 0; background: var(--surface); color: var(--text); }
.slide-scene:fullscreen { padding: 20px; overflow: auto; }
.slide-toolbar,.slide-pages,.slide-zoom { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.slide-toolbar { justify-content:space-between; padding:0 0 12px; }
button { min-height:36px; padding:6px 11px; border:1px solid var(--line); border-radius:6px; background:var(--bg); color:var(--text); cursor:pointer; }
button:disabled { opacity:.4; cursor:default; }
button:focus-visible,summary:focus-visible { outline:2px solid var(--text); outline-offset:3px; }
.slide-pages span,.slide-zoom span { font-variant-numeric:tabular-nums; font-size:12px; }
.studyroom-slide-viewport { height: min(62vh,680px); min-height:240px; overflow:auto; background:var(--bg); touch-action:pan-x pan-y; border:1px solid var(--line); }
.is-zoomed { cursor:grab; }.is-zoomed:active { cursor:grabbing; }
.slide-canvas { position:relative;min-width:100%; min-height:100%; }
.studyroom__slide { display:block; width:100%; height:100%; object-fit:contain; }
.slide-error { min-height:240px; display:grid; place-content:center; gap:12px; text-align:center; padding:20px; }
.slide-caption { display:flex; justify-content:space-between; gap:12px; margin-top:12px; font-size:12px; color:var(--text-muted); }.slide-caption strong { color:var(--text); }
.slide-narration { max-width:75ch; line-height:1.8; white-space:pre-wrap; margin:12px 0; }
.slide-directory { margin-top:16px; border-top:1px solid var(--line); padding-top:12px; font-size:13px; }summary { cursor:pointer; }
.slide-directory nav { display:grid; grid-template-columns:repeat(auto-fill,minmax(120px,1fr)); gap:10px; margin-top:12px; }
.slide-directory button { display:grid; gap:8px; text-align:left; }.slide-directory button[aria-current] { border-color:var(--text); }.slide-directory img { width:100%; aspect-ratio:4/3; object-fit:contain; }
@media(max-width:680px) { .slide-toolbar { gap:10px; }.studyroom-slide-viewport { height:42vh; }.slide-caption { display:grid; } }
</style>
