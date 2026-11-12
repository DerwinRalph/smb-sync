#!/bin/bash
# Runs the unpacked Samba as the current user on port 4450, guest access, one share "test".
T=~/smbsync-test
D=$T/run
mkdir -p "$D" "$D/ncalrpc" "$T/share"
chmod 777 "$T/share"
cat > "$T/smb.conf" <<EOF
[global]
private dir = $D
interfaces = 127.0.0.1 eth0
bind interfaces only = no
smb ports = 4450
pid directory = $D
lock directory = $D
state directory = $D
cache directory = $D
ncalrpc dir = $D/ncalrpc
log file = $D/log.smbd
smb passwd file = $D/smbpasswd
security = user
map to guest = Bad User
guest account = $(id -un)
load printers = no
printing = bsd
disable spoolss = yes
usershare max shares = 0
server min protocol = SMB2
[test]
path = $T/share
read only = no
guest ok = yes
force user = $(id -un)
create mask = 0666
force create mode = 0666
directory mask = 0777
force directory mode = 0777
EOF
# smbd in foreground mode quits when its stdin closes, so keep one open.
sleep infinity | env LD_LIBRARY_PATH="$(cat $T/libpath.txt)" "$(cat $T/smbd.txt)" -F --no-process-group --debug-stdout -d ${SMBD_DEBUG:-1} -s "$T/smb.conf"
