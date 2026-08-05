Photographic cluster artwork. Present, so the dashboard composites the real
panel instead of drawing its own approximation.

  cluster_face.png       photograph of the panel with the needles removed,
                         daylight base blended with the illuminated overlay,
                         tachometer redline baked in. 2020x620.
  cluster_geometry.json  dial centres, scale angles and needle outlines, in
                         face pixels; also the counter windows painted over
                         and the trip reset knob's touch target.

Needles are not images: they are flat tapered shapes, so the app draws them
from the outlines in the geometry file. Delete either file and the app falls
back to drawing every gauge itself.

Source: docs/cluster-asset-prompt.md describes what to produce; the current
artwork came from that prompt, with the face and glow layers composited and
the geometry read off the source overlay.
