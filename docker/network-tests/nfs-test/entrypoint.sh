#!/bin/sh
set -e
mkdir -p /run/dbus /var/run/ganesha
rpcbind -w
# O Ganesha so registra NFS/MOUNT no rpcbind na subida: espera ele responder antes.
for _ in 1 2 3 4 5 6 7 8 9 10; do
    rpcinfo -p >/dev/null 2>&1 && break
    sleep 0.5
done
dbus-daemon --system --fork 2>/dev/null || true
exec ganesha.nfsd -F -L /dev/stdout -N NIV_EVENT -f /etc/ganesha/ganesha.conf
