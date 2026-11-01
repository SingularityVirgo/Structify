import { mount } from '@vue/test-utils';
import { describe, it, expect } from 'vitest';
import StudyroomSlideScene from './StudyroomSlideScene.vue';
const signed = (id: string) => `/api/v1/presentation/slides/${id}/sig.webp`;
const ppt = (id: string, extra: Record<string, unknown> = {}) => ({
  id, type: 'PPT', title: extra.title as string ?? id, description: extra.description as string ?? '', available: true,
  parameters: { imageUrl: signed(id) },
});
describe('step slide collection', () => {
  it('uses the shared classroom toolbar instead of duplicate tools and slide metadata',()=>{
    const wrapper=mount(StudyroomSlideScene,{props:{bounded:true,singlePage:true,scene:{id:'main',kind:'PPT',purpose:'定义',items:[{resourceId:'p1',explanation:'解释'}]},resources:[ppt('p1',{title:'幻灯片标题',description:'原始页码=3'})]}});
    expect(wrapper.find('.slide-toolbar').exists()).toBe(false);
    expect(wrapper.find('.slide-caption').exists()).toBe(false);
    expect(wrapper.find('.slide-directory').exists()).toBe(false);
    expect(wrapper.get('img.studyroom__slide').attributes('alt')).toBe('幻灯片标题');
    wrapper.unmount();
  });
  it('changes page and explanation together and supports zoom reset', async () => {
    const wrapper = mount(StudyroomSlideScene, { props: {
      scene: { id: 'main', kind: 'PPT', purpose: '观察遍历', items: [{ resourceId: 'p1', explanation: '先看规则' }, { resourceId: 'p2', explanation: '再看例子' }] },
      resources: ['p1', 'p2'].map((id, i) => ppt(id, { description: `原始页码=${i + 3}` })),
    } });
    expect(wrapper.text()).toContain('先看规则');
    await wrapper.get('button[aria-label="下一页"]').trigger('click');
    expect(wrapper.get('img.studyroom__slide').attributes('src')).toBe(signed('p2'));
    expect(wrapper.text()).toContain('再看例子');
    expect(wrapper.text()).toContain('2 / 2');
    await wrapper.get('button[aria-label="放大"]').trigger('click');
    expect(wrapper.text()).toContain('125%');
    await wrapper.get('button[aria-label="恢复整页"]').trigger('click');
    expect(wrapper.text()).toContain('100%');
  });
  it('retries a failed page without substituting a different resource', async () => {
    const wrapper = mount(StudyroomSlideScene, { props: { scene: { id: 'p', kind: 'PPT', purpose: '看图', items: [{ resourceId: 'p1', explanation: '' }] }, resources: [ppt('p1', { title: '图' })] } });
    await wrapper.get('img.studyroom__slide').trigger('error');
    expect(wrapper.text()).toContain('加载失败');
    await wrapper.get('button[aria-label="重试当前页"]').trigger('click');
    expect(wrapper.find('img.studyroom__slide').exists()).toBe(true);
  });
  it('does not reconstruct an unsigned /image path', () => {
    const wrapper = mount(StudyroomSlideScene, {
      props: {
        scene: { id: 'p', kind: 'PPT', purpose: '看图', items: [{ resourceId: 'p1', explanation: '' }] },
        resources: [{ id: 'p1', type: 'PPT', title: '图', description: '', available: true, parameters: {} }],
      },
    });
    expect(wrapper.find('img.studyroom__slide').exists()).toBe(false);
    expect(wrapper.text()).toContain('暂不可用');
    wrapper.unmount();
  });
});
