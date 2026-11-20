# Creates tun "kdxt0" with 10.66.0.1/24 and relays packets between it and stdin/stdout (2-byte length frames).
import fcntl, os, socket, struct, sys, threading
TUNSETIFF = 0x400454ca; IFF_TUN = 1; IFF_NO_PI = 0x1000
fd = os.open("/dev/net/tun", os.O_RDWR)
fcntl.ioctl(fd, TUNSETIFF, struct.pack("16sH", b"kdxt0", IFF_TUN | IFF_NO_PI))
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
def ifreq_addr(ip): return struct.pack("16sH2s4s8s", b"kdxt0", socket.AF_INET, b"\0\0", socket.inet_aton(ip), b"\0" * 8)
fcntl.ioctl(s, 0x8916, ifreq_addr("10.66.0.1"))       # SIOCSIFADDR
fcntl.ioctl(s, 0x891c, ifreq_addr("255.255.255.0"))   # SIOCSIFNETMASK
flags = struct.unpack("16sH", fcntl.ioctl(s, 0x8913, struct.pack("16sH", b"kdxt0", 0)))[1]
fcntl.ioctl(s, 0x8914, struct.pack("16sH", b"kdxt0", flags | 1 | 0x40))  # IFF_UP | IFF_RUNNING
sys.stderr.write("ready\n"); sys.stderr.flush()
out = sys.stdout.buffer
def up():
    while True:
        p = os.read(fd, 65535)
        out.write(struct.pack(">H", len(p)) + p); out.flush()
threading.Thread(target=up, daemon=True).start()
inp = sys.stdin.buffer
while True:
    h = inp.read(2)
    if len(h) < 2: break
    n = struct.unpack(">H", h)[0]
    os.write(fd, inp.read(n))
