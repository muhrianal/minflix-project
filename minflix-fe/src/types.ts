export type VideoStatus = 'UPLOADING' | 'PROCESSING' | 'READY' | 'FAILED';

export interface Video {
  id: string;
  title: string;
  status: VideoStatus;
  ownerId: string;
  masterPlaylistUrl: string | null;
  durationSec: number | null;
  createdAt: string;
}
