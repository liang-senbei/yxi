"""把子代理拿回来的新曲子批量出谱（老板 2026-09-07：「再去做十首歌」）。

输入：design/music/incoming/<name>.json（子代理写的：id / zh / author / site / url / license_url / credit / file / seconds）+ 同目录的音频。
输出：design/music/remote/<id>/  ← song.json（清单条目用）、<id>.ogg（Vorbis q5）、chart_<id>_easy.json、chart_<id>_hard.json
      publish_songs.py 会把 remote/ 下的曲子作为「清单独有的曲子」发出去（不进 APK，客户端点「下载这首」）。
用法：python3 make_remote_songs.py [id …]（不给就做 incoming 里所有还没做过的）
"""
import glob, json, os, subprocess, sys
import numpy as np

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here)
from chart_from_audio import analyze, build
from choreo import choreo
from lanes import spread
from energy_all import envelope

INC = os.path.join(here, 'incoming'); OUT = os.path.join(here, 'remote')


def make(meta):
    sid = meta['id']
    assert sid and all(c.isalnum() or c == '_' for c in sid), f'id 不合法：{sid}'
    src = os.path.join(INC, meta['file'])
    d = os.path.join(OUT, sid); os.makedirs(d, exist_ok=True)
    ogg = os.path.join(d, f'{sid}.ogg')
    if not os.path.exists(ogg):
        subprocess.run(['ffmpeg', '-v', 'quiet', '-y', '-i', src, '-vn', '-c:a', 'libvorbis', '-q:a', '5', ogg], check=True)
    a = analyze(ogg, meta.get('bpm'))
    for hard in (False, True):
        c = build(a, hard, sid, meta['zh'])
        c['notes'] = spread(c['notes'], sid + c['difficulty'])
        c['lines'] = choreo(sid, a['spb'], a['phase'], a['dur'], hard)
        c['energy'] = {'hz': 4, 'v': envelope(ogg)}
        p = os.path.join(d, f'chart_{sid}_{c["difficulty"]}.json')
        json.dump(c, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
        ns = c['notes']; cnt = {k: sum(1 for x in ns if x['type'] == k) for k in ('tick', 'slide', 'trace', 'swipe')}
        print(f"  {meta['zh']} {c['difficulty']:4} bpm={c['bpm']} 音符 {len(ns):3} {cnt} units={len(ns) + cnt['slide']}")
    dur = float(subprocess.run(['ffprobe', '-v', 'quiet', '-show_entries', 'format=duration', '-of', 'csv=p=0', ogg], capture_output=True, text=True).stdout.strip())
    song = dict(id=sid, zh=meta['zh'], bpm=int(round(a['tempo'])), seconds=int(round(dur)), credit=meta.get('credit', ''),
                author=meta.get('author', ''), site=meta.get('site', ''), url=meta.get('url', ''), license_url=meta.get('license_url', ''))
    json.dump(song, open(os.path.join(d, 'song.json'), 'w'), ensure_ascii=False, indent=1)
    print(f"✓ {sid}（{meta['zh']}）→ {d}")


if __name__ == '__main__':
    want = set(sys.argv[1:])
    metas = [json.load(open(p)) for p in sorted(glob.glob(os.path.join(INC, '*.json')))]
    done = 0
    for m in metas:
        if want and m['id'] not in want: continue
        if not want and os.path.exists(os.path.join(OUT, m['id'], 'song.json')): continue
        try:
            make(m); done += 1
        except Exception as e:
            print(f"✗ {m.get('id')}：{e}")
    print(f'做了 {done} 首')
