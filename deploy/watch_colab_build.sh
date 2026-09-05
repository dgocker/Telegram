#!/bin/bash
# Досматривает сборку, запущенную deploy/build_on_colab.sh, после смерти оркестратора.
# Раннер на ВМ detached — он пережил падение локального скрипта, качаем результат отсюда.
S="${1:-tgvpn-20260903-115216}"
STAMP="${2:-20260903-115216}"
ART=/root/build-artifacts
TMP=/tmp/claude-0/-root/f32a7658-1c6e-432c-b832-ea1c6691d90d/scratchpad
APK="$ART/telegram-vpn-$STAMP.apk"
DEADLINE=$(( $(date +%s) + ${DEADLINE_SEC:-10800} ))
MISSES=0

while :; do
    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
        echo "СБОРКА: дедлайн 3ч истёк, сдаюсь"
        exit 1
    fi
    rm -f "$TMP/progress.txt"
    if timeout 60 colab download -s "$S" /content/progress.txt "$TMP/progress.txt" >/dev/null 2>&1; then
        MISSES=0
    else
        MISSES=$((MISSES + 1))
        # Без этого наблюдатель 03.09 простоял 3 часа над давно утилизированной ВМ.
        if [ "$MISSES" -ge 5 ]; then
            echo "СБОРКА: сессия $S не отвечает 5 опросов подряд — ВМ утилизирована, сборка потеряна"
            exit 1
        fi
    fi
    LINE=$(tail -1 "$TMP/progress.txt" 2>/dev/null || true)
    case "$LINE" in
        КОД=0)
            echo "СБОРКА: gradle отработал, качаю APK"
            if timeout 900 colab download -s "$S" \
                /content/tg/TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk "$APK" >/dev/null 2>&1; then
                echo "СБОРКА ГОТОВА: $APK ($(du -h "$APK" | cut -f1), sha256 $(sha256sum "$APK" | cut -d' ' -f1))"
            else
                echo "СБОРКА: APK не скачался — сессия $S ещё жива, качать вручную"
                exit 1
            fi
            timeout 300 colab download -s "$S" /content/build.log "$ART/build-$STAMP.log" >/dev/null 2>&1 || true
            colab stop -s "$S" >/dev/null 2>&1 || true
            exit 0
            ;;
        КОД=*)
            echo "СБОРКА УПАЛА: $LINE — качаю логи"
            timeout 300 colab download -s "$S" /content/build.log "$ART/build-$STAMP-FAILED.log" >/dev/null 2>&1 || true
            timeout 60 colab download -s "$S" /content/runner.log "$ART/runner-$STAMP-FAILED.log" >/dev/null 2>&1 || true
            colab stop -s "$S" >/dev/null 2>&1 || true
            exit 1
            ;;
    esac
    sleep 180
done
