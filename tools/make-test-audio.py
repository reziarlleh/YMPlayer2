"""Generate our own quiet PCM tones for explicitly authorized emulator checks."""
import argparse
import math
from pathlib import Path
import struct
import wave

parser = argparse.ArgumentParser()
parser.add_argument('output', type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
for name, frequency in [('01 - Evening route', 440), ('02 - City lights', 660)]:
    with wave.open(str(args.output / (name + '.wav')), 'wb') as audio:
        audio.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        audio.writeframes(b''.join(struct.pack('<h', int(1500 * math.sin(2 * math.pi * frequency * i / 16000))) for i in range(16000 * 45)))
print('Generated two 45-second PCM fixtures.')
