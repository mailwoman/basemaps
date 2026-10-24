package com.protomaps.basemap.layers;

import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Fills building attributes the source leaves empty: a height from the building's class and footprint, a color in
 * one hex form, and a district index that groups neighboring buildings.
 *
 * <p>
 * The level tables and the color names are ported from Arnis (https://github.com/louis-e/arnis, Apache-2.0,
 * {@code src/element_processing/buildings.rs} and {@code src/colors.rs}).
 */
final class BuildingInference {

  private BuildingInference() {}

  /** Where a building's height came from. */
  static final String HEIGHT_FROM_TAG = "height";
  static final String HEIGHT_FROM_FLOORS = "floors";
  static final String HEIGHT_FROM_CLASS = "inferred";

  /** A height in meters and whether the building is a single-volume hall rather than stacked floors. */
  record InferredHeight(double meters, boolean hall) {}

  private static final double EARTH_CIRCUMFERENCE_METERS = 40_075_016.686;

  /** Converts a Planetiler world-coordinate area at a latitude to square meters. */
  static double worldAreaToSquareMeters(double worldArea, double latitude) {
    double scale = EARTH_CIRCUMFERENCE_METERS * Math.cos(Math.toRadians(latitude));
    return worldArea * scale * scale;
  }

  /** A weighted choice of floor counts, or of hall heights in meters, as {value, weight} pairs. */
  private record Table(boolean hall, int[][] options) {}

  private static Table levels(int[]... options) {
    return new Table(false, options);
  }

  private static Table hall(int[]... options) {
    return new Table(true, options);
  }

  private static int[] w(int value, int weight) {
    return new int[]{value, weight};
  }

  private static final Map<String, Table> TABLES_BY_CLASS = Map.ofEntries(
    Map.entry("bungalow", levels(w(1, 100))),
    Map.entry("static_caravan", levels(w(1, 100))),
    Map.entry("house", levels(w(1, 25), w(2, 55), w(3, 20))),
    Map.entry("detached", levels(w(1, 25), w(2, 55), w(3, 20))),
    Map.entry("villa", levels(w(1, 25), w(2, 55), w(3, 20))),
    Map.entry("farm", levels(w(1, 25), w(2, 55), w(3, 20))),
    Map.entry("semidetached_house", levels(w(2, 70), w(3, 30))),
    Map.entry("terrace", levels(w(2, 50), w(3, 40), w(4, 10))),
    Map.entry("apartments", levels(w(3, 30), w(4, 30), w(5, 25), w(6, 15))),
    Map.entry("residential", levels(w(3, 30), w(4, 30), w(5, 25), w(6, 15))),
    Map.entry("dormitory", levels(w(3, 30), w(4, 30), w(5, 25), w(6, 15))),
    Map.entry("hotel", levels(w(3, 25), w(4, 30), w(5, 25), w(6, 10), w(8, 10))),
    Map.entry("retail", levels(w(1, 60), w(2, 40))),
    Map.entry("shop", levels(w(1, 60), w(2, 40))),
    Map.entry("supermarket", levels(w(1, 60), w(2, 40))),
    Map.entry("kiosk", levels(w(1, 100))),
    Map.entry("parking", levels(w(2, 30), w(3, 40), w(4, 20), w(5, 10))),
    Map.entry("school", levels(w(2, 50), w(3, 40), w(4, 10))),
    Map.entry("kindergarten", levels(w(2, 50), w(3, 40), w(4, 10))),
    Map.entry("college", levels(w(2, 50), w(3, 40), w(4, 10))),
    Map.entry("university", levels(w(2, 50), w(3, 40), w(4, 10))),
    Map.entry("hospital", levels(w(4, 40), w(5, 30), w(6, 20), w(7, 10))),
    Map.entry("cathedral", hall(w(18, 50), w(21, 30), w(24, 20))),
    Map.entry("church", hall(w(10, 50), w(12, 30), w(14, 20))),
    Map.entry("chapel", hall(w(10, 50), w(12, 30), w(14, 20))),
    Map.entry("mosque", hall(w(10, 50), w(12, 30), w(14, 20))),
    Map.entry("synagogue", hall(w(10, 50), w(12, 30), w(14, 20))),
    Map.entry("temple", hall(w(10, 50), w(12, 30), w(14, 20))),
    Map.entry("religious", hall(w(10, 50), w(12, 30), w(14, 20))),
    Map.entry("industrial", hall(w(5, 20), w(7, 50), w(9, 30))),
    Map.entry("warehouse", hall(w(5, 20), w(7, 50), w(9, 30))),
    Map.entry("hangar", hall(w(5, 20), w(7, 50), w(9, 30))),
    Map.entry("barn", hall(w(5, 20), w(7, 50), w(9, 30))),
    Map.entry("stable", hall(w(5, 20), w(7, 50), w(9, 30))),
    Map.entry("garage", hall(w(3, 100))),
    Map.entry("garages", hall(w(3, 100))),
    Map.entry("carport", hall(w(3, 100))),
    Map.entry("shed", hall(w(3, 100))),
    Map.entry("hut", hall(w(3, 100))),
    Map.entry("cabin", levels(w(1, 80), w(2, 20)))
  );

  /**
   * Infers a height for a building whose source gives neither a height nor a floor count.
   *
   * <p>
   * The choice is drawn from a table for the building's class, or for an office, commercial or unclassified building
   * from a table for its footprint area. The draw is seeded by the feature id, so every build gives a building the
   * same height. Floors convert to meters as {@code building:levels} does in {@link Buildings#parseHeight}.
   */
  static InferredHeight inferHeight(String buildingClass, double areaSquareMeters, long seed) {
    var rng = new SplittableRandom(seed ^ 0x48E1_6F00_1EA5_0001L);
    Table table = tableFor(buildingClass == null ? "" : buildingClass, areaSquareMeters, rng);
    int value = pickWeighted(rng, table.options());
    return table.hall() ? new InferredHeight(value, true) : new InferredHeight(value * 3 + 2, false);
  }

  private static Table tableFor(String buildingClass, double area, SplittableRandom rng) {
    var table = TABLES_BY_CLASS.get(buildingClass);
    if (table != null) {
      return table;
    }
    if (buildingClass.equals("office") || buildingClass.equals("commercial")) {
      if (area < 300) {
        return levels(w(2, 40), w(3, 60));
      }
      return area < 1000 ? levels(w(3, 40), w(4, 35), w(5, 25)) : levels(w(4, 40), w(5, 30), w(6, 20), w(8, 10));
    }
    if (area < 40) {
      return rng.nextBoolean() ? hall(w(3, 100)) : levels(w(1, 100));
    }
    if (area < 150) {
      return levels(w(1, 15), w(2, 60), w(3, 25));
    }
    if (area < 600) {
      return levels(w(2, 40), w(3, 40), w(4, 20));
    }
    int roll = rng.nextInt(100);
    if (roll < 40) {
      return hall(w(7, 100));
    }
    return roll < 75 ? levels(w(3, 100)) : levels(w(4, 100));
  }

  private static int pickWeighted(SplittableRandom rng, int[][] options) {
    int total = 0;
    for (int[] option : options) {
      total += option[1];
    }
    int roll = rng.nextInt(total);
    for (int[] option : options) {
      if (roll < option[1]) {
        return option[0];
      }
      roll -= option[1];
    }
    return options[options.length - 1][0];
  }

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
