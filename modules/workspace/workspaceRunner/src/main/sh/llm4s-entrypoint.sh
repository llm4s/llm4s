#!/bin/sh
# The workspace-runner image's entry point (design §4.39, #2008, #870).
#
# A cgroup per command needs the container's cgroup v2 hierarchy writable, which Docker mounts read-only. When the
# container has a private cgroup namespace (its own cgroup reads as the root, "0::/") and was started with
# CAP_SYS_ADMIN and without AppArmor's docker-default profile (`--cgroupns=private --cap-add SYS_ADMIN
# --security-opt apparmor=unconfined`), the mount is made read-write here. Otherwise the remount fails, quietly, and
# the runner reports at startup that commands' daemons can outlive them.
#
# No egress (#870): with LLM4S_NO_EGRESS=1, which ContainerisedWorkspace sets when the sandbox allows no network, and
# CAP_NET_ADMIN (`--cap-add NET_ADMIN`), the container's firewall lets out only loopback and the replies to
# connections the container accepted (the runner's published port), for IPv4 and IPv6. Nothing in the container, the
# runner included, can then open a connection out. When the firewall cannot be set, the container does not start:
# the sandbox asked for no network, and a runner that started anyway would not give it.
#
# CAP_SYS_ADMIN and CAP_NET_ADMIN are then dropped, from the bounding and inheritable sets, before the runner starts:
# neither the runner nor any command it starts ever holds them, so neither can undo the firewall.
if [ "$(cat /proc/self/cgroup 2>/dev/null)" = "0::/" ] && grep -q ' /sys/fs/cgroup cgroup2 ro[ ,]' /proc/mounts; then
  mount -o remount,bind,rw /sys/fs/cgroup 2>/dev/null || true
fi

no_egress() {
  "$1" -w -A OUTPUT -o lo -j ACCEPT &&
    "$1" -w -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT &&
    "$1" -w -P OUTPUT DROP
}

if [ "${LLM4S_NO_EGRESS:-}" = "1" ]; then
  if ! no_egress iptables; then
    echo "llm4s-entrypoint: LLM4S_NO_EGRESS=1, but the container's IPv4 egress could not be blocked; start it with" \
      "--cap-add NET_ADMIN, or allow the sandbox network (networkAllowed = true) (#870)" >&2
    exit 78
  fi
  # a container without IPv6 (no interface listed: procfs reports every file's size as 0, so it is read, not
  # tested with -s) has no IPv6 egress to block; one with it fails closed like IPv4
  if grep -q . /proc/net/if_inet6 2>/dev/null && ! no_egress ip6tables; then
    echo "llm4s-entrypoint: LLM4S_NO_EGRESS=1, but the container's IPv6 egress could not be blocked (#870)" >&2
    exit 78
  fi
  export LLM4S_EGRESS=blocked
fi

exec setpriv --bounding-set=-sys_admin,-net_admin --inh-caps=-sys_admin,-net_admin -- "$@"
