"""Expose Instagram's explicit photo products; never mistake video thumbnails for photos.
Uses the installed extractor's first-party response, not another endpoint/session.
"""
from yt_dlp.extractor.instagram import InstagramIE


class InstagramPhotosIE(InstagramIE, plugin_name='aio_photos'):
    def _extract_product_media(self, product_media):
        if product_media.get('media_type') != 1 and product_media.get('__typename') not in ('XDTGraphImage', 'GraphImage'):
            return super()._extract_product_media(product_media)
        candidates = product_media.get('image_versions2', {}).get('candidates', [])
        candidates = [c for c in candidates if isinstance(c, dict) and isinstance(c.get('url'), str)
                      and isinstance(c.get('width'), int) and isinstance(c.get('height'), int)
                      and c['width'] > 0 and c['height'] > 0]
        if not candidates:
            return super()._extract_product_media(product_media)
        image = max(candidates, key=lambda c: c['width'] * c['height'])
        info = super()._extract_product_media(product_media)
        # Native downloader validates CDN, MIME, size and decoded bounds before saving.
        info.update(aio_image=image['url'], formats=[{
            'url': image['url'], 'ext': 'jpg', 'format_id': 'original-photo',
            'width': image['width'], 'height': image['height'],
        }])
        return info
