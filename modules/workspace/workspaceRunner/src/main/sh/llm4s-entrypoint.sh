#!/bin/sh
# The workspace-runner image's entry point (design §4.39, #2008).
#
# A cgroup per command needs the container's cgroup v2 hierarchy writable, which Docker mounts read-only. When the
# container has a private cgroup namespace (its own cgroup reads as the root, "0::/") and was started with
# CAP_SYS_ADMIN and without AppArmor's docker-default profile (`--cgroupns=private --cap-add SYS_ADMIN
# --security-opt apparmor=unconfined`), the mount is made read-write here. Otherwise the remount fails, quietly, and
# the runner reports at startup that commands' daemons can outlive them.
#
# CAP_SYS_ADMIN is then dropped, from the bounding and inheritable sets, before the runner starts: neither the runner
# nor any command it starts ever holds it.
if [ "$(cat /proc/self/cgroup 2>/dev/null)" = "0::/" ] && grep -q ' /sys/fs/cgroup cgroup2 ro[ ,]' /proc/mounts; then
  mount -o remount,bind,rw /sys/fs/cgroup 2>/dev/null || true
fi
exec setpriv --bounding-set=-sys_admin --inh-caps=-sys_admin -- "$@"
