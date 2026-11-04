#!/usr/bin/env python3
"""
ElevenTV 启动音效合成器（纯标准库，无 numpy/scipy）。

声音设计原则：贴合启动动画的时间表（SplashMotion.kt，预算 1000ms）
  - 低频 pad（双正弦，174Hz/146.8Hz 小三度，柔和起音 + 缓慢衰减）铺底，给「品牌登场」的重量感
  - 逐字母的「叮」声：8 个 tick，起始时刻与 8 个字母淡入的时刻逐一对齐
    （LETTER_START=0.10, LETTER_STEP=0.072，按 1000ms 预算换算）
  - 收尾落位音：一个更亮的上滑「叮」+ 短促的低频「咚」，与整组停稳的时刻（位移 0.74）对齐
输出：44.1kHz、16-bit、立体声 WAV，总时长 1.1s（末尾留 100ms 自然收边）
"""
import array
import math
import os
import struct
import wave

SR = 44100          # 采样率
DUR = 1.1           # 总时长（秒），含末尾留白
N = int(SR * DUR)   # 总采样数

# --- 动画时间表（与 SplashMotion.kt 一致，单位：秒）---
BUDGET = 1.0
LETTER_START = 0.10
LETTER_STEP = 0.072
SLIDE_END = 0.74        # 整组停稳 / 最后一音落位的时刻
N_LETTERS = 8           # "ElevenTV"

left = [0.0] * N
right = [0.0] * N


def add(t0, t1, ch, amp, sample):
    """把 sample(i)->float 叠加到 ch（0/1）的 [t0, t1) 区间。"""
    i0 = int(t0 * SR)
    i1 = int(t1 * SR)
    for i in range(max(0, i0), min(N, i1)):
        if ch == 0:
            left[i] += amp * sample(i - i0, t1 - t0)
        else:
            right[i] += amp * sample(i - i0, t1 - t0)


def pad(freq, t0, t1, amp):
    """低频 pad：双正弦（主频 + 高八度弱分量）+ 柔和起音 + 缓慢衰减。"""
    dur = t1 - t0
    f1 = freq
    f2 = freq * 1.5
    attack = min(0.12, dur * 0.5)

    def s(i, d):
        t = i / SR
        a = math.sin(math.pi * (t / attack)) ** 2 if t < attack else 1.0
        env = a * math.exp(-t / (d * 1.6))
        return (0.8 * math.sin(2 * math.pi * f1 * t)
                + 0.25 * math.sin(2 * math.pi * f2 * t)) * env
    add(t0, t1, 0, amp, s)
    add(t0, t1, 1, amp * 0.9, s)


def ding(freq, t0, dur, amp):
    """「叮」：单音 + 弱八度泛音 + 指数衰减，起音极短避免爆点。"""
    f1 = freq
    f2 = freq * 2.0
    a0 = 0.004

    def s(i, d):
        t = i / SR
        env = (math.sin(math.pi * t / a0) ** 2 if t < a0 else 1.0) * math.exp(-t / (d * 0.55))
        return (math.sin(2 * math.pi * f1 * t)
                + 0.35 * math.sin(2 * math.pi * f2 * t)) * env * 0.6
    add(t0, t0 + dur, 0, amp, s)
    add(t0, t0 + dur, 1, amp, s)


def thump(t0, dur, amp):
    """「咚」：低频短促冲击，落在收尾，给整组「坐住」。"""
    f0 = 70.0
    a0 = 0.006

    def s(i, d):
        t = i / SR
        env = math.exp(-t / (d * 0.35))
        env *= (math.sin(math.pi * t / a0) ** 2 if t < a0 else 1.0)
        return math.sin(2 * math.pi * f0 * t) * env
    add(t0, t0 + dur, 0, amp, s)
    add(t0, t0 + dur, 1, amp * 0.85, s)


# 1) 低频 pad：从 0 起，持续到收尾，给整体一个温暖的底
pad(174.6, 0.0, 0.78, 0.16)          # F3
pad(146.8, 0.05, 0.78, 0.10)        # D3，小三度叠出来更有重量

# 2) 逐字母的「叮」：起始时刻与 8 个字母淡入逐一对齐，音高从低到高（上行，配合左移的动势）
letter_notes = [523.25, 587.33, 659.25, 698.46, 783.99, 880.00, 987.77, 1046.50]
for i, f in enumerate(letter_notes):
    t = LETTER_START + i * LETTER_STEP          # 与 SplashMotion.letter(i) 起点一致
    ding(f, t, 0.28, 0.10 + 0.012 * i)         # 越靠后越亮、越响一点

# 3) 收尾：整组停稳（SLIDE_END）时，一个更亮的上滑「叮」+ 一个「咚」坐住
ding(1318.5, SLIDE_END, 0.42, 0.16)            # C6，收尾最高音
thump(SLIDE_END, 0.30, 0.22)                   # 低频落位

# --- 软削峰（限幅 0.9）---
peak = max(abs(v) for v in left + right) or 1.0
gain = 0.9 / peak if peak > 0.9 else 1.0
left = [v * gain for v in left]
right = [v * gain for v in right]

# --- 写 WAV ---
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eleventv_splash.wav")
pcm = array.array("h")
for i in range(N):
    l = int(max(-1.0, min(1.0, left[i])) * 32767)
    r = int(max(-1.0, min(1.0, right[i])) * 32767)
    pcm.append(l)
    pcm.append(r)

with wave.open(out, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())

size = os.path.getsize(out)
print(f"written {out}  ({size/1024:.1f} KB)  {N/SR:.2f}s  stereo 16bit  peak={peak:.3f} gain={gain:.3f}")
