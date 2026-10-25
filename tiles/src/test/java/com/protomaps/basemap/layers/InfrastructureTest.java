package com.protomaps.basemap.layers;

import static com.onthegomap.planetiler.TestUtils.newLineString;
import static com.onthegomap.planetiler.TestUtils.newPoint;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SimpleFeature;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InfrastructureTest extends LayerTest {

  private List<FeatureCollector.Feature> infrastructure(FeatureCollector collector) {
    return StreamSupport.stream(collector.spliterator(), false)
      .filter(f -> f.getLayer().equals(Infrastructure.LAYER_NAME))
      .toList();
  }

  private FeatureCollector point(String... tags) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < tags.length; i += 2) {
      map.put(tags[i], tags[i + 1]);
    }
    return process(SimpleFeature.create(newPoint(0.5, 0.5), map, "osm", null, 1));
  }

  @ParameterizedTest
  @CsvSource({
    "amenity, bench, amenity, bench",
    "amenity, waste_basket, amenity, waste_basket",
    "amenity, post_box, amenity, post_box",
    "amenity, vending_machine, amenity, vending_machine",
    "emergency, fire_hydrant, emergency, hydrant",
    "barrier, bollard, barrier, bollard",
    "highway, street_lamp, highway, lamp",
    "highway, traffic_signals, highway, signal",
    "highway, stop, highway, sign",
    "man_made, utility_pole, man_made, pole",
  })
  void streetFurnitureCarriesItsProp(String key, String value, String kind, String prop) {
    assertFeatures(15,
      List.of(Map.of("kind", kind, "kind_detail", value, "prop", prop, "_minzoom", 15)),
      infrastructure(point(key, value)));
  }

  @Test
  void signalKeepsItsDirection() {
    assertFeatures(15,
      List.of(Map.of("prop", "signal", "direction", "forward")),
      infrastructure(point("highway", "traffic_signals", "direction", "forward")));
  }

  @Test
  void pointWithoutAModelHasNoProp() {
    var features = infrastructure(point("power", "tower"));
    assertFeatures(15, List.of(Map.of("kind", "power")), features);
    assertTrue(features.getFirst().getAttrsAtZoom(15).get("prop") == null);
  }

  @Test
  void amenityWayIsNotInfrastructure() {
    assertFeatures(15, List.of(),
      infrastructure(process(SimpleFeature.create(newLineString(0, 0, 1, 1), new HashMap<>(Map.of("amenity", "bench")),
        "osm", null, 1))));
  }
}
