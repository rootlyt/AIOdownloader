"""Framework-free classification check. Run: python app/check-social-plugin.py (yt-dlp installed).
Synthetic product cases exercise rejection; they do not establish live photo availability.
"""
import importlib.util
from pathlib import Path
from yt_dlp import YoutubeDL
spec = importlib.util.spec_from_file_location('social_photos', Path(__file__).parent / 'src/main/assets/social_photos.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
with YoutubeDL({'quiet': True}) as y:
    ie = m.InstagramPhotosIE(y)
    candidate = {'url': 'https://s.cdninstagram.com/photo.jpg', 'width': 1080, 'height': 720}
    image = {'pk': '1922949326347663701', 'media_type': 1, 'image_versions2': {'candidates': [candidate]}}
    assert ie._extract_product_media(image)['aio_image'] == candidate['url']
    for kind in (2, None):
        other = dict(image, media_type=kind)
        assert 'aio_image' not in ie._extract_product_media(other)
    assert 'aio_image' not in ie._extract_product_media(dict(image, image_versions2={'candidates': []}))
print('PASS: explicit photos only; video/unknown thumbnails never promoted')
