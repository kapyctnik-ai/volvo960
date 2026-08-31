Dial faces cut out of the photograph of the real 960 cluster, used one at a
time by the portrait tile layout.

  piece_speedo.png  490x490, hub at (245, 245)
  piece_tacho.png   420x420, hub at (200, 204)
  piece_clock.png   410x410, hub at (198, 192)

Each piece is a circular crop with a transparent surround, framed on the
printing rather than on the hub — which is why the hub is not the centre of the
image. Those hub coordinates live in ui/dash/DialArt.kt and must be updated
together with the artwork.

Needles are not images: they are flat tapered shapes drawn by DialView from the
outlines in DialArt, so they stay sharp at any size and cost nothing to move.

The crops were taken from the earlier full-panel composite (see git history and
docs/cluster-asset-prompt.md) at these centres and radii, in that image's
pixels: clock (245,386) r205, speedo (748,370) r245, tacho (1262,375) r210.
