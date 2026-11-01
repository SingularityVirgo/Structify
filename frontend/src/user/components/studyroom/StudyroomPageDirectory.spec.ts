import {mount} from '@vue/test-utils';
import {expect,it} from 'vitest';
import StudyroomPageDirectory from './StudyroomPageDirectory.vue';
import type {StudyroomRun} from '../../../shared/types/outline';
import type {StudyroomStep} from '../../../shared/types/studyroom';

it('uses the signed page URL for PPT thumbnails', () => {
  const step = {
    id: 's',
    stepKey: 's',
    type: 'CONCEPT',
    title: '栈',
    explanation: '讲解',
    evidenceIds: [],
    resources: [{
      id: 'slide-1',
      type: 'PPT',
      title: '入栈',
      description: '',
      available: true,
      parameters: {imageUrl: '/api/v1/presentation/slides/slide-1/sig.webp'},
    }],
    order: 0,
    supplemental: false,
    parentStepId: null,
    createdAt: '2026-11-01',
    orchestration: {
      schemaVersion: 2 as const,
      intent: 'CONCEPT' as const,
      objective: '理解栈',
      primary: {id: 'text', kind: 'TEXT' as const, purpose: '讲述', items: []},
      supplementary: [{
        id: 'ppt',
        kind: 'PPT' as const,
        purpose: '课件',
        items: [{resourceId: 'slide-1', explanation: ''}],
      }],
    },
  } satisfies StudyroomStep;
  const run = {
    id: 'run',
    knowledgePoints: [{
      id: 'u',
      title: '栈',
      status: 'READY',
      fragments: [{id: 'f', stepId: 's', contentRevision: 1}],
    }],
  } as unknown as StudyroomRun;
  const wrapper = mount(StudyroomPageDirectory, {props: {run, steps: [step], activeId: 'u', pageId: ''}});
  expect(wrapper.get('.thumbnail-preview img').attributes('src'))
    .toBe('/api/v1/presentation/slides/slide-1/sig.webp');
  expect(wrapper.html()).not.toContain('/image');
  wrapper.unmount();
});
