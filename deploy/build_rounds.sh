#!/bin/bash
# Заходы в Colab подряд, пока APK не соберётся.
#
# Бесплатную ВМ утилизируют через 35-60 минут CPU-нагрузки — за один заход полный нативный
# билд не успевает. Зато ccache переезжает между заходами (см. build_on_colab.sh), поэтому
# каждый следующий компилирует только то, что не успел предыдущий.
#
# Использование: deploy/build_rounds.sh [макс_заходов]
set -u

REPO="${REPO:-/root/projects/telegram-android}"
ART=/root/build-artifacts
MAX="${1:-5}"
cd "$REPO"

for i in $(seq 1 "$MAX"); do
    LOG="$ART/colab-round$i-$(date +%Y%m%d-%H%M%S).log"
    echo "=== ЗАХОД $i/$MAX -> $LOG"
    DEADLINE_SEC="${DEADLINE_SEC:-5400}" bash deploy/build_on_colab.sh > "$LOG" 2>&1
    RC=$?
    CACHE_SIZE=$(du -m "$ART/tg-ccache.tgz" 2>/dev/null | cut -f1 || echo 0)
    if [ "$RC" -eq 0 ]; then
        echo "=== ЗАХОД $i: ГОТОВО, APK собран (ccache ${CACHE_SIZE}МБ)"
        grep -E "^apk:|^sha256:" "$LOG"
        exit 0
    fi
    # Локальные отказы (разъехавшаяся ветка, пустой патч, токен в патче) повторять бессмысленно.
    if grep -qE "ОТКАЗ: (локальная|патч|в патч)" "$LOG"; then
        echo "=== ЗАХОД $i: отказ ещё до ВМ — цикл остановлен"
        tail -3 "$LOG"
        exit 1
    fi
    echo "=== ЗАХОД $i: ВМ не дожила (ccache ${CACHE_SIZE}МБ), иду на следующий заход"
    sleep 60
done

echo "=== Заходы кончились ($MAX), APK так и нет"
exit 1
