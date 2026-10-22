package com.protomaps.basemap.feature;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;

/**
 * Corner rounding for road lines, by Chaikin's corner-cutting algorithm.
 *
 * <p>
 * Each pass replaces every interior vertex with two points, one on each adjoining segment. A plain Chaikin pass
 * places them a quarter of the way along each segment, which on a long segment moves the road far from its surveyed
 * position. Here the distance is capped at {@link #MAX_CUT_METERS}, so only the corner itself rounds. End vertices
 * never move, which keeps junctions connected to the roads that meet them.
 * </p>
 */
public final class Smoothing {

  /**
   * The farthest a cut point may sit from the vertex it replaces, in meters.
   */
  public static final double MAX_CUT_METERS = 6;

  private static final int PASSES = 3;

  /**
   * Meters per world unit at the equator; Planetiler's world coordinates span the earth's circumference as 0 to 1.
   */
  private static final double EQUATOR_METERS = 40_075_016.686;

  private Smoothing() {}

  /**
   * Rounds the corners of a line or multiline in Planetiler world coordinates; any other geometry is returned as is.
   */
  public static Geometry smoothWorldLine(Geometry geometry) {
    if (geometry instanceof LineString line) {
      return smooth(line);
    }
    if (geometry instanceof MultiLineString multi) {
      var parts = new LineString[multi.getNumGeometries()];
      for (int i = 0; i < parts.length; i++) {
        parts[i] = smooth((LineString) multi.getGeometryN(i));
      }
      return multi.getFactory().createMultiLineString(parts);
    }
    return geometry;
  }

  private static LineString smooth(LineString line) {
    Coordinate[] coords = line.getCoordinates();
    if (coords.length < 3) {
      return line;
    }
    double maxCut = maxCutWorldUnits(coords[0].y);
    for (int pass = 0; pass < PASSES; pass++) {
      coords = cutCorners(coords, maxCut);
    }
    return line.getFactory().createLineString(coords);
  }

  private static Coordinate[] cutCorners(Coordinate[] coords, double maxCut) {
    List<Coordinate> out = new ArrayList<>(coords.length * 2);
    out.add(coords[0]);
    for (int i = 1; i < coords.length - 1; i++) {
      Coordinate prev = coords[i - 1];
      Coordinate vertex = coords[i];
      Coordinate next = coords[i + 1];
      out.add(towards(vertex, prev, maxCut));
      out.add(towards(vertex, next, maxCut));
    }
    out.add(coords[coords.length - 1]);
    return out.toArray(Coordinate[]::new);
  }

  /**
   * The point a quarter of the way from {@code from} to {@code to}, but at most {@code maxCut} from {@code from}.
   */
  private static Coordinate towards(Coordinate from, Coordinate to, double maxCut) {
    double dx = to.x - from.x;
    double dy = to.y - from.y;
    double length = Math.hypot(dx, dy);
    if (length == 0) {
      return new Coordinate(from);
    }
    double distance = Math.min(length * 0.25, maxCut);
    return new Coordinate(from.x + dx / length * distance, from.y + dy / length * distance);
  }

  /**
   * Converts {@link #MAX_CUT_METERS} to world units at the latitude of world y coordinate {@code worldY}.
   */
  private static double maxCutWorldUnits(double worldY) {
    double latitude = Math.atan(Math.sinh(Math.PI * (1 - 2 * worldY)));
    return MAX_CUT_METERS / (EQUATOR_METERS * Math.cos(latitude));
  }
}
