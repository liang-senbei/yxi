import re,sys
B=open('/root/inbox/apk/assets/index.android.bundle','rb').read()
def show(needle, before=120, after=420, limit=6):
    print(f"\n########## {needle!r} ##########")
    n=0
    for m in re.finditer(re.escape(needle.encode()), B):
        s=max(0,m.start()-before); e=min(len(B),m.end()+after)
        chunk=B[s:e].decode('utf-8','replace')
        chunk=''.join(c if (c=='\n' or c=='\t' or 32<=ord(c)<127 or ord(c)>159) else '·' for c in chunk)
        print(f"--- @{m.start()} ---"); print(chunk)
        n+=1
        if n>=limit: break
    if n==0: print("(无)")
for nd in sys.argv[1:]:
    show(nd)
