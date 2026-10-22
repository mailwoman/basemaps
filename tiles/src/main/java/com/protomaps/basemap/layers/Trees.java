package com.protomaps.basemap.layers;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.ForwardingProfile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.util.Parse;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Polygon;

/**
 * Trees as extrudable shapes: each tree becomes a trunk and a crown polygon with heights, so a style can draw it
 * with two {@code fill-extrusion} layers.
 *
 * <p>
 * Sources are OSM {@code natural=tree} nodes and {@code natural=tree_row} ways; a row becomes one tree every
 * {@link #ROW_SPACING_METERS} along its length. Each feature carries {@code kind} ({@code trunk} or {@code crown}),
 * {@code height} and {@code min_height} in meters, and {@code leaf_type} when the tree has one.
 * </p>
 */
public class Trees implements ForwardingProfile.LayerPostProcessor {

  public static final String LAYER_NAME = "trees";

  /**
   * Height used when a tree has no {@code height} tag; a typical Japanese street tree.
   */
  static final double DEFAULT_HEIGHT_METERS = 8;
  /**
   * Crown diameter used when a tree has no {@code diameter_crown} tag.
   */
  static final double DEFAULT_CROWN_METERS = 5;
  static final double ROW_SPACING_METERS = 7;
  static final double TRUNK_RADIUS_METERS = 0.35;
  /**
   * Fraction of the height where the crown starts; the trunk ends there.
   */
  static final double CROWN_BASE_FRACTION = 0.35;
  private static final int SIDES = 8;
  private static final double EQUATOR_METERS = 40_075_016.686;
  private static final int MAX_ROW_TREES = 2_000;

  private static final GeometryFactory FACTORY = new GeometryFactory();

  @Override
  public String name() {
    return LAYER_NAME;
  }

  public void processOsm(SourceFeature sf, FeatureCollector features) {
    if (!sf.hasTag("natural", "tree", "tree_row")) {
      return;
    }
    double height = positive(Parse.meters(sf.getString("height")), DEFAULT_HEIGHT_METERS);
    double crown = positive(Parse.meters(sf.getString("diameter_crown")), DEFAULT_CROWN_METERS);
    String leafType = sf.getString("leaf_type");

    try {
      if (sf.isPoint() && sf.hasTag("natural", "tree")) {
        emitTree(features, sf.worldGeometry().getCoordinate(), height, crown, leafType);
      } else if (sf.canBeLine() && sf.hasTag("natural", "tree_row")) {
        for (Coordinate position : alongLine(sf.line())) {
          emitTree(features, position, height, crown, leafType);
        }
      }
    } catch (GeometryException e) {
      e.log("Failed to build tree geometry");
    }
  }

  /**
   * Handles a treetop detected in a canopy height raster: a point with a {@code height} column in meters.
   *
   * <p>
   * The crown diameter follows height, as a raster treetop carries no crown measurement.
   * </p>
   */
  public void processTreetop(SourceFeature sf, FeatureCollector features) {
    if (!sf.isPoint() || !(sf.getTag("height") instanceof Number number)) {
      return;
    }
    double height = positive(number.doubleValue(), DEFAULT_HEIGHT_METERS);
    try {
      emitTree(features, sf.worldGeometry().getCoordinate(), height, Math.min(12, Math.max(3, height * 0.6)), null);
    } catch (GeometryException e) {
      e.log("Failed to build treetop geometry");
    }
  }

  private static double positive(Double value, double fallback) {
    return value != null && value > 0 && value < 150 ? value : fallback;
  }

  private void emitTree(FeatureCollector features, Coordinate center, double height, double crown, String leafType) {
    double metersToWorld = 1 / (EQUATOR_METERS * Math.cos(latitude(center.y)));
    double crownBase = height * CROWN_BASE_FRACTION;
    // Needle-leaved trees are narrow for their height.
    double crownRadius = "needleleaved".equals(leafType) ? crown * 0.35 : crown * 0.5;

    features.geometry(LAYER_NAME, octagon(center, TRUNK_RADIUS_METERS * metersToWorld))
      .setAttr("kind", "trunk")
      .setAttr("height", round(crownBase + 0.5))
      .setAttr("min_height", 0)
      .setZoomRange(15, 15)
      .setMinPixelSize(0);

    features.geometry(LAYER_NAME, octagon(center, crownRadius * metersToWorld))
      .setAttr("kind", "crown")
      .setAttr("height", round(height))
      .setAttr("min_height", round(crownBase))
      .setAttr("leaf_type", leafType)
      .setZoomRange(15, 15)
      .setMinPixelSize(0);
  }

  /**
   * Positions every {@link #ROW_SPACING_METERS} along a line in world coordinates, starting half a spacing in.
   */
  private static List<Coordinate> alongLine(Geometry geometry) {
    List<LineString> lines = new ArrayList<>();
    if (geometry instanceof LineString line) {
      lines.add(line);
    } else if (geometry instanceof MultiLineString multi) {
      for (int i = 0; i < multi.getNumGeometries(); i++) {
        lines.add((LineString) multi.getGeometryN(i));
      }
    }
    List<Coordinate> positions = new ArrayList<>();
    for (LineString line : lines) {
      Coordinate[] coords = line.getCoordinates();
      if (coords.length < 2) {
        continue;
      }
      double spacing = ROW_SPACING_METERS / (EQUATOR_METERS * Math.cos(latitude(coords[0].y)));
      double untilNext = spacing / 2;
      for (int i = 1; i < coords.length && positions.size() < MAX_ROW_TREES; i++) {
        Coordinate from = coords[i - 1];
        Coordinate to = coords[i];
        double length = from.distance(to);
        double travelled = 0;
        while (travelled + untilNext <= length && positions.size() < MAX_ROW_TREES) {
          travelled += untilNext;
          double t = travelled / length;
          positions.add(new Coordinate(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t));
          untilNext = spacing;
        }
        untilNext -= length - travelled;
      }
    }
    return positions;
  }

  private static Polygon octagon(Coordinate center, double radius) {
    Coordinate[] ring = new Coordinate[SIDES + 1];
    for (int i = 0; i < SIDES; i++) {
      double angle = 2 * Math.PI * i / SIDES;
      ring[i] = new Coordinate(center.x + radius * Math.cos(angle), center.y + radius * Math.sin(angle));
    }
    ring[SIDES] = ring[0];
    return FACTORY.createPolygon(ring);
  }

  private static double latitude(double worldY) {
    return Math.atan(Math.sinh(Math.PI * (1 - 2 * worldY)));
  }

  private static double round(double meters) {
    return Math.round(meters * 10) / 10.0;
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) {
    return items;
  }
}
