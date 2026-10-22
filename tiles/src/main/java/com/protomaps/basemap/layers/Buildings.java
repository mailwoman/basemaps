package com.protomaps.basemap.layers;

import static com.onthegomap.planetiler.util.Parse.parseDoubleOrNull;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.ForwardingProfile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.util.Parse;
import com.onthegomap.planetiler.util.ZoomFunction;
import com.protomaps.basemap.feature.FeatureId;
import com.protomaps.basemap.feature.Smoothing;
import com.protomaps.basemap.postprocess.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@SuppressWarnings("java:S1192")
public class Buildings implements ForwardingProfile.LayerPostProcessor {

  static final String HEIGHT_KEY = "height";
  static final String MIN_HEIGHT_KEY = "min_height";

  public static final String LAYER_NAME = "buildings";

  /**
   * True when footprints come from Overture, in which case OSM building polygons are skipped to avoid drawing the same
   * building twice.
   */
  private final boolean overtureFootprints;

  public Buildings() {
    this(false);
  }

  public Buildings(boolean overtureFootprints) {
    this.overtureFootprints = overtureFootprints;
  }

  @Override
  public String name() {
    return LAYER_NAME;
  }

  public record Height(Double height, Double min_height) {}


  static final Pattern pattern = Pattern.compile("^\\d+(\\.\\d)?$");

  static final Pattern COMMA_DECIMAL_PATTERN = Pattern.compile(",(?=\\d{1,2}(\\s*[a-zA-Z]*)?$)");

  /**
   * Sanitizes height values by fixing common OSM tagging mistakes. Specifically handles commas used as decimal
   * separators (e.g., "89,10" -> "89.10").
   *
   * @param value the raw height value from OSM
   * @return sanitized value
   */
  static String sanitizeHeightValue(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }

    // Replace comma with period if followed by 1 or 2 digits (optional unit suffix)
    // This handles cases like: "89,1", "89,10", "89,1 m", "89,1m"
    // Pattern explanation:
    // - Match a comma followed by 1-2 digits
    // - Optionally followed by whitespace and/or unit characters (like 'm')
    // - Leave other commas unchanged (e.g., thousands separators like "1,000")
    return COMMA_DECIMAL_PATTERN.matcher(value).replaceFirst(".");
  }

  static Double parseWellFormedDouble(String s) {
    if (pattern.matcher(s).matches()) {
      return parseDoubleOrNull(s);
    }
    return null;
  }

  static Height parseHeight(String osmHeight, String osmLevels, String osmMinHeight) {
    var height = parseDoubleOrNull(sanitizeHeightValue(osmHeight));
    if (height == null) {
      Double levels = parseDoubleOrNull(osmLevels);
      if (levels != null) {
        height = Math.max(levels, 1) * 3 + 2;
      }
    }

    return new Height(height, parseDoubleOrNull(sanitizeHeightValue(osmMinHeight)));
  }

  static int quantizeVal(double val, int step) {
    // special case: if val is very small, we don't want it rounding to zero, so
    // round the smallest values up to the first step.
    if (val < step) {
      return step;
    }

    return (int) Math.round(val / step) * step;
  }

  public void processOsm(SourceFeature sf, FeatureCollector features) {
    if (!overtureFootprints && sf.canBePolygon() && ((sf.hasTag("building") && !sf.hasTag("building", "no")) ||
      (sf.hasTag("building:part") && !sf.hasTag("building:part", "no"))) && !isModeledLandmark(sf)) {

      var height = parseHeight(sf.getString(HEIGHT_KEY), sf.getString("building:levels"), sf.getString(MIN_HEIGHT_KEY));
      Integer minZoom = 11;
      String kind = "building";

      if (sf.hasTag("building:part")) {
        kind = "building_part";
        minZoom = 13;
      }

      var feature = roundedFootprint(sf, features)
        .setId(FeatureId.create(sf))
        // Core Tilezen schema properties
        .setAttr("kind", kind)
        // Core OSM tags for different kinds of places
        .setAttrWithMinzoom("layer", Parse.parseIntOrNull(sf.getString("layer")), 13)
        // NOTE: Height is quantized by zoom in a post-process step
        .setAttr(HEIGHT_KEY, height.height())
        .setAttr("sort_rank", 400)
        .setZoomRange(minZoom, 15)
        .setMinPixelSize(0)
        .setMinPixelSizeOverrides(MERGED_ZOOMS_MIN_PIXEL_SIZE);

      if (kind.equals("building_part")) {
        // We don't need to set WithMinzoom because that's implicate with the ZoomRange
        feature.setAttr("kind_detail", sf.getString("building:part"));
        feature.setAttr(MIN_HEIGHT_KEY, height.min_height());
      }
    }

    // Japanese addresses number the block (番地) rather than the house, so a block number alone also counts as an address.
    if (sf.hasTag("addr:housenumber") || sf.hasTag("addr:block_number")) {
      FeatureCollector.Feature housenumberFeature = null;
      if (sf.isPoint()) {
        housenumberFeature = features.point(this.name());
      } else if (sf.canBePolygon()) {
        housenumberFeature = features.centroid(this.name());
      }
      if (housenumberFeature != null) {
        housenumberFeature
          .setId(FeatureId.create(sf))
          .setAttr("addr_housenumber", sf.getString("addr:housenumber"))
          .setAttr("addr_street", sf.getString("addr:street"))
          .setAttr("addr_block_number", sf.getString("addr:block_number"))
          .setAttr("kind", "address")
          .setMinZoom(15);
      }
    }
  }

  /**
   * Landmarks drawn by a 3D model rather than by extrusion, as {lon, lat, radius in meters}. A building or part whose
   * centroid lies within the radius is left out of the tiles, so its stacked prisms do not show through the model.
   */
  static final double[][] MODELED_LANDMARKS = {
    {139.745433, 35.658581, 45}, // Tokyo Tower
    {139.810700, 35.710063, 45}, // Tokyo Skytree
  };

  private static boolean isModeledLandmark(SourceFeature sf) {
    org.locationtech.jts.geom.Point centroid;
    try {
      centroid = sf.latLonGeometry().getCentroid();
    } catch (GeometryException e) {
      return false;
    }
    for (double[] landmark : MODELED_LANDMARKS) {
      double dx = (centroid.getX() - landmark[0]) * 111_320 * Math.cos(Math.toRadians(landmark[1]));
      double dy = (centroid.getY() - landmark[1]) * 110_540;
      if (Math.hypot(dx, dy) <= landmark[2]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Corner radius of building footprints, in meters. Rounded corners give extruded walls a soft vertical edge.
   */
  static final double CORNER_METERS = 1.2;

  /**
   * Minimum pixel size at z11 and z12, where footprints merge into blocks; from z13 every footprint is emitted.
   * The z11-12 merge drops sub-pixel buildings anyway, so emitting them there only adds encoding work.
   */
  static final ZoomFunction<Number> MERGED_ZOOMS_MIN_PIXEL_SIZE = ZoomFunction.maxZoom(12, 1.0);
  static final int CORNER_PASSES = 2;

  /**
   * Starts a building feature whose footprint has rounded corners, falling back to the source polygon.
   */
  private FeatureCollector.Feature roundedFootprint(SourceFeature sf, FeatureCollector features) {
    try {
      return features.geometry(this.name(),
        Smoothing.roundWorldPolygon(sf.worldGeometry(), CORNER_METERS, CORNER_PASSES));
    } catch (GeometryException e) {
      e.log("Failed to round building footprint");
      return features.polygon(this.name());
    }
  }

  public void processOverture(SourceFeature sf, FeatureCollector features) {
    // Filter by type field - Overture theme
    if (!"buildings".equals(sf.getString("theme"))) {
      return;
    }

    // Ignore non-building types
    if (!"building".equals(sf.getString("type")) && !"building_part".equals(sf.getString("type"))) {
      return;
    }

    boolean isPart = "building_part".equals(sf.getString("type"));
    // A building with parts is drawn by its parts. Its own outline is the envelope of every part at the tallest
    // part's height, which would hide a tower's shape inside a plain prism.
    if (!isPart && Boolean.TRUE.equals(sf.getTag("has_parts")) || isModeledLandmark(sf)) {
      return;
    }
    // Overture gives height in meters and floors as `num_floors`; floors convert the same way as `building:levels`.
    var height = parseHeight(
      sf.hasTag("height") ? String.valueOf(sf.getTag("height")) : null,
      sf.hasTag("num_floors") ? String.valueOf(sf.getTag("num_floors")) : null,
      sf.hasTag("min_height") ? String.valueOf(sf.getTag("min_height")) : null);

    var feature = roundedFootprint(sf, features)
      .setAttr("kind", isPart ? "building_part" : "building")
      // NOTE: Height is quantized by zoom in a post-process step
      .setAttr(HEIGHT_KEY, height.height())
      .setAttr("sort_rank", 400)
      .setZoomRange(isPart ? 13 : 11, 15)
      .setMinPixelSize(0)
      .setMinPixelSizeOverrides(MERGED_ZOOMS_MIN_PIXEL_SIZE);

    if (isPart) {
      feature.setAttr(MIN_HEIGHT_KEY, height.min_height());
    }
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) throws GeometryException {
    if (zoom == 15) {
      List<VectorTile.Feature> buildings = new ArrayList<>();

      // deduplicate addresses
      HashMap<Map<String, Object>, List<VectorTile.Feature>> groupedAddresses = new LinkedHashMap<>();
      for (VectorTile.Feature item : items) {
        if (item.tags().containsKey("addr_housenumber")) {
          groupedAddresses.computeIfAbsent(item.tags(), k -> new ArrayList<>()).add(item);
        } else {
          buildings.add(item);
        }
      }

      for (var address : groupedAddresses.values()) {
        var feature = address.get(0);
        feature.tags().remove("addr_street");
        buildings.add(feature);
      }

      return buildings;
    }
    items = Area.filterArea(items, 0);

    if (zoom >= 15)
      return items;

    // quantize height by zoom when less than max_zoom 15 to facilitate better feature merging
    for (var item : items) {
      if (item.tags().containsKey(HEIGHT_KEY)) {
        var height = (double) item.tags().get(HEIGHT_KEY);

        // Protected against NULL values
        if (height > 0) {
          // at zoom <= 12 round height to nearest 20 meters
          if (zoom <= 12) {
            height = quantizeVal(height, 20);
          } else
          // at zoom 13 round height to nearest 10 meters
          if (zoom == 13) {
            height = quantizeVal(height, 10);
          } else
          // at zoom 14 round height to nearest 5 meters
          if (zoom == 14) {
            height = quantizeVal(height, 5);
          }

          item.tags().put(HEIGHT_KEY, height);
        }
      }

      if (item.tags().containsKey(MIN_HEIGHT_KEY)) {
        var minHeight = (double) item.tags().get(MIN_HEIGHT_KEY);

        // Protected against NULL values
        if (minHeight > 0) {
          if (zoom <= 12) {
            minHeight = quantizeVal(minHeight, 20);
          } else
          // at zoom 13 round height to nearest 10 meters
          if (zoom == 13) {
            minHeight = quantizeVal(minHeight, 10);
          } else
          // at zoom 14 round height to nearest 5 meters
          if (zoom == 14) {
            minHeight = quantizeVal(minHeight, 5);
          }

          item.tags().put(MIN_HEIGHT_KEY, minHeight);
        }
      }
    }
    // Below z13 nearby footprints merge into blocks. From z13 every footprint is kept, so the set of buildings is the
    // same at z13, z14 and z15 and buildings do not appear in steps as the map zooms in.
    double minArea = zoom >= 13 ? 0 : 3.125;
    return FeatureMerge.mergeNearbyPolygons(items, minArea, minArea, 0.5, 0.5);
  }
}
