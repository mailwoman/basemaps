package com.protomaps.basemap.layers;

import java.util.Locale;
import java.util.Map;

/**
 * Building attributes derived at build time: where a height came from, a color in one hex form, and a district index
 * that groups neighboring buildings.
 *
 * <p>
 * The color names are ported from Arnis (https://github.com/louis-e/arnis, Apache-2.0, {@code src/colors.rs}).
 */
final class BuildingAttributes {

  private BuildingAttributes() {}

  /** Where a building's height came from. A building with neither leaves {@code height_src} empty. */
  static final String HEIGHT_FROM_TAG = "height";
  static final String HEIGHT_FROM_FLOORS = "floors";

  /** The side of a district cell in meters. Buildings in one cell share a district index. */
  static final double DISTRICT_CELL_METERS = 128;
  static final int DISTRICT_COUNT = 16;

  /**
   * The district index of a point: a hash of the 128 m cell that holds it, from 0 to 15.
   *
   * <p>
   * A map style picks a building's palette entry from this index, so a street reads as one fabric while
   * neighboring districts differ.
   */
  static int district(double lon, double lat) {
    long cellX = (long) Math.floor(lon * 111_320 * Math.cos(Math.toRadians(lat)) / DISTRICT_CELL_METERS);
    long cellY = (long) Math.floor(lat * 110_540 / DISTRICT_CELL_METERS);
    long h = cellX * 0x9E3779B97F4A7C15L ^ cellY * 0xC2B2AE3D27D4EB4FL;
    h ^= h >>> 31;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return (int) Math.floorMod(h, (long) DISTRICT_COUNT);
  }

  private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
    Map.entry("aqua", "#00ffff"), Map.entry("cyan", "#00ffff"),
    Map.entry("beige", "#bbad8e"),
    Map.entry("black", "#000000"),
    Map.entry("blue", "#0000ff"),
    Map.entry("brown", "#804000"),
    Map.entry("darkgray", "#606060"), Map.entry("darkgrey", "#606060"),
    Map.entry("darkbrown", "#5a3214"),
    Map.entry("darkred", "#8b0000"),
    Map.entry("dimgray", "#696969"), Map.entry("dimgrey", "#696969"),
    Map.entry("firebrick", "#b22222"),
    Map.entry("fuchsia", "#ff00ff"), Map.entry("magenta", "#ff00ff"),
    Map.entry("gold", "#ffd700"),
    Map.entry("gray", "#808080"), Map.entry("grey", "#808080"),
    Map.entry("green", "#008000"),
    Map.entry("ivory", "#fffff0"),
    Map.entry("khaki", "#f0e68c"),
    Map.entry("lightblue", "#add8e6"),
    Map.entry("lightgray", "#d3d3d3"), Map.entry("lightgrey", "#d3d3d3"),
    Map.entry("lightgreen", "#90ee90"),
    Map.entry("lightyellow", "#ffffe0"),
    Map.entry("lime", "#00ff00"),
    Map.entry("limestone", "#f6f0d0"),
    Map.entry("maroon", "#800000"),
    Map.entry("navy", "#000080"),
    Map.entry("olive", "#808000"),
    Map.entry("orange", "#ff8000"),
    Map.entry("pink", "#ffc0cb"),
    Map.entry("purple", "#800080"),
    Map.entry("red", "#ff0000"),
    Map.entry("salmon", "#fa8072"),
    Map.entry("sandstone", "#d7bc8a"),
    Map.entry("silver", "#c0c0c0"),
    Map.entry("tan", "#d2b48c"),
    Map.entry("teal", "#008080"),
    Map.entry("white", "#ffffff"),
    Map.entry("yellow", "#ffff00")
  );

  /**
   * A color tag as a lowercase {@code #rrggbb} string, or null when the value is neither a hex color nor a known
   * color name.
   */
  static String normalizeColor(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    String trimmed = text.trim().toLowerCase(Locale.ROOT);
    if (trimmed.matches("#[0-9a-f]{6}")) {
      return trimmed;
    }
    if (trimmed.matches("#[0-9a-f]{3}")) {
      return "#" + trimmed.charAt(1) + trimmed.charAt(1) + trimmed.charAt(2) + trimmed.charAt(2) + trimmed.charAt(3) +
        trimmed.charAt(3);
    }
    return NAMED_COLORS.get(trimmed.replaceAll("[\\s_-]", ""));
  }
}
