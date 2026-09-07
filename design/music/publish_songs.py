"""关卡联网的发布（老板 2026-09-07：「能不能联网，不然每次更新关卡又要更新一次 App」）。

做什么：
  1. 从 APK 的素材目录攒出 dist/rhythm/：<id>/<id>.ogg、<id>/chart_<id>_<diff>.json、songs.json（清单）
  2. rsync 到 hk13 的 /var/www/yxi/rhythm/，**清单先以 songs.json.new 上传**
  3. 在 hk13 上跑 logto 的 rhythm-sync.py --apply（让服务端先认识新谱：units / durationMs / notes / slides）
  4. 同步成功后再把 songs.json.new 改名成 songs.json 对外可见（顺序反了客户端会下到服务端不认识的谱 → no_such_chart）
  5. 公网真拉一遍核对

清单结构（客户端 Rhythm.parseManifest 读的）：
  {"version": 1, "generated": "...", "songs": [
     {"id", "zh", "bpm", "seconds", "credit", "version": 1, "audio": "<id>/<id>.ogg", "audioBytes": N,
      "charts": {"easy": "<id>/chart_<id>_easy.json", ...},
      "chartsMeta": {"<id>_easy": {"notes", "slides", "traces", "swipes", "ticks", "units", "durationMs"}, ...}} ] }

曲目元数据（zh / bpm / seconds / credit）从 Rhythm.kt 的 SONGS 表读，保证和 App 内置一致；清单独有的新曲子写在下面 EXTRA 里。
用法：python3 publish_songs.py [--dry]   （--dry 只攒 dist 不上传）
⚠️ 跑完提醒 logto：他要把 service/rhythm.json 一起提进 git，不然下次部署会覆盖回去。
"""
import glob, json, os, re, subprocess, sys, time

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.abspath(os.path.join(here, '../..'))
raw = os.path.join(root, 'android/app/src/main/res/raw')
charts = os.path.join(root, 'android/app/src/main/assets/charts')
dist = os.path.join(here, 'dist/rhythm')
HK = 'hk13'
REMOTE_DIR = '/var/www/yxi/rhythm'
URL = 'https://yxi.keuury.com/rhythm/'
SYNC = 'cd /root/src/workplace/logto_yxi && python3 scripts/rhythm-sync.py'
# 清单独有的曲子（不进 APK）：id → dict(zh, bpm, seconds, credit, audio=本地 ogg 路径)
EXTRA = {}


def songs_from_kotlin():
    src = open(os.path.join(root, 'android/app/src/main/kotlin/app/yxi/agent/Rhythm.kt'), encoding='utf-8').read()
    out = []
    for m in re.finditer(r'Song\("(\w+)", "([^"]+)", app\.yxi\.R\.raw\.yx_\w+, (\d+), (\d+)(?:, "([^"]*)")?', src):
        out.append(dict(id=m.group(1), zh=m.group(2), bpm=int(m.group(3)), seconds=int(m.group(4)), credit=m.group(5) or ''))
    return out


def chart_meta(path):
    c = json.load(open(path)); ns = c['notes']
    cnt = {k: sum(1 for x in ns if x['type'] == k) for k in ('tick', 'slide', 'trace', 'swipe')}
    dur = max(n['t'] + n.get('dur', 0) for n in ns)
    return dict(notes=len(ns), ticks=cnt['tick'], slides=cnt['slide'], traces=cnt['trace'], swipes=cnt['swipe'],
                units=len(ns) + cnt['slide'], durationMs=int(round(dur * 1000)))


def build():
    if os.path.exists(dist):
        subprocess.run(['rm', '-rf', dist], check=True)
    os.makedirs(dist)
    songs = []
    for s in songs_from_kotlin() + [dict(id=k, **v) for k, v in EXTRA.items()]:
        sid = s['id']; d = os.path.join(dist, sid); os.makedirs(d)
        audio_src = s.get('audio') or os.path.join(raw, f'yx_{sid}.ogg')
        subprocess.run(['cp', audio_src, os.path.join(d, f'{sid}.ogg')], check=True)
        entry = dict(id=sid, zh=s['zh'], bpm=s['bpm'], seconds=s['seconds'], credit=s.get('credit', ''), version=1,
                     audio=f'{sid}/{sid}.ogg', audioBytes=os.path.getsize(audio_src), charts={}, chartsMeta={})
        for p in sorted(glob.glob(os.path.join(charts, f'chart_{sid}_*.json'))):
            diff = os.path.basename(p)[len(f'chart_{sid}_'):-5]
            subprocess.run(['cp', p, d], check=True)
            entry['charts'][diff] = f'{sid}/{os.path.basename(p)}'
            entry['chartsMeta'][f'{sid}_{diff}'] = chart_meta(p)
        # 谱面版本 = 谱文件内容的哈希前 8 位转成数字（内容变了客户端才重下）
        import hashlib
        h = hashlib.sha256(''.join(open(os.path.join(d, os.path.basename(v))).read() for v in entry['charts'].values()).encode()).hexdigest()
        entry['version'] = int(h[:7], 16)
        songs.append(entry)
    manifest = dict(version=1, generated=time.strftime('%Y-%m-%dT%H:%M:%S'), songs=songs)
    json.dump(manifest, open(os.path.join(dist, 'songs.json'), 'w'), ensure_ascii=False, indent=1)
    n_charts = sum(len(s['charts']) for s in songs)
    print(f'dist: {len(songs)} 首 {n_charts} 张谱 → {dist}')
    return manifest


def publish():
    subprocess.run(['ssh', HK, f'mkdir -p {REMOTE_DIR}'], check=True)
    # 音频 + 谱先上；清单以 .new 上传
    subprocess.run(['rsync', '-a', '--exclude', 'songs.json', dist + '/', f'{HK}:{REMOTE_DIR}/'], check=True)
    subprocess.run(['scp', '-q', os.path.join(dist, 'songs.json'), f'{HK}:{REMOTE_DIR}/songs.json.new'], check=True)
    print('文件已上传（清单暂为 songs.json.new）')
    # 服务端先认识新谱
    r = subprocess.run(['ssh', HK, f'{SYNC} {REMOTE_DIR}/songs.json.new --apply'], capture_output=True, text=True)   # 本机路径：从 hk13 用 urllib 拉自己的公网 URL 会被 403（UA 拦截）
    print(r.stdout[-1500:]); print(r.stderr[-800:])
    if r.returncode != 0:
        print('✗ 服务端同步失败，清单没公开（songs.json.new 留在服务器上供排查）'); sys.exit(1)
    subprocess.run(['ssh', HK, f'mv -f {REMOTE_DIR}/songs.json.new {REMOTE_DIR}/songs.json'], check=True)
    # 公网真拉一遍
    r = subprocess.run(['curl', '-sS', '--max-time', '20', URL + 'songs.json'], capture_output=True, text=True)
    m = json.loads(r.stdout)
    print(f'✓ 公网清单 {len(m["songs"])} 首，generated {m["generated"]}')
    print('⚠️ 提醒 logto：把 service/rhythm.json 一起提进 git（脚本改的是线上那份）')


if __name__ == '__main__':
    build()
    if '--dry' not in sys.argv:
        publish()
