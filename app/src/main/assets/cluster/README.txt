Drop the photographic cluster artwork here to switch the dashboard from its
drawn fallback to the photo-composited rendering:

  cluster_face.png       dial face, no needles, no hubs
  needle_long.png        speedometer / tachometer / minute hand, pointing up
  needle_short.png       fuel / temperature / hour hand, pointing up
  needle_second.png      optional, clock second hand
  hub.png                optional, centre cap drawn over the needle roots
  cluster_geometry.json  dial centres, radii and angles, in face pixels

See docs/cluster-asset-prompt.md for the exact specification.
Without cluster_face.png and cluster_geometry.json the app draws the gauges
itself and nothing here is required.
