"""Framework-free check with real yt-dlp/FFmpeg: absent and corrupt artwork preserve audio.
Run with yt-dlp importable (device's bundled Python works); args: plugin.py ffmpeg-path work-dir.
Creates only private test fixtures, never touches downloaded user files.
"""
import importlib.util
import json
import pathlib
import subprocess
import sys

plugin, ffmpeg, work = sys.argv[1:]
work = pathlib.Path(work)
work.mkdir(parents=True, exist_ok=True)
spec = importlib.util.spec_from_file_location('optional_art', plugin)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
from yt_dlp import YoutubeDL

source = work / 'fixture.mp3'
subprocess.run([ffmpeg, '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', '-c:a', 'libmp3lame', str(source)], check=True, capture_output=True)
original = source.read_bytes()
with YoutubeDL({'ffmpeg_location': ffmpeg}) as ydl:
    pp = module.EmbedThumbnailPP(ydl)
    info = {'filepath': str(source), 'ext': 'mp3', 'vcodec': 'none', 'acodec': 'mp3'}
    pp.run(dict(info, thumbnails=[]))
    assert source.read_bytes() == original
    bad = work / 'bad.jpg'
    bad.write_bytes(b'not an image')
    pp.run(dict(info, thumbnails=[{'filepath': str(bad)}]))
    assert source.read_bytes() == original
    # Programming errors must not become successful downloads.
    try:
        pp.run({'ext': 'mp3'})
    except KeyError:
        pass
    else:
        raise AssertionError('Unexpected errors swallowed')
print('PASS: absent/corrupt cover retains byte-identical MP3; programming errors propagate')
