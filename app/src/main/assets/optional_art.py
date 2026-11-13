"""Keep completed audio when optional cover conversion/embedding fails."""
from yt_dlp.postprocessor.embedthumbnail import EmbedThumbnailPP as _EmbedThumbnailPP
from yt_dlp.utils import PostProcessingError


class EmbedThumbnailPP(_EmbedThumbnailPP):
    def run(self, info):
        try:
            return super().run(info)
        except (PostProcessingError, OSError) as error:
            # Upstream writes a temporary file, replaces the audio only on success.
            self.report_warning(f'Optional cover skipped; audio retained: {error}')
            return [], info
