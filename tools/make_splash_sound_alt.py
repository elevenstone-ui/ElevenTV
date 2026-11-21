#!/usr/bin/env python3
"""
ElevenTV 启动音效·三备选合成器（纯标准库，无 numpy/scipy）。

沿用 make_splash_sound.py 的工程约定：
  - 44.1kHz / 16bit / 立体声 / 总时长 1.1s（末尾 100ms 自然收边）
  - 8 个字母淡入点与 SplashMotion.kt 逐一对齐：
      LETTER_START = 0.10s, LETTER_STEP = 0.072s  ->  每个 tick 的起音时刻
      SLIDE_END    = 0.74s                        ->  收尾落位音
  - 软削峰（限幅 0.9）

产出 3 个 WAV，风格各异，供试听定稿：
  [A] digital  数字感·清脆滴答：去掉垫音，只留 8 个对齐字母的干爽 tick + 收尾一击。
  [B] pulse    律动感·心跳脉冲：每个字母位置一个低频脉冲（心跳律动），收尾重音坐住。
  [C] cinematic 电影感·温暖垫音：保留低频 pad + 上行叮声 + 收尾咚声，做精修（更柔、收得干净）。

用法：
  python3 make_splash_sound_alt.py            # 生成全部 3 个 .wav
  python3 make_splash_sound_alt.py digital    # 只生成指定风格
"""
import array
import math
import os
import sys
import wave

SR = 44100          # 采样率
DUR = 1.1          # 总时长（秒），含末尾留白
N = int(SR * DUR)  # 总采样数

# --- 动画时间表（与 SplashMotion.kt 一致，单位：秒）---
BUDGET = 1.0
LETTER_START = 0.10
LETTER_STEP = 0.072
SLIDE_END = 0.74         # 整组停稳 / 收尾落位
N_LETTERS = 8            # "ElevenTV"


class Synth:
    """单风格混音缓冲。"""

    def __init__(self):
        self.left = [0.0] * N
        self.right = [0.0] * N

    def add(self, t0, t1, ch, amp, sample):
        i0 = int(t0 * SR)
        i1 = int(t1 * SR)
        for i in range(max(0, i0), min(N, i1)):
            if ch == 0:
                self.left[i] += amp * sample(i - i0, t1 - t0)
            else:
                self.right[i] += amp * sample(i - i0, t1 - t0)

    def write(self, path):
        left, right = self.left, self.right
        peak = max(abs(v) for v in left + right) or 1.0
        gain = 0.9 / peak if peak > 0.9 else 1.0
        left = [v * gain for v in left]
        right = [v * gain for v in right]
        pcm = array.array("h")
        for i in range(N):
            pcm.append(int(max(-1.0, min(1.0, left[i])) * 32767))
            pcm.append(int(max(-1.0, min(1.0, right[i])) * 32767))
        with wave.open(path, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        print(f"  -> {path}  ({os.path.getsize(path)/1024:.1f} KB)  "
              f"{N/SR:.2f}s  stereo 16bit  peak={peak:.3f} gain={gain:.3f}")


# ---------- 通用音色 ----------

def tick(synth, freq, t0, dur, amp, bright=0.20):
    """干爽滴答：单音 + 弱高频泛音 + 指数衰减，起音极短避免爆点。bright 控制泛音亮度。"""
    f2 = freq * 2.0
    a0 = 0.003

    def s(i, d):
        t = i / SR
        env = (math.sin(math.pi * t / a0) ** 2 if t < a0 else 1.0) * math.exp(-t / (d * 0.40))
        return (math.sin(2 * math.pi * freq * t)
                + bright * math.sin(2 * math.pi * f2 * t)) * env
    synth.add(t0, t0 + dur, 0, amp, s)
    synth.add(t0, t0 + dur, 1, amp, s)


def pulse(synth, t0, dur, amp, freq=78.0):
    """心跳脉冲：低频短促冲击 + 柔和起音 + 快速衰减。"""
    a0 = 0.010

    def s(i, d):
        t = i / SR
        env = (math.sin(math.pi * t / a0) ** 2 if t < a0 else 1.0) * math.exp(-t / (d * 0.55))
        return math.sin(2 * math.pi * freq * t) * env
    synth.add(t0, t0 + dur, 0, amp, s)
    synth.add(t0, t0 + dur, 1, amp * 0.9, s)


def pad(synth, freq, t0, t1, amp, attack=0.12, release=1.6):
    """低频 pad：主频 + 高八度弱分量，柔和起音 + 缓慢衰减。"""
    f1, f2 = freq, freq * 1.5
    dur = t1 - t0

    def s(i, d):
        t = i / SR
        a = math.sin(math.pi * (t / attack)) ** 2 if t < attack else 1.0
        env = a * math.exp(-t / (d * release))
        return (0.8 * math.sin(2 * math.pi * f1 * t)
                + 0.22 * math.sin(2 * math.pi * f2 * t)) * env
    synth.add(t0, t1, 0, amp, s)
    synth.add(t0, t1, 1, amp * 0.9, s)


def thump(synth, t0, dur, amp, freq=70.0):
    """收尾「咚」：低频重音坐住。"""
    a0 = 0.008

    def s(i, d):
        t = i / SR
        env = math.exp(-t / (d * 0.35))
        env *= (math.sin(math.pi * t / a0) ** 2 if t < a0 else 1.0)
        return math.sin(2 * math.pi * freq * t) * env
    synth.add(t0, t0 + dur, 0, amp, s)
    synth.add(t0, t0 + dur, 1, amp * 0.85, s)


# ---------- 三个风格 ----------

# [A] 数字感·清脆滴答
# 8 个 tick 逐字母上行（音高渐亮），干爽无垫音；收尾一个最高音 + 轻「咚」坐住。
def build_digital():
    s = Synth()
    notes = [880.0, 987.77, 1174.66, 1318.5, 1567.98, 1760.0, 2093.0, 2349.32]
    for i, f in enumerate(notes):
        t = LETTER_START + i * LETTER_STEP
        tick(s, f, t, 0.16, 0.16, bright=0.28)   # 高频干爽，越靠后越亮
    tick(s, 2637.02, SLIDE_END, 0.30, 0.22, bright=0.30)  # 收尾最高音
    thump(s, SLIDE_END, 0.22, 0.16, freq=90.0)                 # 轻「咚」，克制
    return s


# [B] 律动感·心跳脉冲
# 低频脉冲坐底（律动感）+ 每个字母位置一个上行的中高音「亮头」tick（让它听得清），
# 后半程更密更亮；收尾双击「lub-dub」重音 + 一个最高音坐住，整体既有律动又清楚响亮。
def build_pulse():
    s = Synth()
    notes = [440.0, 523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1174.66]
    for i in range(N_LETTERS):
        t = LETTER_START + i * LETTER_STEP
        group2 = i >= 4                       # 后半程更密更亮
        # 低频脉冲坐底（律动）
        pulse(s, t, 0.14, 0.20, freq=82.0 if not group2 else 92.0)
        # 中高音 tick 亮头（让它听得清），越靠后越亮越响
        tick(s, notes[i], t + 0.012, 0.18, 0.16 + (0.03 * i if group2 else 0.018 * i), bright=0.36)
    # 收尾：重音「咚」+ 最高音 tick 坐住（lub-dub）
    thump(s, SLIDE_END, 0.28, 0.30, freq=64.0)
    tick(s, 1568.0, SLIDE_END, 0.30, 0.24, bright=0.40)
    pulse(s, SLIDE_END + 0.09, 0.18, 0.20, freq=72.0)
    return s


# [C] 电影感·温暖垫音（精修现有款）
# 保留低频 pad + 上行叮声 + 收尾咚声；垫音更柔（更低增益、更长起音）、
# 叮声收敛、收尾咚更短，整体「暖但收得干净」。
def build_cinematic():
    s = Synth()
    pad(s, 174.6, 0.0, 0.70, 0.11, attack=0.16)     # F3，更柔更弱
    pad(s, 146.8, 0.06, 0.70, 0.07, attack=0.20)    # D3 小三度，压得比现款更轻
    notes = [523.25, 587.33, 659.25, 698.46, 783.99, 880.00, 987.77, 1046.50]
    for i, f in enumerate(notes):
        t = LETTER_START + i * LETTER_STEP
        tick(s, f, t, 0.22, 0.085 + 0.009 * i, bright=0.35)  # 上行叮，收敛
    tick(s, 1318.5, SLIDE_END, 0.30, 0.13, bright=0.35)      # 收尾最高音（比现款 0.16 更轻）
    thump(s, SLIDE_END, 0.20, 0.18, freq=72.0)               # 「咚」更短更收
    return s


BUILDERS = {
    "digital": ("build_digital", build_digital, "splash_sound_A_digital.wav"),
    "pulse": ("build_pulse", build_pulse, "splash_sound_B_pulse.wav"),
    "cinematic": ("build_cinematic", build_cinematic, "splash_sound_C_cinematic.wav"),
}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    want = sys.argv[1:] if len(sys.argv) > 1 else list(BUILDERS)
    for key in want:
        if key not in BUILDERS:
            print(f"unknown style '{key}', options: {list(BUILDERS)}")
            return 1
        label, fn, outname = BUILDERS[key]
        print(f"[{key}] {label}")
        fn().write(os.path.join(here, outname))
    return 0


if __name__ == "__main__":
    sys.exit(main())
