import {expect, it} from 'vitest';
import {signedPptImageUrl} from './ppt-image';
import type {StudyroomResource} from '../shared/types/studyroom';

const ppt = (parameters: unknown): StudyroomResource => ({
  type: 'PPT',
  id: 'slide-1',
  title: '栈',
  description: '',
  parameters,
  available: true,
});

it('uses the server-signed page URL and ignores missing parameters', () => {
  expect(signedPptImageUrl(ppt({imageUrl: '/api/v1/presentation/slides/slide-1/sig.webp'})))
    .toBe('/api/v1/presentation/slides/slide-1/sig.webp');
  expect(signedPptImageUrl(ppt({}))).toBe('');
  expect(signedPptImageUrl(undefined)).toBe('');
});
