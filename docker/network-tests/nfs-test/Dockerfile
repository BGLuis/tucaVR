# Servidor NFSv3 em espaco de usuario (NFS-Ganesha + rpcbind): nao exige o modulo
# `nfsd` do kernel do host. O MOUNT fica na porta 20048, diferente da 2049 do NFS,
# como num nfs-kernel-server (mountd em porta propria) — e o que exercita o
# rpcbind no cliente.
FROM debian:trixie-slim
RUN apt-get update -qq \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nfs-ganesha nfs-ganesha-vfs rpcbind dbus netbase \
 && rm -rf /var/lib/apt/lists/*
COPY ganesha.conf /etc/ganesha/ganesha.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 111 2049 20048
ENTRYPOINT ["/entrypoint.sh"]
