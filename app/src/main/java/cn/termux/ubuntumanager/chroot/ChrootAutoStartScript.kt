package cn.termux.ubuntumanager.chroot

/** Root boot script installed into Magisk Alpha's service.d directory. */
internal object ChrootAutoStartScript {
    val content: String = """
        #!/system/bin/sh
        set -u

        SELF='${ChrootContract.AUTO_START_SCRIPT_PATH}'
        CONFIG='${ChrootContract.AUTO_START_CONFIG_PATH}'
        HELPER='${ChrootContract.HELPER_PATH}'
        IMAGES='${ChrootContract.IMAGE_DIRECTORY}'
        LOG='${ChrootContract.AUTO_START_LOG_PATH}'
        VERSION='${ChrootContract.AUTO_START_VERSION}'
        CONFIG_VERSION='${ChrootContract.AUTO_START_CONFIG_VERSION}'

        valid_name() {
          case "${'$'}1" in
            ''|*[!a-zA-Z0-9_-]*|_*|-*) return 1 ;;
            *) return 0 ;;
          esac
        }
        valid_port() {
          case "${'$'}1" in ''|*[!0-9]*) return 1 ;; esac
          [ "${'$'}1" -ge 1024 ] 2>/dev/null && [ "${'$'}1" -le 65535 ] 2>/dev/null
        }
        rotate_log() {
          /system/bin/mkdir -p '${ChrootContract.LOG_DIRECTORY}'
          size="${'$'}(/system/bin/stat -c %s "${'$'}LOG" 2>/dev/null || /system/bin/printf '0')"
          case "${'$'}size" in ''|*[!0-9]*) size=0 ;; esac
          if [ "${'$'}size" -gt 1048576 ]; then
            /system/bin/mv -f "${'$'}LOG" "${'$'}LOG.previous" 2>/dev/null || true
          fi
        }
        log() {
          /system/bin/printf '%s %s\n' "${'$'}(/system/bin/date '+%Y-%m-%d %H:%M:%S')" "${'$'}*" >> "${'$'}LOG"
        }
        start_configured() {
          rotate_log
          [ -x "${'$'}HELPER" ] || { log 'supervisor helper missing'; return 1; }
          [ -r "${'$'}CONFIG" ] || { log 'auto-start config missing'; return 0; }
          header="${'$'}(/system/bin/head -n 1 "${'$'}CONFIG" 2>/dev/null)"
          case "${'$'}header" in
            "${'$'}CONFIG_VERSION|0") log 'auto-start globally disabled'; return 0 ;;
            "${'$'}CONFIG_VERSION|1") ;;
            *) log 'invalid auto-start config header'; return 1 ;;
          esac

          line_number=0
          while IFS='|' read -r name port extra; do
            line_number="${'$'}((line_number + 1))"
            [ "${'$'}line_number" -eq 1 ] && continue
            [ -n "${'$'}name" ] || continue
            if [ -n "${'$'}{extra:-}" ] || ! valid_name "${'$'}name" || ! valid_port "${'$'}port"; then
              log "ignored invalid config row ${'$'}line_number"
              continue
            fi
            if [ ! -f "${'$'}IMAGES/${'$'}name.img" ]; then
              log "ignored missing instance ${'$'}name"
              continue
            fi

            attempt=1
            while [ "${'$'}attempt" -le 3 ]; do
              output="${'$'}("${'$'}HELPER" start "${'$'}name" "${'$'}port" 2>&1)"
              result="${'$'}?"
              if [ "${'$'}result" -eq 0 ]; then
                log "${'$'}name:${'$'}port ${'$'}output"
                break
              fi
              log "${'$'}name:${'$'}port attempt ${'$'}attempt failed: ${'$'}output"
              attempt="${'$'}((attempt + 1))"
              [ "${'$'}attempt" -gt 3 ] || /system/bin/sleep 5
            done
          done < "${'$'}CONFIG"
        }
        wait_and_start() {
          attempt=0
          while [ "${'$'}(/system/bin/getprop sys.boot_completed)" != 1 ] && [ "${'$'}attempt" -lt 180 ]; do
            /system/bin/sleep 2
            attempt="${'$'}((attempt + 1))"
          done
          if [ "${'$'}(/system/bin/getprop sys.boot_completed)" != 1 ]; then
            rotate_log
            log 'Android boot did not complete within 360 seconds'
            return 1
          fi
          /system/bin/sleep 5
          start_configured
        }

        case "${'$'}{1:-}" in
          --version) /system/bin/printf '%s\n' "${'$'}VERSION" ;;
          --run-now) start_configured ;;
          --boot-worker) wait_and_start ;;
          '')
            /system/bin/nohup /system/bin/sh "${'$'}SELF" --boot-worker \
              </dev/null >/dev/null 2>&1 &
            ;;
          *) exit 2 ;;
        esac
    """.trimIndent() + "\n"
}
