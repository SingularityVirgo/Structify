import type {StudyroomResource} from '../shared/types/studyroom';

/** Read-model overlay from GET study-rooms; clients must not reconstruct `/slides/{id}/image`. */
export function signedPptImageUrl(resource: StudyroomResource | undefined): string {
  const parameters = resource?.parameters;
  if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) return '';
  const url = (parameters as Record<string, unknown>).imageUrl;
  return typeof url === 'string' ? url : '';
}
