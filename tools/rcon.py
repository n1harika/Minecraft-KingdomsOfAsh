import socket, struct, sys
def pkt(i, t, body):
    b = struct.pack('<ii', i, t) + body.encode() + b'\x00\x00'
    return struct.pack('<i', len(b)) + b
def rd(s):
    n = struct.unpack('<i', s.recv(4))[0]; d = b''
    while len(d) < n: d += s.recv(n - len(d))
    return struct.unpack('<ii', d[:8]), d[8:-2].decode(errors='replace')
def run(cmds, port=25575, pw='test'):
    s = socket.create_connection(('127.0.0.1', port), timeout=30)
    s.sendall(pkt(1, 3, pw)); rd(s)
    for c in cmds:
        s.sendall(pkt(2, 2, c)); print('>', c); print(rd(s)[1])
    s.close()
if __name__ == '__main__':
    run(sys.argv[1:])
