import Hls from 'hls.js';
import { useEffect, useRef, useState } from 'react';

interface Option {
  index: number; // index into hls.levels
  label: string; // e.g. "720p"
}

const AUTO = -1;

export default function Player({ src }: { src: string }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const [options, setOptions] = useState<Option[]>([]);
  const [selected, setSelected] = useState(AUTO);
  const [playing, setPlaying] = useState('');

  useEffect(() => {
    const video = videoRef.current!;

    // Native HLS fallback (e.g. iOS Safari without MSE): the browser picks the quality, so no dropdown.
    // hls.js is checked first because recent Chrome also answers canPlayType for HLS.
    if (!Hls.isSupported() && video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src;
      return;
    }

    const hls = new Hls();
    hlsRef.current = hls;
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      setOptions(
        hls.levels
          .map((level, index) => ({ index, label: `${level.height}p`, height: level.height }))
          .sort((a, b) => b.height - a.height)
          .map(({ index, label }) => ({ index, label })),
      );
    });
    hls.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => {
      setPlaying(`${hls.levels[data.level].height}p`);
    });
    hls.loadSource(src);
    hls.attachMedia(video);

    return () => {
      hls.destroy();
      hlsRef.current = null;
    };
  }, [src]);

  const select = (index: number) => {
    setSelected(index);
    // -1 = automatic (ABR); a level index switches immediately.
    hlsRef.current!.currentLevel = index;
  };

  return (
    <div className="player">
      <video ref={videoRef} controls playsInline />
      {options.length > 0 && (
        <div className="player-controls">
          <label htmlFor="quality">Quality</label>
          <select id="quality" value={selected} onChange={(e) => select(Number(e.target.value))}>
            <option value={AUTO}>Auto</option>
            {options.map((o) => (
              <option key={o.index} value={o.index}>
                {o.label}
              </option>
            ))}
          </select>
          {playing && <span>{selected === AUTO ? `Auto (${playing})` : playing}</span>}
        </div>
      )}
    </div>
  );
}
