package cn.termux.ubuntumanager.chroot

/** Script installed in Magisk's persistent data directory and only invoked with validated args. */
internal object ChrootSupervisorScript {
    val content: String = """
        #!/system/bin/sh
        set -u

        BASE='${ChrootContract.BASE_DIRECTORY}'
        IMAGES="${'$'}BASE/images"
        RUNTIME="${'$'}BASE/runtime"
        LOGS="${'$'}BASE/logs"
        BACKUPS="${'$'}BASE/backups"
        CACHE="${'$'}BASE/cache"
        HELPER='${ChrootContract.HELPER_PATH}'

        fail() { /system/bin/printf 'CNTERMUX_ERROR:%s\n' "${'$'}*" >&2; exit 1; }
        valid_name() {
          case "${'$'}1" in
            ''|*[!a-zA-Z0-9_-]*|_*|-*) return 1 ;;
            *) return 0 ;;
          esac
        }
        require_name() { valid_name "${'$'}1" || fail 'invalid instance name'; }
        image_for() { /system/bin/printf '%s/%s.img' "${'$'}IMAGES" "${'$'}1"; }
        run_for() { /system/bin/printf '%s/%s' "${'$'}RUNTIME" "${'$'}1"; }
        root_for() { /system/bin/printf '%s/%s/rootfs' "${'$'}RUNTIME" "${'$'}1"; }
        pid_for() { /system/bin/printf '%s/%s/supervisor.pid' "${'$'}RUNTIME" "${'$'}1"; }
        clear_runtime_state() {
          run="${'$'}(run_for "${'$'}1")"
          /system/bin/rm -f "${'$'}run/supervisor.pid" "${'$'}run/sshd.pid" "${'$'}run/ssh.port" "${'$'}run/ttyd.pid" "${'$'}run/ttyd.port"
        }

        ensure_layout() {
          /system/bin/mkdir -p "${'$'}IMAGES" "${'$'}RUNTIME" "${'$'}LOGS" "${'$'}BACKUPS" "${'$'}CACHE"
          /system/bin/chmod 700 "${'$'}BASE" "${'$'}IMAGES" "${'$'}RUNTIME" "${'$'}LOGS" "${'$'}BACKUPS" "${'$'}CACHE"
        }

        private_mounts() {
          /system/bin/mount none / -o rprivate || fail 'cannot make mounts private'
        }

        mount_instance() {
          name="${'$'}1"
          image="${'$'}(image_for "${'$'}name")"
          root="${'$'}(root_for "${'$'}name")"
          [ -f "${'$'}image" ] || fail 'instance image does not exist'
          /system/bin/mkdir -p "${'$'}root"
          /system/bin/chcon u:object_r:magisk_file:s0 "${'$'}image" 2>/dev/null || true
          /system/bin/mount -t ext4 -o loop,noatime "${'$'}image" "${'$'}root" || fail 'cannot mount ext4 image'
          /system/bin/mkdir -p "${'$'}root/dev" "${'$'}root/dev/pts" "${'$'}root/proc" "${'$'}root/sys" "${'$'}root/run" "${'$'}root/tmp"
          /system/bin/mount --bind /dev "${'$'}root/dev" || fail 'cannot bind /dev'
          /system/bin/mount -t devpts devpts "${'$'}root/dev/pts" -o mode=620,ptmxmode=666 || fail 'cannot mount devpts'
          /system/bin/mount -t proc proc "${'$'}root/proc" || fail 'cannot mount proc'
          /system/bin/mount --bind /sys "${'$'}root/sys" || fail 'cannot bind /sys'
        }

        cleanup_mounts() {
          name="${'$'}1"
          root="${'$'}(root_for "${'$'}name")"
          for target in "${'$'}root/dev/pts" "${'$'}root/dev" "${'$'}root/proc" "${'$'}root/sys" "${'$'}root"; do
            /system/bin/umount "${'$'}target" 2>/dev/null || /system/bin/umount -l "${'$'}target" 2>/dev/null || true
          done
        }

        kill_rooted() {
          name="${'$'}1"
          signal="${'$'}2"
          root="${'$'}(root_for "${'$'}name")"
          '${ChrootContract.SPARSE_COPY_PATH}' --kill-rooted "${'$'}root" "${'$'}signal" >/dev/null 2>&1 || true
        }

        is_running() {
          name="${'$'}1"
          pidfile="${'$'}(pid_for "${'$'}name")"
          [ -f "${'$'}pidfile" ] || return 1
          pid="${'$'}(/system/bin/head -n 1 "${'$'}pidfile" 2>/dev/null)"
          case "${'$'}pid" in ''|*[!0-9]*) return 1 ;; esac
          kill -0 "${'$'}pid" 2>/dev/null || return 1
          process_cmd="${'$'}(/system/bin/tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null)"
          case "${'$'}process_cmd" in *"chroot-supervisor.sh run ${'$'}name "*) return 0 ;; *) return 1 ;; esac
        }

        run_instance() {
          name="${'$'}1"; port="${'$'}2"
          require_name "${'$'}name"
          case "${'$'}port" in ''|*[!0-9]*) fail 'invalid SSH port' ;; esac
          private_mounts
          ensure_layout
          run="${'$'}(run_for "${'$'}name")"
          root="${'$'}(root_for "${'$'}name")"
          /system/bin/mkdir -p "${'$'}run"
          /system/bin/printf '%s\n' "${'$'}${'$'}" > "${'$'}run/supervisor.pid"
          /system/bin/printf '%s\n' "${'$'}port" > "${'$'}run/ssh.port"
          /system/bin/chmod 600 "${'$'}run/supervisor.pid"
          mount_instance "${'$'}name"
          child=''
          shutdown_instance() {
            [ -n "${'$'}child" ] && kill -TERM "${'$'}child" 2>/dev/null || true
            kill_rooted "${'$'}name" TERM
            /system/bin/sleep 1
            kill_rooted "${'$'}name" KILL
            cleanup_mounts "${'$'}name"
            clear_runtime_state "${'$'}name"
            exit 0
          }
          trap shutdown_instance HUP INT TERM
          /system/bin/chroot "${'$'}root" /usr/bin/env -i \
            HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TERM=xterm-256color \
            LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TMPDIR=/tmp \
            /bin/bash -lc "mkdir -p /run/sshd; exec /usr/sbin/sshd -D -e -p ${'$'}port" \
            >> "${'$'}LOGS/${'$'}name.log" 2>&1 &
          child="${'$'}!"
          /system/bin/printf '%s\n' "${'$'}child" > "${'$'}run/sshd.pid"
          set +e
          wait "${'$'}child"
          result="${'$'}?"
          set -e
          child=''
          cleanup_mounts "${'$'}name"
          clear_runtime_state "${'$'}name"
          exit "${'$'}result"
        }

        start_instance() {
          name="${'$'}1"; port="${'$'}2"
          require_name "${'$'}name"
          if is_running "${'$'}name"; then /system/bin/printf 'ALREADY_RUNNING\n'; return 0; fi
          /system/bin/rm -f "${'$'}(pid_for "${'$'}name")"
          /system/bin/mkdir -p "${'$'}(run_for "${'$'}name")"
          /system/bin/nohup /system/bin/setsid /system/bin/unshare -m /system/bin/sh "${'$'}HELPER" run "${'$'}name" "${'$'}port" \
            </dev/null >> "${'$'}LOGS/${'$'}name-supervisor.log" 2>&1 &
          attempt=0
          while [ "${'$'}attempt" -lt 80 ]; do
            if is_running "${'$'}name"; then /system/bin/printf 'STARTED\n'; return 0; fi
            /system/bin/sleep 0.1
            attempt="${'$'}((attempt + 1))"
          done
          /system/bin/tail -n 30 "${'$'}LOGS/${'$'}name-supervisor.log" >&2 2>/dev/null || true
          fail 'instance supervisor did not start'
        }

        stop_instance() {
          name="${'$'}1"; require_name "${'$'}name"
          if ! is_running "${'$'}name"; then
            clear_runtime_state "${'$'}name"
            /system/bin/printf 'ALREADY_STOPPED\n'; return 0
          fi
          pid="${'$'}(/system/bin/head -n 1 "${'$'}(pid_for "${'$'}name")")"
          kill -TERM "${'$'}pid" 2>/dev/null || true
          attempt=0
          while [ "${'$'}attempt" -lt 100 ]; do
            if ! is_running "${'$'}name"; then
              /system/bin/rm -f "${'$'}(pid_for "${'$'}name")"
              /system/bin/printf 'STOPPED\n'; return 0
            fi
            /system/bin/sleep 0.1
            attempt="${'$'}((attempt + 1))"
          done
          kill_rooted "${'$'}name" KILL
          kill -KILL "${'$'}pid" 2>/dev/null || true
          clear_runtime_state "${'$'}name"
          /system/bin/printf 'FORCE_STOPPED\n'
        }

        one_shot() {
          name="${'$'}1"; encoded="${'$'}2"
          require_name "${'$'}name"
          private_mounts
          mount_instance "${'$'}name"
          root="${'$'}(root_for "${'$'}name")"
          cmd="/run/cntermux-command-${'$'}${'$'}.sh"
          /system/bin/printf '%s' "${'$'}encoded" | /system/bin/base64 -d > "${'$'}root${'$'}cmd" || {
            cleanup_mounts "${'$'}name"; fail 'cannot decode command'
          }
          /system/bin/chmod 700 "${'$'}root${'$'}cmd"
          /system/bin/chroot "${'$'}root" /usr/bin/env -i \
            HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TERM=xterm-256color \
            LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TMPDIR=/tmp \
            /bin/bash "${'$'}cmd"
          result="${'$'}?"
          /system/bin/rm -f "${'$'}root${'$'}cmd"
          cleanup_mounts "${'$'}name"
          return "${'$'}result"
        }

        exec_instance() {
          name="${'$'}1"; encoded="${'$'}2"; require_name "${'$'}name"
          if ! is_running "${'$'}name"; then
            /system/bin/unshare -m /system/bin/sh "${'$'}HELPER" oneshot "${'$'}name" "${'$'}encoded"
            return "${'$'}?"
          fi
          root="${'$'}(root_for "${'$'}name")"
          cmd="/run/cntermux-command-${'$'}${'$'}.sh"
          attempt=0
          while [ "${'$'}attempt" -lt 2 ]; do
            is_running "${'$'}name" || fail 'instance stopped while preparing command'
            pid="${'$'}(/system/bin/head -n 1 "${'$'}(pid_for "${'$'}name")")"
            marker="${'$'}(run_for "${'$'}name")/exec-entered-${'$'}${'$'}-${'$'}attempt"
            /system/bin/rm -f "${'$'}marker"
            /system/bin/nsenter -t "${'$'}pid" -m -- /system/bin/sh -c '
              root="${'$'}1"
              cmd="${'$'}2"
              encoded="${'$'}3"
              marker="${'$'}4"
              target="${'$'}{root}${'$'}{cmd}"
              [ -d "${'$'}root/run" ] || exit 125
              cleanup() { /system/bin/rm -f "${'$'}target"; }
              trap cleanup EXIT
              /system/bin/printf "%s" "${'$'}encoded" | /system/bin/base64 -d > "${'$'}target" || exit 125
              /system/bin/chmod 700 "${'$'}target" || exit 125
              /system/bin/printf "entered\n" > "${'$'}marker" || exit 125
              /system/bin/chroot "${'$'}root" /usr/bin/env -i \
                HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TERM=xterm-256color \
                LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TMPDIR=/tmp \
                /bin/bash "${'$'}cmd"
              exit "${'$'}?"
            ' cntermux "${'$'}root" "${'$'}cmd" "${'$'}encoded" "${'$'}marker"
            result="${'$'}?"
            if [ -f "${'$'}marker" ]; then
              /system/bin/rm -f "${'$'}marker"
              return "${'$'}result"
            fi
            /system/bin/rm -f "${'$'}marker"
            attempt="${'$'}((attempt + 1))"
            /system/bin/sleep 0.1
          done
          fail 'instance mount namespace changed during command setup'
        }

        install_instance() {
          name="${'$'}1"; archive="${'$'}2"; require_name "${'$'}name"
          image="${'$'}(image_for "${'$'}name")"
          [ ! -e "${'$'}image" ] || fail 'instance already exists'
          [ -f "${'$'}archive" ] || fail 'Ubuntu Base archive is missing'
          /system/bin/truncate -s '${ChrootContract.DEFAULT_IMAGE_SIZE}' "${'$'}image" || fail 'cannot create image'
          /system/bin/mke2fs -q -F -t ext4 -m 0 -L "cn-${'$'}name" "${'$'}image" || { /system/bin/rm -f "${'$'}image"; fail 'cannot format image'; }
          /system/bin/chcon u:object_r:magisk_file:s0 "${'$'}image" 2>/dev/null || true
          private_mounts
          mount_instance "${'$'}name"
          root="${'$'}(root_for "${'$'}name")"
          /system/bin/tar -xzf "${'$'}archive" -C "${'$'}root" || {
            cleanup_mounts "${'$'}name"; /system/bin/rm -f "${'$'}image"; fail 'cannot extract Ubuntu Base'
          }
          /system/bin/printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "${'$'}root/etc/resolv.conf"
          /system/bin/printf '%s\n' "${'$'}name" > "${'$'}root/etc/hostname"
          /system/bin/printf '127.0.0.1 localhost\n127.0.1.1 %s\n' "${'$'}name" > "${'$'}root/etc/hosts"
          /system/bin/chroot "${'$'}root" /usr/bin/env -i \
            HOME=/root USER=root LOGNAME=root SHELL=/bin/bash DEBIAN_FRONTEND=noninteractive TMPDIR=/tmp \
            LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            /bin/bash -lc "apt-get update && apt-get install -y --no-install-recommends openssh-server tmux ttyd nmap iproute2 procps ca-certificates zstd && apt-get clean"
          result="${'$'}?"
          if [ "${'$'}result" -eq 0 ]; then
            /system/bin/chroot "${'$'}root" /usr/bin/env -i \
              HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TMPDIR=/tmp \
              LANG=C.UTF-8 LC_ALL=C.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              /bin/bash -lc "printf 'root:${ChrootContract.DEFAULT_ROOT_PASSWORD}\\n' | /usr/sbin/chpasswd; /usr/bin/ssh-keygen -A; /usr/bin/mkdir -p /run/sshd; printf 'PermitRootLogin yes\\nPasswordAuthentication yes\\nKbdInteractiveAuthentication no\\nUsePAM no\\n' > /etc/ssh/sshd_config.d/99-cntermux.conf; /usr/bin/chmod 600 /etc/ssh/sshd_config.d/99-cntermux.conf"
            result="${'$'}?"
          fi
          cleanup_mounts "${'$'}name"
          if [ "${'$'}result" -ne 0 ]; then /system/bin/rm -f "${'$'}image"; fail 'Ubuntu package provisioning failed'; fi
          /system/bin/printf 'INSTALLED\n'
        }

        action="${'$'}{1:-}"
        ensure_layout
        case "${'$'}action" in
          bootstrap) /system/bin/printf 'CHROOT_BACKEND_${ChrootContract.VERSION}\n' ;;
          run) run_instance "${'$'}2" "${'$'}3" ;;
          start) start_instance "${'$'}2" "${'$'}3" ;;
          stop) stop_instance "${'$'}2" ;;
          status) if is_running "${'$'}2"; then /system/bin/printf 'RUNNING\n'; else /system/bin/printf 'STOPPED\n'; fi ;;
          exec) exec_instance "${'$'}2" "${'$'}3" ;;
          oneshot) one_shot "${'$'}2" "${'$'}3" ;;
          install) /system/bin/unshare -m /system/bin/sh "${'$'}HELPER" install-inner "${'$'}2" "${'$'}3" ;;
          install-inner) install_instance "${'$'}2" "${'$'}3" ;;
          *) fail 'unknown action' ;;
        esac
    """.trimIndent() + "\n"
}
