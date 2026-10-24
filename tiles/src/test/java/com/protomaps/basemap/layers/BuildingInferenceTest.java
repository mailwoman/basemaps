package com.protomaps.basemap.layers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildingInferenceTest {

  @Test
  void inferredHeightRepeatsForOneId() {
    var first = BuildingInference.inferHeight("apartments", 500, 42);
    for (int i = 0; i < 5; i++) {
      assertEquals(first, BuildingInference.inferHeight("apartments", 500, 42));
    }
  }

  @Test
  void apartmentsStandThreeToSixFloors() {
    for (long id = 0; id < 200; id++) {
      var inferred = BuildingInference.inferHeight("apartments", 500, id);
      assertTrue(inferred.meters() >= 3 * 3 + 2 && inferred.meters() <= 6 * 3 + 2, () -> "got " + inferred);
    }
  }

  @Test
  void aWarehouseIsAHall() {
    assertTrue(BuildingInference.inferHeight("warehouse", 2000, 7).hall());
  }

  @Test
  void anUnclassifiedLargeFootprintStaysWithinItsTable() {
    for (long id = 0; id < 200; id++) {
      double meters = BuildingInference.inferHeight(null, 5000, id).meters();
      assertTrue(meters == 7 || meters == 3 * 3 + 2 || meters == 4 * 3 + 2, "got " + meters);
    }
  }

  @Test
  void districtIsSharedWithinACellAndInRange() {
    assertEquals(BuildingInference.district(139.7454, 35.6586), BuildingInference.district(139.74541, 35.65861));
    for (double lon = -180; lon < 180; lon += 7.3) {
      int d = BuildingInference.district(lon, 35);
      assertTrue(d >= 0 && d < BuildingInference.DISTRICT_COUNT);
    }
  }

  @Test
  void colorsNormalizeToSixDigitHex() {
    assertEquals("#aabbcc", BuildingInference.normalizeColor("#ABC"));
    assertEquals("#ffffff", BuildingInference.normalizeColor("#FFFFFF"));
    assertEquals("#808080", BuildingInference.normalizeColor("Grey"));
    assertEquals("#d3d3d3", BuildingInference.normalizeColor("light_grey"));
    assertNull(BuildingInference.normalizeColor("sparkly"));
    assertNull(BuildingInference.normalizeColor(12));
  }

  @Test
  void areaConvertsAtTheEquator() {
    double side = 10 / 40_075_016.686;
    assertEquals(100, BuildingInference.worldAreaToSquareMeters(side * side, 0), 1e-6);
  }
}
