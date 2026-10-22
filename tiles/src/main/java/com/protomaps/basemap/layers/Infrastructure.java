package com.protomaps.basemap.layers;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.ForwardingProfile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.protomaps.basemap.feature.FeatureId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility networks, barriers, street furniture and road-side signals that the Protomaps schema leaves out.
 *
 * <p>
 * Each feature carries {@code kind}, the OSM key that classified it ({@code power}, {@code telecom}, {@code water},
 * {@code barrier}, {@code emergency}, {@code highway}, {@code amenity}, {@code leisure}, {@code natural},
 * {@code man_made}), and {@code kind_detail}, the tag value.
 * Lines become line features, nodes point features, and closed areas a polygon plus a point at its centroid so a
 * style can place an icon on it.
 * </p>
 */
public class Infrastructure implements ForwardingProfile.LayerPostProcessor {

  public static final String LAYER_NAME = "infrastructure";

  /**
   * Minimum zoom for each kind and value; a value absent from its map is not kept.
   */
  private static final Map<String, Map<String, Integer>> KEPT = Map.of(
    "power", Map.of(
      "line", 12, "minor_line", 14, "tower", 14, "pole", 15, "portal", 15,
      "substation", 13, "plant", 12, "generator", 15, "transformer", 15),
    "telecom", Map.of(
      "exchange", 14, "data_center", 14, "connection_point", 15, "line", 14),
    "barrier", Map.of(
      "fence", 15, "wall", 15, "retaining_wall", 15, "hedge", 15, "gate", 15, "bollard", 15, "lift_gate", 15,
      "kerb", 15),
    "emergency", Map.of(
      "fire_hydrant", 15, "water_tank", 15, "suction_point", 15, "defibrillator", 15, "siren", 15),
    // Nodes on the road network: signals, signs, crossings and lighting.
    "highway", Map.of(
      "traffic_signals", 15, "stop", 15, "give_way", 15, "crossing", 15, "street_lamp", 15,
      "speed_camera", 15, "traffic_mirror", 15),
    // Street furniture.
    "amenity", Map.ofEntries(
      Map.entry("bench", 15), Map.entry("waste_basket", 15), Map.entry("post_box", 15),
      Map.entry("bicycle_parking", 15), Map.entry("vending_machine", 15), Map.entry("recycling", 15),
      Map.entry("drinking_water", 15), Map.entry("telephone", 15)),
    "leisure", Map.of("picnic_table", 15),
    "natural", Map.of("tree_row", 15),
    "man_made", Map.ofEntries(
      Map.entry("mast", 14), Map.entry("tower", 14), Map.entry("street_cabinet", 15),
      Map.entry("water_tower", 14), Map.entry("storage_tank", 15), Map.entry("reservoir_covered", 15),
      Map.entry("water_works", 13), Map.entry("wastewater_plant", 13), Map.entry("pumping_station", 15),
      Map.entry("pipeline", 14), Map.entry("embankment", 15), Map.entry("manhole", 15),
      Map.entry("utility_pole", 15))
  );

  /**
   * Keys checked in order; the first key with a kept value classifies the feature.
   */
  private static final List<String> KEYS = List.of("power", "telecom", "emergency", "barrier", "highway", "amenity",
    "leisure", "natural", "man_made");

  /**
   * Keys kept only as points, because a way with the same tag is drawn by another layer.
   */
  private static final Set<String> POINT_ONLY_KEYS = Set.of("highway", "amenity", "leisure");

  /**
   * {@code man_made} values that belong to the water network rather than general structures.
   */
  private static final Set<String> WATER_WORKS = Set.of(
    "water_tower", "storage_tank", "reservoir_covered", "water_works", "wastewater_plant", "pumping_station");

  private static final Set<String> WATER_SUBSTANCES = Set.of("water", "sewage", "rainwater", "hot_water");

  @Override
  public String name() {
    return LAYER_NAME;
  }

  public void processOsm(SourceFeature sf, FeatureCollector features) {
    for (String key : KEYS) {
      if (!sf.hasTag(key)) {
        continue;
      }
      String value = sf.getString(key);
      Integer minZoom = KEPT.get(key).get(value);
      if (minZoom == null || (POINT_ONLY_KEYS.contains(key) && !sf.isPoint())) {
        continue;
      }

      String kind = key.equals("man_made") && WATER_WORKS.contains(value) ? "water" : key;
      // A pipeline's substance decides its network: water and sewage are water, gas and oil stay man_made.
      if (key.equals("man_made") && value.equals("pipeline") &&
        sf.hasTag("substance") && WATER_SUBSTANCES.contains(sf.getString("substance"))) {
        kind = "water";
      }
      // A mast or tower carrying antennas is part of the telecom network.
      if (key.equals("man_made") && (value.equals("mast") || value.equals("tower")) &&
        sf.hasTag("tower:type", "communication")) {
        kind = "telecom";
      }
      if (key.equals("man_made") && value.equals("street_cabinet") && sf.hasTag("street_cabinet", "telecom")) {
        kind = "telecom";
      }

      FeatureCollector.Feature feature;
      if (sf.isPoint()) {
        feature = features.point(LAYER_NAME);
      } else if (sf.canBePolygon() && !isLinear(key, value)) {
        feature = features.polygon(LAYER_NAME).setMinPixelSize(2);
        decorate(features.centroid(LAYER_NAME), sf, kind, value, Math.max(minZoom, 15));
      } else if (sf.canBeLine()) {
        feature = features.line(LAYER_NAME).setMinPixelSize(0);
      } else {
        return;
      }
      decorate(feature, sf, kind, value, minZoom);
      return;
    }
  }

  /**
   * Whether a closed way of this kind is still a line, such as a fence around a field.
   */
  private static boolean isLinear(String key, String value) {
    return key.equals("barrier") || value.equals("line") || value.equals("minor_line") || value.equals("tree_row") ||
      value.equals("pipeline") || value.equals("embankment");
  }

  private static void decorate(FeatureCollector.Feature feature, SourceFeature sf, String kind, String value,
    int minZoom) {
    feature
      .setId(FeatureId.create(sf))
      .setAttr("kind", kind)
      .setAttr("kind_detail", value)
      .setAttr("operator", sf.getString("operator"))
      .setAttr("voltage", sf.getString("voltage"))
      .setAttr("name", sf.getString("name"))
      // Fibre and copper telecom lines, as OSM's telecom:medium records them.
      .setAttr("medium", sf.getString("telecom:medium"))
      // A crossing's markings (zebra, marked, traffic_signals) and a kerb's height (lowered, raised, flush).
      .setAttr("crossing", sf.getString("crossing"))
      .setAttr("kerb", sf.getString("kerb"))
      .setZoomRange(minZoom, 15);
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) {
    return items;
  }
}
