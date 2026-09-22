"""Home-scoped key exchange and reverse-link verification. Never exports a private key."""
import base64, json, os, pathlib, re, subprocess, sys, tempfile

def public_key(value):
    parts = value.strip().split()
    if len(parts) < 2 or parts[0] not in ('ssh-ed25519', 'ssh-rsa', 'ecdsa-sha2-nistp256'):
        raise ValueError('invalid public key')
    base64.b64decode(parts[1], validate=True)
    return ' '.join(parts[:2])

def home_dir():
    home = pathlib.Path.home()
    ssh = home / '.ssh'
    if ssh.is_symlink(): raise ValueError('linked ssh directory unsupported')
    ssh.mkdir(mode=0o700, exist_ok=True)
    ssh.chmod(0o700)
    return ssh

def key_path(device):
    if not re.fullmatch(r'[a-f0-9]{32}', device): raise ValueError('invalid device id')
    path = home_dir() / ('yxi-link-' + device)
    if path.is_symlink() or pathlib.Path(str(path)+'.pub').is_symlink(): raise ValueError('linked key unsupported')
    return path

def prepare(req):
    ssh = home_dir()
    key = key_path(req['device'])
    if not key.exists():
        subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(key), '-C', 'yxi-link'], check=True, timeout=20, stdout=subprocess.DEVNULL)
    key.chmod(0o600)
    pub_path = pathlib.Path(str(key)+'.pub')
    if not pub_path.exists(): raise ValueError('incomplete server key pair')
    local = public_key(req['publicKey'])
    dest = ssh / 'authorized_keys'
    if dest.is_symlink(): raise ValueError('linked authorized_keys unsupported')
    # File lock + append: preserve existing content and serialize other Yxi setup requests.
    import fcntl
    with dest.open('a+', encoding='utf-8') as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        f.seek(0); old = f.read()
        if not any(line.strip().startswith(local + ' ') or line.strip() == local for line in old.splitlines()):
            f.write(('\n' if old and not old.endswith('\n') else '') + local + ' yxi-link\n')
            f.flush(); os.fsync(f.fileno())
        os.fchmod(f.fileno(), 0o600)
    return {'publicKey': public_key(pub_path.read_text()), 'keyPath': str(key)}

def verify(req):
    port = int(req['reversePort'])
    if not 1024 <= port <= 65535: raise ValueError('invalid port')
    # Reject a server GatewayPorts policy that exposed the requested loopback listener publicly.
    listeners = []
    for name in ('/proc/net/tcp', '/proc/net/tcp6'):
        for line in pathlib.Path(name).read_text().splitlines()[1:]:
            fields = line.split(); addr, p = fields[1].split(':')
            if int(p, 16) == port and fields[3] == '0A': listeners.append(addr)
    if not listeners or any(addr not in ('0100007F', '00000000000000000000000001000000') for addr in listeners):
        raise ValueError('reverse listener is not loopback-only; check server GatewayPorts')
    key = key_path(req['device'])
    hostkey = public_key(req['hostKey'])
    user = req['username']
    if not user or any(ord(c) < 32 for c in user): raise ValueError('invalid local username')
    fd, known = tempfile.mkstemp(prefix='yxi-link-known-', dir=str(home_dir()))
    try:
        with os.fdopen(fd, 'w') as f: f.write('[127.0.0.1]:' + str(port) + ' ' + hostkey + '\n')
        args = ['ssh', '-F', '/dev/null', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'UserKnownHostsFile='+known, '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'IdentitiesOnly=yes',
                '-o', 'ConnectTimeout=10', '-p', str(port), '-i', str(key), '-l', user, '127.0.0.1', 'whoami']
        result = subprocess.run(args, capture_output=True, timeout=20)
        if result.returncode: raise ValueError('local SSH authentication failed; check Remote Login, username and authorized keys')
        return {'verified': True}
    finally: os.unlink(known)

def main(req):
    return prepare(req) if req['action'] == 'prepare' else verify(req) if req['action'] == 'verify' else (_ for _ in ()).throw(ValueError('unknown action'))

if __name__ == '__main__':
    try:
        value = main(json.loads(base64.b64decode(sys.argv[1])))
        print('__YXI_LINK__:' + json.dumps({'ok': True, **value}))
    except Exception as exc:
        print('__YXI_LINK__:' + json.dumps({'ok': False, 'error': str(exc)[:250]}))
