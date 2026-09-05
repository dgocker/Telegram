# @title keep-alive: держим ядро Colab «занятым», иначе бесплатный рантайм гасят по простою
# (03.09: сборка шла в detached-процессе, ядро молчало — ВМ утилизировали посреди билда)
try:
    print(open("/content/progress.txt").read().strip()[:100])
except Exception as e:
    print("нет прогресса:", e)
