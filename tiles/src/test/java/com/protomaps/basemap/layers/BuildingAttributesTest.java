package com.protomaps.basemap.layers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildingAttributesTest {

  @Test
  void districtIsSharedWithinACellAndInRange() {
    assertEquals(BuildingAttributes.district(139.7454, 35.6586), BuildingAttributes.district(139.74541, 35.65861));
    for (double lon = -180; lon < 180; lon += 7.3) {
      int d = BuildingAttributes.district(lon, 35);
      assertTrue(d >= 0 && d < BuildingAttributes.DISTRICT_COUNT);
    }
  }

  @Test
  void colorsNormalizeToSixDigitHex() {
    assertEquals("#aabbcc", BuildingAttributes.normalizeColor("#ABC"));
    assertEquals("#ffffff", BuildingAttributes.normalizeColor("#FFFFFF"));
    assertEquals("#808080", BuildingAttributes.normalizeColor("Grey"));
    assertEquals("#d3d3d3", BuildingAttributes.normalizeColor("light_grey"));
    assertNull(BuildingAttributes.normalizeColor("sparkly"));
    assertNull(BuildingAttributes.normalizeColor(12));
  }
}
