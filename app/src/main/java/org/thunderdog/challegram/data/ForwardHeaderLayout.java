package org.thunderdog.challegram.data;

/** Width allocation for the source name and metadata of a forwarded message. */
final class ForwardHeaderLayout {
  final boolean metadataOnNewLine;
  final int nameMaxWidth;
  final int timeMaxWidth;

  ForwardHeaderLayout (int availableWidth, float nameWidth, float timeWidth,
                       float countersWidth, int nameGap, int minimumNameWidth) {
    availableWidth = Math.max(0, availableWidth);
    int inlineNameWidth = (int) Math.floor(availableWidth - timeWidth - countersWidth - nameGap);
    // Short names can stay inline; long names should retain a readable prefix before the ellipsis.
    metadataOnNewLine = inlineNameWidth < Math.min(nameWidth, minimumNameWidth);
    nameMaxWidth = metadataOnNewLine ? availableWidth : Math.max(0, inlineNameWidth);
    timeMaxWidth = Math.max(0, (int) Math.floor(availableWidth - countersWidth -
      (metadataOnNewLine ? 0 : nameMaxWidth + nameGap)));
  }
}
